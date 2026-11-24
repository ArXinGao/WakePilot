package com.wakepilot.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object RelayTransport {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    fun toWebSocketUrl(baseUrl: String, role: String, sentinelId: String, channelToken: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> "ws://$trimmed"
        }
        return "$wsBase/ws?role=$role&id=$sentinelId&channel=$channelToken"
    }

    fun toHttpBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
            trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            else -> "http://$trimmed"
        }
    }

    /**
     * Stateless 1-shot E2EE HTTPS POST (used by Android Home Screen Widget & Quick Settings Tile).
     */
    fun postOneShotEnvelope(
        relayBaseUrl: String,
        sentinelId: String,
        channelToken: String,
        envelopeJson: String,
    ): Result<String> = runCatching {
        val url = "${toHttpBaseUrl(relayBaseUrl)}/api/v1/relay/$sentinelId"
        val req = Request.Builder()
            .url(url)
            .header("X-Channel-Token", channelToken)
            .post(envelopeJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $body")
            }
            body
        }
    }
}

class ReconnectingRelaySocket(
    private val wsUrlProvider: () -> String,
    private val onStatusChange: (connected: Boolean, info: String) -> Unit,
    private val onMessage: (text: String) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    @Volatile
    private var activeWs: WebSocket? = null
    private var backoffMs = 2000L

    fun start() {
        stopped.set(false)
        connectNow()
    }

    fun stop() {
        stopped.set(true)
        activeWs?.close(1000, "client stopped")
        activeWs = null
    }

    fun send(text: String): Boolean = activeWs?.send(text) == true

    private fun connectNow() {
        if (stopped.get()) return
        val url = wsUrlProvider()
        val req = Request.Builder().url(url).build()
        onStatusChange(false, "正在连接云端盲中继...")

        activeWs = RelayTransport.httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 2000L
                onStatusChange(true, "已安全连接盲中继 (E2EE)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatusChange(false, "连接断开: ${t.message ?: "网络异常"}，${backoffMs / 1000}s 后重连")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped.get()) {
                    onStatusChange(false, "连接已关闭 ($code)，准备重连")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        Thread {
            try {
                Thread.sleep(wait)
                connectNow()
            } catch (_: InterruptedException) {
            }
        }.start()
    }
}
