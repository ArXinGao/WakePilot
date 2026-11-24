package com.wakepilot.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WolSender {
    private val macRegex = Regex("^[0-9A-Fa-f]{12}$")

    fun normalizeMac(mac: String): String {
        val clean = mac.replace(Regex("[:\\-.\\s]"), "").uppercase()
        require(macRegex.matches(clean)) { "无效 MAC 地址: $mac" }
        return clean.chunked(2).joinToString(":")
    }

    fun buildMagicPacket(mac: String): ByteArray {
        val clean = normalizeMac(mac).replace(":", "")
        val macBytes = ByteArray(6) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val pkt = ByteArray(102)
        for (i in 0 until 6) pkt[i] = 0xFF.toByte()
        for (rep in 0 until 16) {
            System.arraycopy(macBytes, 0, pkt, 6 + rep * 6, 6)
        }
        return pkt
    }

    /**
     * Multi-target WoL burst: global broadcast + subnet broadcast + optional unicast IP on UDP 9 & 7.
     */
    fun sendBurst(
        mac: String,
        broadcastIp: String,
        unicastIp: String = "",
        burstCount: Int = 3,
    ): Int {
        val pktBytes = buildMagicPacket(mac)
        val targets = linkedSetOf("255.255.255.255")
        if (broadcastIp.isNotBlank()) targets.add(broadcastIp.trim())
        if (unicastIp.isNotBlank()) targets.add(unicastIp.trim())

        var sent = 0
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(burstCount.coerceAtLeast(1)) { idx ->
                for (host in targets) {
                    val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: continue
                    for (port in intArrayOf(9, 7)) {
                        runCatching {
                            socket.send(DatagramPacket(pktBytes, pktBytes.size, addr, port))
                            sent++
                        }
                    }
                }
                if (idx + 1 < burstCount) Thread.sleep(150)
            }
        }
        return sent
    }
}
