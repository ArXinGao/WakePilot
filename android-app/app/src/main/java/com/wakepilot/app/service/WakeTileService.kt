package com.wakepilot.app.service

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.wakepilot.app.data.ConfigStore
import com.wakepilot.app.data.E2eeCrypto
import com.wakepilot.app.net.RelayTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class WakeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val cfg = ConfigStore(this).getController()
        qsTile?.apply {
            state = if (cfg != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            label = cfg?.pcName?.let { "唤醒 $it" } ?: "远程开机"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val cfg = ConfigStore(this).getController()
        if (cfg == null) {
            Toast.makeText(this, "请先打开 WakePilot 绑定家中哨兵", Toast.LENGTH_SHORT).show()
            return
        }

        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "唤醒中..."
            updateTile()
        }

        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val res = runCatching {
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
                    msgId = "t_" + UUID.randomUUID().toString().take(8),
                    plaintextJson = cmd.toString(),
                    signingPrivKey = ctrlPriv,
                )
                RelayTransport.postOneShotEnvelope(cfg.relayUrl, cfg.sentinelId, channelToken, env).getOrThrow()
            }
            mainHandler.post {
                qsTile?.apply {
                    state = Tile.STATE_INACTIVE
                    label = if (res.isSuccess) "已发送开机 ✓" else "唤醒失败"
                    updateTile()
                }
                Toast.makeText(
                    this,
                    if (res.isSuccess) "已通过 E2EE 唤醒 ${cfg.pcName}！" else "失败: ${res.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }
}
