package com.wakepilot.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import com.wakepilot.app.R
import com.wakepilot.app.data.ConfigStore
import com.wakepilot.app.data.E2eeCrypto
import com.wakepilot.app.net.RelayTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class WakeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_WAKE = "com.wakepilot.app.ACTION_WIDGET_WAKE"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val cfg = ConfigStore(context).getController()
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_wake)
            views.setTextViewText(R.id.widget_title, cfg?.pcName?.let { "唤醒 $it" } ?: "一键远程开机")
            views.setTextViewText(R.id.widget_status, if (cfg != null) "点击通过 E2EE 唤醒" else "请先在 App 扫码绑定")

            val intent = Intent(context, WakeWidgetProvider::class.java).apply { action = ACTION_WIDGET_WAKE }
            val pi = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_WIDGET_WAKE) return

        val mainHandler = Handler(Looper.getMainLooper())
        val cfg = ConfigStore(context).getController()
        if (cfg == null) {
            Toast.makeText(context, "请先打开 WakePilot 扫码绑定家中哨兵", Toast.LENGTH_SHORT).show()
            return
        }

        updateAllWidgetsStatus(context, "正在加密发送开机指令...")
        Thread {
            val result = runCatching {
                val roomKey = E2eeCrypto.fromB64Url(cfg.roomKeyB64)
                val ctrlPriv = E2eeCrypto.fromB64Url(cfg.ctrlPrivB64)
                val channelToken = E2eeCrypto.channelToken(roomKey)
                val cmd = JSONObject().apply {
                    put("type", "WAKE")
                    put("apps", JSONArray(listOf("workbuddy", "doubao", "antigravity")))
                    put("auto_lock", true)
                }
                val env = E2eeCrypto.seal(
                    roomKey = roomKey,
                    sentinelId = cfg.sentinelId,
                    direction = E2eeCrypto.DIR_C2S,
                    msgId = "w_" + UUID.randomUUID().toString().take(8),
                    plaintextJson = cmd.toString(),
                    signingPrivKey = ctrlPriv,
                )
                RelayTransport.postOneShotEnvelope(cfg.relayUrl, cfg.sentinelId, channelToken, env).getOrThrow()
            }
            mainHandler.post {
                if (result.isSuccess) {
                    updateAllWidgetsStatus(context, "开机指令已送达哨兵 ✓")
                    Toast.makeText(context, "WakePilot：已通过 E2EE 发送开机指令！", Toast.LENGTH_SHORT).show()
                } else {
                    updateAllWidgetsStatus(context, "发送失败（哨兵离线？）")
                    Toast.makeText(context, "唤醒失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun updateAllWidgetsStatus(context: Context, status: String) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, WakeWidgetProvider::class.java))
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_wake)
            views.setTextViewText(R.id.widget_status, status)
            mgr.partiallyUpdateAppWidget(id, views)
        }
    }
}
