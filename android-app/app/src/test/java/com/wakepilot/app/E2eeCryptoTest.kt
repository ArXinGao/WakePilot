package com.wakepilot.app

import com.wakepilot.app.data.E2eeCrypto
import com.wakepilot.app.net.WolSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eeCryptoTest {

    @Test
    fun sealAndOpenWithEd25519AndReplayProtection() {
        val roomKey = E2eeCrypto.generateRoomKey()
        val kp = E2eeCrypto.generateEd25519KeyPair()
        val plain = """{"type":"WAKE","apps":["workbuddy","antigravity"],"auto_lock":true}"""

        val env = E2eeCrypto.seal(
            roomKey = roomKey,
            sentinelId = "sp_unit01",
            direction = E2eeCrypto.DIR_C2S,
            msgId = "m1",
            plaintextJson = plain,
            signingPrivKey = kp.privateKey,
        )

        val guard = E2eeCrypto.ReplayGuard()
        val opened = E2eeCrypto.open(
            roomKey = roomKey,
            expectedSentinelId = "sp_unit01",
            expectedDir = E2eeCrypto.DIR_C2S,
            envelopeJson = env,
            pinnedSenderPub = kp.publicKey,
            replayGuard = guard,
        )
        assertEquals(plain, opened.plaintext)

        // Replay must throw
        var replayFailed = false
        try {
            E2eeCrypto.open(roomKey, "sp_unit01", E2eeCrypto.DIR_C2S, env, kp.publicKey, guard)
        } catch (_: IllegalArgumentException) {
            replayFailed = true
        }
        assertTrue("Expected replay rejection", replayFailed)
    }

    @Test
    fun magicPacketExact102Bytes() {
        val pkt = WolSender.buildMagicPacket("AA-BB-CC-DD-EE-FF")
        assertEquals(102, pkt.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), pkt[i])
        assertEquals(0xAA.toByte(), pkt[6])
        assertEquals(0xFF.toByte(), pkt[101])
    }
}
