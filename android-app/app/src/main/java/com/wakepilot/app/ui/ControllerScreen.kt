package com.wakepilot.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakepilot.app.data.ConfigStore
import com.wakepilot.app.data.ControllerConfig
import com.wakepilot.app.data.E2eeCrypto
import com.wakepilot.app.net.ReconnectingRelaySocket
import com.wakepilot.app.net.RelayTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun ControllerScreen(
    store: ConfigStore,
    scannedPairUri: String?,
    onConsumeScannedUri: () -> Unit,
    onRequestScanQr: () -> Unit,
    onSwitchRole: () -> Unit,
) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(store.getController()) }
    var manualUri by remember { mutableStateOf("") }
    var showPairBox by remember { mutableStateOf(cfg == null) }

    var relayConnected by remember { mutableStateOf(false) }
    var sentinelOnline by remember { mutableStateOf(false) }
    var pcOnline by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf("等待连接盲中继...") }
    var diagnosisText by remember { mutableStateOf("") }
    val timeline = remember { mutableStateListOf<String>() }

    var selWorkBuddy by remember { mutableStateOf(true) }
    var selDoubao by remember { mutableStateOf(true) }
    var selAntigravity by remember { mutableStateOf(true) }
    var autoLock by remember { mutableStateOf(true) }

    val replayGuard = remember { E2eeCrypto.ReplayGuard() }
    var socketRef by remember { mutableStateOf<ReconnectingRelaySocket?>(null) }

    // Handle QR scan callback
    if (!scannedPairUri.isNullOrBlank()) {
        val res = store.importPairingUri(scannedPairUri)
        onConsumeScannedUri()
        res.onSuccess {
            cfg = it
            showPairBox = false
            Toast.makeText(ctx, "E2EE 扫码绑定成功：${it.pcName}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(ctx, "二维码无效: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun sendCommand(activeCfg: ControllerConfig, payload: JSONObject) {
        val roomKey = E2eeCrypto.fromB64Url(activeCfg.roomKeyB64)
        val ctrlPriv = E2eeCrypto.fromB64Url(activeCfg.ctrlPrivB64)
        val env = E2eeCrypto.seal(
            roomKey = roomKey,
            sentinelId = activeCfg.sentinelId,
            direction = E2eeCrypto.DIR_C2S,
            msgId = "c_" + UUID.randomUUID().toString().take(8),
            plaintextJson = payload.toString(),
            signingPrivKey = ctrlPriv,
        )
        val sent = socketRef?.send(env) == true
        if (!sent) {
            // Fallback to one-shot HTTPS POST if WebSocket is reconnecting
            Thread {
                RelayTransport.postOneShotEnvelope(
                    activeCfg.relayUrl,
                    activeCfg.sentinelId,
                    E2eeCrypto.channelToken(roomKey),
                    env,
                )
            }.start()
        }
    }

    DisposableEffect(cfg) {
        val active = cfg
        if (active != null) {
            val roomKey = E2eeCrypto.fromB64Url(active.roomKeyB64)
            val channelTok = E2eeCrypto.channelToken(roomKey)
            val pinnedSentinelPub = active.sentinelPubB64.takeIf { it.isNotBlank() }?.let { E2eeCrypto.fromB64Url(it) }
            val sock = ReconnectingRelaySocket(
                wsUrlProvider = { RelayTransport.toWebSocketUrl(active.relayUrl, "controller", active.sentinelId, channelTok) },
                onStatusChange = { ok, msg ->
                    relayConnected = ok
                    statusBanner = msg
                    if (ok) {
                        sendCommand(active, JSONObject().put("type", "PING"))
                    }
                },
                onMessage = { raw ->
                    runCatching {
                        val j = JSONObject(raw)
                        if (j.optString("relay_event") == "sentinel_presence") {
                            sentinelOnline = j.optBoolean("sentinel_online", false)
                            if (sentinelOnline) {
                                sendCommand(active, JSONObject().put("type", "PING"))
                            }
                            return@runCatching
                        }
                        val opened = E2eeCrypto.open(roomKey, active.sentinelId, E2eeCrypto.DIR_S2C, raw, pinnedSentinelPub, replayGuard)
                        val body = JSONObject(opened.plaintext)
                        when (body.optString("type")) {
                            "STATUS" -> {
                                sentinelOnline = body.optBoolean("sentinel_online", true)
                                pcOnline = body.optBoolean("agent_online", false) || body.optBoolean("pc_reachable", false)
                            }
                            "PROGRESS" -> {
                                val sec = body.optInt("elapsed_sec", 0)
                                val stage = body.optString("stage")
                                val msg = body.optString("message")
                                timeline.add("[${sec}s] $msg")
                                val diag = body.optString("diagnosis", "")
                                if (diag.isNotBlank()) diagnosisText = diag
                                if (stage in listOf("AGENT_ONLINE", "APPS_LAUNCHED", "SCREEN_LOCKED", "DONE")) {
                                    pcOnline = true
                                }
                            }
                            "ACK" -> {
                                timeline.add("✓ " + body.optString("message"))
                            }
                        }
                    }
                },
            )
            socketRef = sock
            sock.start()
            onDispose {
                sock.stop()
                socketRef = null
            }
        } else {
            onDispose { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(cfg?.pcName ?: "随身遥控器", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    when {
                        cfg == null -> "未绑定家中哨兵"
                        !relayConnected -> "○ $statusBanner"
                        !sentinelOnline -> "● 中继已连 · 家中哨兵离线"
                        pcOnline -> "● 家中哨兵在线 · 电脑已开机"
                        else -> "● 家中哨兵在线 · 电脑关机待唤醒"
                    },
                    fontSize = 12.sp,
                    color = when {
                        pcOnline -> StatusGreen
                        sentinelOnline -> AccentBlue
                        else -> StatusAmber
                    },
                )
            }
            Row {
                TextButton(onClick = { showPairBox = !showPairBox }) { Text("绑定", color = AccentPurple) }
                TextButton(onClick = onSwitchRole) { Text("切换", color = TextMuted) }
            }
        }

        if (showPairBox || cfg == null) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = BorderStroke(1.dp, AccentPurple),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("绑定家中备用手机哨兵", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        onClick = onRequestScanQr,
                    ) { Text("扫描哨兵二维码（推荐）", color = Color(0xFF020617), fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualUri,
                        onValueChange = { manualUri = it },
                        label = { Text("或粘贴 wakepilot://pair?... 链接") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            store.importPairingUri(manualUri).onSuccess {
                                cfg = it
                                showPairBox = false
                                manualUri = ""
                                Toast.makeText(ctx, "绑定成功：${it.pcName}", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(ctx, "解析失败: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                    ) { Text("通过链接绑定") }
                }
            }
        }

        val activeCfg = cfg
        if (activeCfg != null) {
            Spacer(Modifier.height(12.dp))

            // Primary Wake & AI Launch Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = BorderStroke(1.dp, BorderDark),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("开机后自动拉起的 AI 客户端", fontSize = 13.sp, color = TextMuted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = selWorkBuddy, onClick = { selWorkBuddy = !selWorkBuddy }, label = { Text("WorkBuddy") })
                        FilterChip(selected = selDoubao, onClick = { selDoubao = !selDoubao }, label = { Text("豆包 PC") })
                        FilterChip(selected = selAntigravity, onClick = { selAntigravity = !selAntigravity }, label = { Text("Antigravity") })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("拉起后 3 秒自动锁屏（防桌面隐私外泄）", fontSize = 12.sp, color = TextMuted)
                        Switch(checked = autoLock, onCheckedChange = { autoLock = it })
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        onClick = {
                            timeline.clear()
                            diagnosisText = ""
                            val apps = buildList {
                                if (selWorkBuddy) add("workbuddy")
                                if (selDoubao) add("doubao")
                                if (selAntigravity) add("antigravity")
                            }
                            timeline.add("[0s] 已通过 E2EE 发送开机指令...")
                            sendCommand(activeCfg, JSONObject().apply {
                                put("type", "WAKE")
                                put("apps", JSONArray(apps))
                                put("auto_lock", autoLock)
                            })
                        },
                    ) {
                        Text("一键远程开机 + 启动 AI", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF020617))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            timeline.clear()
                            diagnosisText = ""
                            timeline.add("[0s] 发送纯开机指令（适合去 RustDesk 输开机密码）...")
                            sendCommand(activeCfg, JSONObject().apply {
                                put("type", "WAKE")
                                put("apps", JSONArray())
                                put("auto_lock", false)
                            })
                        },
                    ) { Text("仅远程开机（随后打开 RustDesk 输密码）", fontSize = 13.sp) }
                }
            }

            if (timeline.isNotEmpty() || diagnosisText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                    border = BorderStroke(1.dp, if (diagnosisText.isNotBlank()) StatusRed else BorderDark),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("实时启动进度与诊断流水线", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            timeline.joinToString("\n"),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 18.sp,
                        )
                        if (diagnosisText.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("诊断结论：$diagnosisText", fontSize = 12.sp, color = StatusAmber, lineHeight = 18.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Quick Remote Control Handoff Card (RustDesk + Antigravity)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = BorderStroke(1.dp, BorderDark),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("远程接管与锁屏输密码", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        onClick = { launchRustDesk(ctx, activeCfg.rustDeskId) },
                    ) {
                        Text(
                            if (activeCfg.rustDeskId.isNotBlank()) "打开 RustDesk 输开机密码（已自动复制 ${activeCfg.rustDeskId}）" else "打开 RustDesk 远程桌面",
                            color = Color(0xFF090D16),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://antigravity.google.com")))
                        },
                    ) { Text("打开 Antigravity 手机远程控制台", fontSize = 13.sp) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Safe Power Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = BorderStroke(1.dp, BorderDark),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("安全电源管理（电脑在线时生效）", fontSize = 13.sp, color = TextMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { sendCommand(activeCfg, JSONObject().put("type", "POWER").put("action", "lock")) },
                        ) { Text("立即锁屏", fontSize = 12.sp) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { sendCommand(activeCfg, JSONObject().put("type", "POWER").put("action", "sleep")) },
                        ) { Text("S3 睡眠", fontSize = 12.sp, color = StatusAmber) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { sendCommand(activeCfg, JSONObject().put("type", "POWER").put("action", "shutdown")) },
                        ) { Text("安全关机", fontSize = 12.sp, color = StatusRed) }
                    }
                }
            }
        }
    }
}

private fun launchRustDesk(ctx: Context, rustDeskId: String) {
    if (rustDeskId.isNotBlank()) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("RustDesk ID", rustDeskId))
        Toast.makeText(ctx, "已复制 $rustDeskId，正在打开 RustDesk...", Toast.LENGTH_SHORT).show()
    }
    val launch = ctx.packageManager.getLaunchIntentForPackage("com.carriez.flutter_hbb")
    if (launch != null) {
        ctx.startActivity(launch)
    } else {
        Toast.makeText(ctx, "未检测到 RustDesk App，正在打开官网下载页", Toast.LENGTH_LONG).show()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rustdesk.com")))
    }
}
