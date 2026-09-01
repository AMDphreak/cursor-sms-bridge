package dev.amdphreak.cursorsmsbridge.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BridgeWebSocket(
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onConfig(allowedNumbers: Set<String>, forwardAll: Boolean)
        fun onSendSmsRequested(requestId: String, to: String, body: String)
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var host: String = ""
    private var port: Int = 8787
    private var token: String = ""

    fun connect(host: String, port: Int, token: String) {
        disconnect("Reconnecting")
        this.host = host
        this.port = port
        this.token = token

        val url = "ws://$host:$port/ws?token=${token.encodeURIComponent()}"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, socketListener)
    }

    fun disconnect(reason: String? = null) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.close(1000, reason ?: "Client disconnect")
        webSocket = null
    }

    fun sendAuth(deviceName: String) {
        send(
            JSONObject()
                .put("type", "auth")
                .put("token", token)
                .put("deviceName", deviceName),
        )
    }

    fun sendInboundSms(id: String, from: String, body: String, receivedAt: Long) {
        send(
            JSONObject()
                .put("type", "sms_in")
                .put("id", id)
                .put("from", from)
                .put("body", body)
                .put("receivedAt", receivedAt),
        )
    }

    fun sendOutboundAck(requestId: String, success: Boolean, error: String? = null) {
        send(
            JSONObject()
                .put("type", "sms_out_ack")
                .put("requestId", requestId)
                .put("success", success)
                .put("error", error),
        )
    }

    private fun send(payload: JSONObject) {
        val sent = webSocket?.send(payload.toString()) ?: false
        if (!sent) {
            listener.onError("WebSocket is not connected")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(25_000)
                send(
                    JSONObject()
                        .put("type", "ping")
                        .put("at", System.currentTimeMillis()),
                )
            }
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open")
            sendAuth(android.os.Build.MODEL)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            listener.onDisconnected(reason.ifBlank { "Closed ($code)" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeatJob?.cancel()
            listener.onDisconnected(t.message ?: "Connection failed")
        }
    }

    private fun handleMessage(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            listener.onError("Invalid JSON from desktop")
            return
        }
        when (json.optString("type")) {
            "auth_ok" -> {
                startHeartbeat()
                listener.onConnected()
            }
            "config" -> {
                val allowed = json.optJSONArray("allowedNumbers")
                    ?.let { array ->
                        buildSet {
                            for (index in 0 until array.length()) {
                                add(array.optString(index))
                            }
                        }
                    } ?: emptySet()
                listener.onConfig(allowed, json.optBoolean("forwardAll", allowed.isEmpty()))
            }
            "sms_out" -> {
                listener.onSendSmsRequested(
                    json.getString("requestId"),
                    json.getString("to"),
                    json.getString("body"),
                )
            }
            "error" -> listener.onError(json.optString("message", "Unknown error"))
        }
    }

    private fun String.encodeURIComponent(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
            .replace("+", "%20")

    companion object {
        private const val TAG = "BridgeWebSocket"
    }
}
