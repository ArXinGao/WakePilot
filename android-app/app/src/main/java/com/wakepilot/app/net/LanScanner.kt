package com.wakepilot.app.net

import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class DiscoveredHost(
    val pcName: String,
    val ip: String,
    val mac: String,
    val broadcastIp: String,
    val agentPort: Int = 18766,
    val rustDeskId: String = "",
    val source: String,
)

object LanScanner {
    private const val DISCOVER_PORT = 18767
    private const val DISCOVER_MAGIC = "WAKEPILOT_DISCOVER_V1"

    fun localSubnetBroadcast(): String {
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val ip = ia.address
                    val bcast = ia.broadcast
                    if (ip is Inet4Address && bcast != null && !ip.isLoopbackAddress) {
                        return bcast.hostAddress ?: "255.255.255.255"
                    }
                }
            }
        }
        return "255.255.255.255"
    }

    /**
     * 1) Broadcasts WAKEPILOT_DISCOVER_V1 on UDP 18767 for instant auto-discovery of wakepilot-agent.exe
     * 2) Supplements with /proc/net/arp table entries so even PCs without the agent appear.
     */
    fun scanLan(): List<DiscoveredHost> {
        val bcast = localSubnetBroadcast()
        val foundByMac = linkedMapOf<String, DiscoveredHost>()

        // Step 1: Active UDP broadcast discovery against WakePilot Windows Agent
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 450
                val payload = DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
                for (target in linkedSetOf(bcast, "255.255.255.255")) {
                    val addr = InetAddress.getByName(target)
                    sock.send(DatagramPacket(payload, payload.size, addr, DISCOVER_PORT))
                }
                val deadline = System.currentTimeMillis() + 1400L
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val rx = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(rx)
                        val text = String(rx.data, 0, rx.length, Charsets.UTF_8)
                        val obj = JSONObject(text)
                        val mac = obj.optString("mac", "").uppercase()
                        val ip = obj.optString("lan_ip", rx.address.hostAddress ?: "")
                        if (mac.length >= 12) {
                            foundByMac[mac] = DiscoveredHost(
                                pcName = obj.optString("pc_name", "Windows-PC"),
                                ip = ip,
                                mac = mac,
                                broadcastIp = bcast,
                                agentPort = obj.optInt("agent_port", 18766),
                                rustDeskId = obj.optString("rustdesk_id", ""),
                                source = "WakePilot Agent",
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
            }
        }

        // Step 2: Read /proc/net/arp fallback (works on Android 8-10 and many OEM ROMs)
        runCatching {
            val arpFile = File("/proc/net/arp")
            if (arpFile.canRead()) {
                arpFile.readLines().drop(1).forEach { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size >= 4) {
                        val ip = cols[0]
                        val mac = cols[3].uppercase()
                        if (mac != "00:00:00:00:00:00" && mac.length == 17 && !foundByMac.containsKey(mac)) {
                            foundByMac[mac] = DiscoveredHost(
                                pcName = "LAN 设备 ($ip)",
                                ip = ip,
                                mac = mac,
                                broadcastIp = bcast,
                                source = "ARP 缓存",
                            )
                        }
                    }
                }
            }
        }

        return foundByMac.values.toList()
    }

    fun isHostReachable(ip: String, timeoutMs: Int = 1200): Boolean {
        if (ip.isBlank()) return false
        return runCatching { InetAddress.getByName(ip).isReachable(timeoutMs) }.getOrDefault(false)
    }
}
