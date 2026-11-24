package com.wakepilot.app.data

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashSet
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WakePilot v1 End-to-End Encryption (100% byte-for-byte compatible with Go wakepilot/shared/e2ee):
 *  - ChannelToken : hex(SHA-256("wakepilot-channel-v1:" || room_key))
 *  - Key Derive   : HKDF-SHA256(ikm=room_key, salt="wakepilot-e2ee-v1", info=direction)
 *  - Cipher       : AES-256-GCM (12-byte nonce, 128-bit tag, AAD="v|sid|dir|ts")
 *  - Signature    : RFC 8032 Ed25519 (BouncyCastle, works on Android API 26+)
 *  - Replay Guard : ±120s timestamp window + bounded seen-nonce set
 */
object E2eeCrypto {
    const val PROTOCOL_VERSION = 1
    const val DIR_C2S = "c2s"
    const val DIR_S2C = "s2c"

    private const val CHANNEL_PREFIX = "wakepilot-channel-v1:"
    private const val HKDF_SALT = "wakepilot-e2ee-v1"
    private const val REPLAY_WINDOW_SEC = 120L

    private val rng = SecureRandom()
    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()

    data class Ed25519KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

    fun generateRoomKey(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    fun generateEd25519KeyPair(): Ed25519KeyPair {
        val priv = Ed25519PrivateKeyParameters(rng)
        val pub = priv.generatePublicKey()
        return Ed25519KeyPair(pub.encoded, priv.encoded)
    }

    fun toB64Url(bytes: ByteArray): String = b64Enc.encodeToString(bytes)
    fun fromB64Url(str: String): ByteArray = b64Dec.decode(str.trim())

    fun channelToken(roomKey: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(CHANNEL_PREFIX.toByteArray(Charsets.UTF_8))
        md.update(roomKey)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun deriveDirectionalKey(roomKey: ByteArray, direction: String): ByteArray {
        require(roomKey.size == 32) { "room_key must be 32 bytes" }
        val info = when (direction) {
            DIR_C2S -> "controller->sentinel"
            DIR_S2C -> "sentinel->controller"
            else -> throw IllegalArgumentException("Invalid direction: $direction")
        }

        // HKDF-Extract
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(HKDF_SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val prk = extractMac.doFinal(roomKey)

        // HKDF-Expand (single 32-byte block)
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update(info.toByteArray(Charsets.UTF_8))
        expandMac.update(byteArrayOf(0x01))
        return expandMac.doFinal()
    }

    fun seal(
        roomKey: ByteArray,
        sentinelId: String,
        direction: String,
        msgId: String,
        plaintextJson: String,
        signingPrivKey: ByteArray? = null,
    ): String {
        val aesKey = deriveDirectionalKey(roomKey, direction)
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val ts = System.currentTimeMillis() / 1000L
        val aad = "$PROTOCOL_VERSION|$sentinelId|$direction|$ts".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))

        val nonceB64 = toB64Url(nonce)
        val ctB64 = toB64Url(ct)

        val env = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("id", msgId)
            put("sid", sentinelId)
            put("dir", direction)
            put("ts", ts)
            put("n", nonceB64)
            put("ct", ctB64)
        }

        if (signingPrivKey != null && signingPrivKey.size == 32) {
            val privParams = Ed25519PrivateKeyParameters(signingPrivKey, 0)
            val pubParams = privParams.generatePublicKey()
            val signData = "$PROTOCOL_VERSION|$sentinelId|$direction|$ts|$nonceB64|$ctB64".toByteArray(Charsets.UTF_8)
            val signer = Ed25519Signer()
            signer.init(true, privParams)
            signer.update(signData, 0, signData.size)
            val sig = signer.generateSignature()
            env.put("pub", toB64Url(pubParams.encoded))
            env.put("sig", toB64Url(sig))
        }
        return env.toString()
    }

    class ReplayGuard(private val maxEntries: Int = 512) {
        private val seen = LinkedHashSet<String>()

        @Synchronized
        fun accept(nonce: String): Boolean {
            if (seen.contains(nonce)) return false
            seen.add(nonce)
            if (seen.size > maxEntries) {
                val it = seen.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return true
        }
    }

    data class OpenedEnvelope(val id: String, val senderPubB64: String?, val plaintext: String)

    fun open(
        roomKey: ByteArray,
        expectedSentinelId: String,
        expectedDir: String,
        envelopeJson: String,
        pinnedSenderPub: ByteArray? = null,
        replayGuard: ReplayGuard? = null,
    ): OpenedEnvelope {
        val env = JSONObject(envelopeJson)
        val ver = env.optInt("v", -1)
        require(ver == PROTOCOL_VERSION) { "Unsupported version: $ver" }
        val sid = env.getString("sid")
        val dir = env.getString("dir")
        require(sid == expectedSentinelId && dir == expectedDir) { "Sentinel or direction mismatch" }

        val ts = env.getLong("ts")
        val now = System.currentTimeMillis() / 1000L
        require(kotlin.math.abs(now - ts) <= REPLAY_WINDOW_SEC) { "Timestamp skew outside ±120s" }

        val nonceB64 = env.getString("n")
        val ctB64 = env.getString("ct")
        val pubB64 = env.optString("pub", "").ifBlank { null }
        val sigB64 = env.optString("sig", "").ifBlank { null }

        val signData = "$ver|$sid|$dir|$ts|$nonceB64|$ctB64".toByteArray(Charsets.UTF_8)
        if (pinnedSenderPub != null) {
            require(!sigB64.isNullOrBlank()) { "Missing Ed25519 signature" }
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pinnedSenderPub, 0))
            verifier.update(signData, 0, signData.size)
            require(verifier.verifySignature(fromB64Url(sigB64))) { "Invalid pinned Ed25519 signature" }
        } else if (sigB64 != null && pubB64 != null) {
            val pubBytes = fromB64Url(pubB64)
            require(pubBytes.size == 32) { "Invalid Ed25519 public key length" }
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pubBytes, 0))
            verifier.update(signData, 0, signData.size)
            require(verifier.verifySignature(fromB64Url(sigB64))) { "Invalid Ed25519 signature" }
        }

        val aesKey = deriveDirectionalKey(roomKey, expectedDir)
        val nonce = fromB64Url(nonceB64)
        val ct = fromB64Url(ctB64)
        val aad = "$ver|$sid|$dir|$ts".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val plainBytes = cipher.doFinal(ct)

        if (replayGuard != null) {
            require(replayGuard.accept(nonceB64)) { "Replay detected for nonce" }
        }

        return OpenedEnvelope(
            id = env.optString("id", ""),
            senderPubB64 = pubB64,
            plaintext = String(plainBytes, Charsets.UTF_8),
        )
    }
}
