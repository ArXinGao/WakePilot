package com.wakepilot.app.data

import android.content.Context
import android.net.Uri
import java.security.SecureRandom

enum class AppRole { NONE, SENTINEL, CONTROLLER }

data class SentinelConfig(
    val relayUrl: String,
    val sentinelId: String,
    val roomKeyB64: String,
    val edPubB64: String,
    val edPrivB64: String,
    val pcName: String,
    val pcMac: String,
    val pcIp: String,
    val broadcastIp: String,
    val agentPort: Int,
    val lanKey: String,
    val rustDeskId: String,
    val serviceEnabled: Boolean,
)

data class ControllerConfig(
    val relayUrl: String,
    val sentinelId: String,
    val roomKeyB64: String,
    val sentinelPubB64: String,
    val ctrlPubB64: String,
    val ctrlPrivB64: String,
    val pcName: String,
    val rustDeskId: String,
)

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("wakepilot_prefs", Context.MODE_PRIVATE)

    var role: AppRole
        get() = runCatching { AppRole.valueOf(prefs.getString("role", "NONE") ?: "NONE") }.getOrDefault(AppRole.NONE)
        set(value) { prefs.edit().putString("role", value.name).apply() }

    fun getOrInitSentinel(): SentinelConfig {
        var sid = prefs.getString("s_id", null)
        var roomKey = prefs.getString("s_room_key", null)
        var pub = prefs.getString("s_ed_pub", null)
        var priv = prefs.getString("s_ed_priv", null)

        if (sid.isNullOrBlank() || roomKey.isNullOrBlank() || pub.isNullOrBlank() || priv.isNullOrBlank()) {
            val rnd = ByteArray(6).also { SecureRandom().nextBytes(it) }
            sid = "sp_" + rnd.joinToString("") { "%02x".format(it) }
            roomKey = E2eeCrypto.toB64Url(E2eeCrypto.generateRoomKey())
            val kp = E2eeCrypto.generateEd25519KeyPair()
            pub = E2eeCrypto.toB64Url(kp.publicKey)
            priv = E2eeCrypto.toB64Url(kp.privateKey)
            prefs.edit()
                .putString("s_id", sid)
                .putString("s_room_key", roomKey)
                .putString("s_ed_pub", pub)
                .putString("s_ed_priv", priv)
                .apply()
        }

        return SentinelConfig(
            relayUrl = prefs.getString("s_relay", "http://YOUR_SERVER_IP:8443") ?: "http://YOUR_SERVER_IP:8443",
            sentinelId = sid,
            roomKeyB64 = roomKey,
            edPubB64 = pub,
            edPrivB64 = priv,
            pcName = prefs.getString("s_pc_name", "My-Windows-PC") ?: "My-Windows-PC",
            pcMac = prefs.getString("s_pc_mac", "") ?: "",
            pcIp = prefs.getString("s_pc_ip", "") ?: "",
            broadcastIp = prefs.getString("s_bcast", "255.255.255.255") ?: "255.255.255.255",
            agentPort = prefs.getInt("s_agent_port", 18766),
            lanKey = prefs.getString("s_lan_key", "") ?: "",
            rustDeskId = prefs.getString("s_rustdesk", "") ?: "",
            serviceEnabled = prefs.getBoolean("s_svc_enabled", false),
        )
    }

    fun saveSentinel(cfg: SentinelConfig) {
        prefs.edit()
            .putString("s_relay", cfg.relayUrl.trim().trimEnd('/'))
            .putString("s_id", cfg.sentinelId)
            .putString("s_room_key", cfg.roomKeyB64)
            .putString("s_ed_pub", cfg.edPubB64)
            .putString("s_ed_priv", cfg.edPrivB64)
            .putString("s_pc_name", cfg.pcName.trim())
            .putString("s_pc_mac", cfg.pcMac.trim().uppercase())
            .putString("s_pc_ip", cfg.pcIp.trim())
            .putString("s_bcast", cfg.broadcastIp.trim().ifBlank { "255.255.255.255" })
            .putInt("s_agent_port", cfg.agentPort)
            .putString("s_lan_key", cfg.lanKey.trim())
            .putString("s_rustdesk", cfg.rustDeskId.trim())
            .putBoolean("s_svc_enabled", cfg.serviceEnabled)
            .apply()
    }

    fun rotateSentinelKeys(): SentinelConfig {
        val cur = getOrInitSentinel()
        val kp = E2eeCrypto.generateEd25519KeyPair()
        val updated = cur.copy(
            roomKeyB64 = E2eeCrypto.toB64Url(E2eeCrypto.generateRoomKey()),
            edPubB64 = E2eeCrypto.toB64Url(kp.publicKey),
            edPrivB64 = E2eeCrypto.toB64Url(kp.privateKey),
        )
        saveSentinel(updated)
        return updated
    }

    fun buildPairingUri(cfg: SentinelConfig): String {
        return Uri.Builder()
            .scheme("wakepilot")
            .authority("pair")
            .appendQueryParameter("r", cfg.relayUrl.trim().trimEnd('/'))
            .appendQueryParameter("s", cfg.sentinelId)
            .appendQueryParameter("k", cfg.roomKeyB64)
            .appendQueryParameter("pub", cfg.edPubB64)
            .appendQueryParameter("name", cfg.pcName)
            .appendQueryParameter("rd", cfg.rustDeskId)
            .build()
            .toString()
    }

    fun getController(): ControllerConfig? {
        val relay = prefs.getString("c_relay", null) ?: return null
        val sid = prefs.getString("c_sid", null) ?: return null
        val key = prefs.getString("c_room_key", null) ?: return null
        val sPub = prefs.getString("c_sentinel_pub", "") ?: ""
        var cPub = prefs.getString("c_ed_pub", null)
        var cPriv = prefs.getString("c_ed_priv", null)
        if (cPub.isNullOrBlank() || cPriv.isNullOrBlank()) {
            val kp = E2eeCrypto.generateEd25519KeyPair()
            cPub = E2eeCrypto.toB64Url(kp.publicKey)
            cPriv = E2eeCrypto.toB64Url(kp.privateKey)
            prefs.edit().putString("c_ed_pub", cPub).putString("c_ed_priv", cPriv).apply()
        }
        return ControllerConfig(
            relayUrl = relay,
            sentinelId = sid,
            roomKeyB64 = key,
            sentinelPubB64 = sPub,
            ctrlPubB64 = cPub,
            ctrlPrivB64 = cPriv,
            pcName = prefs.getString("c_pc_name", "Windows-PC") ?: "Windows-PC",
            rustDeskId = prefs.getString("c_rustdesk", "") ?: "",
        )
    }

    fun importPairingUri(rawUri: String): Result<ControllerConfig> = runCatching {
        val uri = Uri.parse(rawUri.trim())
        require(uri.scheme == "wakepilot" && uri.host == "pair") { "无效的 WakePilot 配对二维码或链接" }
        val relay = requireNotNull(uri.getQueryParameter("r")).trim().trimEnd('/')
        val sid = requireNotNull(uri.getQueryParameter("s")).trim()
        val key = requireNotNull(uri.getQueryParameter("k")).trim()
        require(E2eeCrypto.fromB64Url(key).size == 32) { "配对密钥长度无效" }
        val sPub = uri.getQueryParameter("pub")?.trim().orEmpty()
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { "Windows-PC" }
        val rd = uri.getQueryParameter("rd")?.trim().orEmpty()

        val kp = E2eeCrypto.generateEd25519KeyPair()
        val cPub = E2eeCrypto.toB64Url(kp.publicKey)
        val cPriv = E2eeCrypto.toB64Url(kp.privateKey)

        prefs.edit()
            .putString("c_relay", relay)
            .putString("c_sid", sid)
            .putString("c_room_key", key)
            .putString("c_sentinel_pub", sPub)
            .putString("c_ed_pub", cPub)
            .putString("c_ed_priv", cPriv)
            .putString("c_pc_name", name)
            .putString("c_rustdesk", rd)
            .apply()

        ControllerConfig(relay, sid, key, sPub, cPub, cPriv, name, rd)
    }
}
