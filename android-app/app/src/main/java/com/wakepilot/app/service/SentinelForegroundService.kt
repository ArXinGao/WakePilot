package com.wakepilot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wakepilot.app.MainActivity
import com.wakepilot.app.R
import com.wakepilot.app.data.ConfigStore
import com.wakepilot.app.data.E2eeCrypto
import com.wakepilot.app.data.SentinelConfig
import com.wakepilot.app.net.LanScanner
import com.wakepilot.app.net.ReconnectingRelaySocket
import com.wakepilot.app.net.RelayTransport
import com.wakepilot.app.net.WolSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SentinelRuntimeState(
    val running: Boolean = false,
    val relayConnected: Boolean = false,
    val statusText: String = "未启动",
    val logs: List<String> = emptyList(),
)

class SentinelForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "wakepilot_sentinel_channel"
        private const val NOTIF_ID = 1001
        private val _state = MutableStateFlow(SentinelRuntimeState())
        val state: StateFlow<SentinelRuntimeState> = _state

        fun start(context: Context) {
            val intent = Intent(context, SentinelForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SentinelForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replayGuard = E2eeCrypto.ReplayGuard()
    private var wakeLock: PowerManager.WakeLock? = null
    private var relaySocket: ReconnectingRelaySocket? = null
    private lateinit var store: ConfigStore

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("家中哨兵启动中..."))
        acquireWakeLock()
        connectRelay()
        return START_STICKY
    }

    override fun onDestroy() {
        relaySocket?.stop()
        relaySocket = null
        releaseWakeLock()
        scope.cancel()
        _state.value = _state.value.copy(running = false, relayConnected = false, statusText = "已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakePilot::SentinelLock").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun appendLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val next = (_state.value.logs + "[$ts] $line").takeLast(40)
        _state.value = _state.value.copy(logs = next)
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun connectRelay() {
        relaySocket?.stop()
        val cfg = store.getOrInitSentinel()
        val roomKey = E2eeCrypto.fromB64Url(cfg.roomKeyB64)
        val channelToken = E2eeCrypto.channelToken(roomKey)

        _state.value = _state.value.copy(running = true, statusText = "正在连接盲中继...")
        appendLog("哨兵启动，中继: ${cfg.relayUrl}")

        relaySocket = ReconnectingRelaySocket(
            wsUrlProvider = {
                val latest = store.getOrInitSentinel()
                val rk = E2eeCrypto.fromB64Url(latest.roomKeyB64)
                RelayTransport.toWebSocketUrl(
                    baseUrl = latest.relayUrl,
                    role = "sentinel",
                    sentinelId = latest.sentinelId,
                    channelToken = E2eeCrypto.channelToken(rk),
                )
            },
            onStatusChange = { connected, info ->
                _state.value = _state.value.copy(running = true, relayConnected = connected, statusText = info)
                updateNotif(if (connected) "家中哨兵在线 · E2EE 守护中" else info)
                appendLog(info)
            },
            onMessage = { raw ->
                scope.launch { handleIncomingEnvelope(raw) }
            },
        )
        relaySocket?.start()
        // Keep channelToken referenced for diagnostics
        appendLog("盲路由频道已建立 (${channelToken.take(10)}...)")
    }

    private fun sendEncryptedToController(cfg: SentinelConfig, payload: JSONObject) {
        val roomKey = E2eeCrypto.fromB64Url(cfg.roomKeyB64)
        val edPriv = E2eeCrypto.fromB64Url(cfg.edPrivB64)
        val env = E2eeCrypto.seal(
            roomKey = roomKey,
            sentinelId = cfg.sentinelId,
            direction = E2eeCrypto.DIR_S2C,
            msgId = "s_" + UUID.randomUUID().toString().take(8),
            plaintextJson = payload.toString(),
            signingPrivKey = edPriv,
        )
        relaySocket?.send(env)
    }

    private suspend fun handleIncomingEnvelope(rawText: String) {
        val cfg = store.getOrInitSentinel()
        val roomKey = E2eeCrypto.fromB64Url(cfg.roomKeyB64)
        val opened = runCatching {
            E2eeCrypto.open(
                roomKey = roomKey,
                expectedSentinelId = cfg.sentinelId,
                expectedDir = E2eeCrypto.DIR_C2S,
                envelopeJson = rawText,
                pinnedSenderPub = null,
                replayGuard = replayGuard,
            )
        }.getOrElse { err ->
            appendLog("拦截非法/重放帧: ${err.message}")
            return
        }

        val cmd = JSONObject(opened.plaintext)
        val type = cmd.optString("type", "").uppercase()
        appendLog("收到 E2EE 指令: $type")

        when (type) {
            "PING" -> pushStatus(cfg)
            "WAKE" -> {
                val appsArr = cmd.optJSONArray("apps")
                val apps = mutableListOf<String>()
                if (appsArr != null) {
                    for (i in 0 until appsArr.length()) apps.add(appsArr.getString(i))
                }
                val autoLock = cmd.optBoolean("auto_lock", true)
                runWakeAndDiagnosticPipeline(cfg, apps, autoLock)
            }
            "LAUNCH_APPS" -> {
                val appsArr = cmd.optJSONArray("apps")
                val apps = mutableListOf<String>()
                if (appsArr != null) {
                    for (i in 0 until appsArr.length()) apps.add(appsArr.getString(i))
                }
                val autoLock = cmd.optBoolean("auto_lock", true)
                val res = callWindowsAgent(cfg, "POST", "/launch", JSONObject().apply {
                    put("apps", JSONArray(apps))
                    put("auto_lock", autoLock)
                })
                sendEncryptedToController(cfg, JSONObject().apply {
                    put("type", "ACK")
                    put("reply_to", opened.id)
                    put("ok", res != null)
                    put("message", if (res != null) "已向电脑下发客户端启动指令" else "Windows 代理未响应，请确认电脑已进桌面")
                })
                pushStatus(cfg)
            }
            "POWER" -> {
                val action = cmd.optString("action", "")
                val res = callWindowsAgent(cfg, "POST", "/power", JSONObject().put("action", action))
                sendEncryptedToController(cfg, JSONObject().apply {
                    put("type", "ACK")
                    put("reply_to", opened.id)
                    put("ok", res != null && res.optBoolean("ok", false))
                    put("message", res?.optString("message") ?: "Windows 代理不在线，无法执行 $action")
                })
            }
            else -> appendLog("未知指令类型: $type")
        }
    }

    private fun pushProgress(cfg: SentinelConfig, jobId: String, stage: String, elapsedSec: Int, message: String, diagnosis: String = "") {
        appendLog("[$stage ${elapsedSec}s] $message")
        sendEncryptedToController(cfg, JSONObject().apply {
            put("type", "PROGRESS")
            put("job_id", jobId)
            put("stage", stage)
            put("elapsed_sec", elapsedSec)
            put("message", message)
            if (diagnosis.isNotBlank()) put("diagnosis", diagnosis)
        })
    }

    private fun pushStatus(cfg: SentinelConfig) {
        val reachable = LanScanner.isHostReachable(cfg.pcIp, 900)
        val health = callWindowsAgent(cfg, "GET", "/health", null)
        sendEncryptedToController(cfg, JSONObject().apply {
            put("type", "STATUS")
            put("sentinel_online", true)
            put("pc_name", cfg.pcName)
            put("pc_mac", cfg.pcMac)
            put("pc_ip", cfg.pcIp)
            put("pc_reachable", reachable)
            put("agent_online", health != null)
            put("rustdesk_id", cfg.rustDeskId)
            if (health != null) put("agent", health)
        })
    }

    private suspend fun runWakeAndDiagnosticPipeline(cfg: SentinelConfig, apps: List<String>, autoLock: Boolean) {
        val jobId = "job_" + UUID.randomUUID().toString().take(6)
        val startMs = System.currentTimeMillis()
        fun elapsed() = ((System.currentTimeMillis() - startMs) / 1000L).toInt()

        if (cfg.pcMac.isBlank()) {
            pushProgress(cfg, jobId, "DIAGNOSIS_FAILED", 0, "尚未配置电脑 MAC 地址", "请在备用手机哨兵界面点击「扫描局域网电脑」选中目标主机。")
            return
        }

        // Check if Windows Agent is already online
        if (callWindowsAgent(cfg, "GET", "/health", null) != null) {
            pushProgress(cfg, jobId, "AGENT_ONLINE", elapsed(), "检测到电脑已在线，直接拉起 AI 客户端...")
            if (apps.isNotEmpty()) {
                callWindowsAgent(cfg, "POST", "/launch", JSONObject().apply {
                    put("apps", JSONArray(apps))
                    put("auto_lock", autoLock)
                })
                pushProgress(cfg, jobId, if (autoLock) "SCREEN_LOCKED" else "DONE", elapsed(), "目标客户端已启动" + if (autoLock) "，3 秒后自动锁屏保护隐私。" else "。")
            } else {
                pushProgress(cfg, jobId, "DONE", elapsed(), "Windows 电脑当前已在线。")
            }
            pushStatus(cfg)
            return
        }

        // Step 1: Fire multi-target WoL burst
        val sentCount = runCatching {
            WolSender.sendBurst(cfg.pcMac, cfg.broadcastIp, cfg.pcIp, burstCount = 3)
        }.getOrDefault(0)
        pushProgress(cfg, jobId, "WOL_SENT", elapsed(), "已向 ${cfg.pcMac} 广播 $sentCount 个魔术包（全网+子网+单播）")

        var retried = false
        var lanSawHost = false

        // Step 2: Poll for up to 65 seconds with automatic retry and diagnostics
        while (elapsed() < 65) {
            delay(2500)
            val sec = elapsed()

            val health = callWindowsAgent(cfg, "GET", "/health", null)
            if (health != null) {
                pushProgress(cfg, jobId, "AGENT_ONLINE", sec, "Windows 桌面代理已上线 (${sec}s)！")
                if (apps.isNotEmpty()) {
                    delay(1500)
                    callWindowsAgent(cfg, "POST", "/launch", JSONObject().apply {
                        put("apps", JSONArray(apps))
                        put("auto_lock", autoLock)
                    })
                    pushProgress(
                        cfg,
                        jobId,
                        if (autoLock) "SCREEN_LOCKED" else "DONE",
                        elapsed(),
                        "已拉起 ${apps.joinToString("/")} " + if (autoLock) "并触发自动锁屏！" else "！",
                    )
                } else {
                    pushProgress(cfg, jobId, "DONE", elapsed(), "Windows 已完全开机就绪！")
                }
                pushStatus(cfg)
                return
            }

            if (!lanSawHost && LanScanner.isHostReachable(cfg.pcIp, 900)) {
                lanSawHost = true
                pushProgress(cfg, jobId, "ARP_UP", sec, "主板已通电且局域网已通 (${sec}s)！若未开启自动登录，现在可点 RustDesk 远程输开机密码。")
            }

            if (!retried && !lanSawHost && sec >= 12) {
                retried = true
                WolSender.sendBurst(cfg.pcMac, cfg.broadcastIp, cfg.pcIp, burstCount = 3)
                pushProgress(cfg, jobId, "RETRY_WOL", sec, "12s 尚未检测到回应，已自动补发一轮魔术包...")
            }
        }

        // Step 3: Actionable failure diagnosis at 65s
        val diag = if (lanSawHost) {
            "电脑局域网已通（主板已开机），但 Windows 桌面代理未上线 → 极可能正停在 Windows 锁屏密码页或系统更新，建议立刻点击下方「打开 RustDesk」远程输入开机密码！"
        } else {
            "局域网 65 秒内无任何响应 → 高度怀疑：① 主板 BIOS 未关闭 ErP/EuP 或未开启 PCIe 唤醒；② 电脑网线松动或断电；③ Windows 未关闭快速启动。"
        }
        pushProgress(cfg, jobId, "DIAGNOSIS_FAILED", elapsed(), "等待桌面代理上线超时", diag)
        pushStatus(cfg)
    }

    private fun callWindowsAgent(cfg: SentinelConfig, method: String, path: String, body: JSONObject?): JSONObject? {
        if (cfg.pcIp.isBlank()) return null
        return runCatching {
            val url = "http://${cfg.pcIp}:${cfg.agentPort}$path"
            val builder = Request.Builder().url(url)
            if (cfg.lanKey.isNotBlank()) builder.header("X-WakePilot-Key", cfg.lanKey)
            if (method == "POST") {
                val reqBody = (body?.toString() ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
                builder.post(reqBody)
            } else {
                builder.get()
            }
            RelayTransport.httpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) JSONObject().put("ok", true) else JSONObject(text)
            }
        }.getOrNull()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "WakePilot 家中哨兵", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wake_tile)
            .setContentTitle("WakePilot 家中哨兵")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
