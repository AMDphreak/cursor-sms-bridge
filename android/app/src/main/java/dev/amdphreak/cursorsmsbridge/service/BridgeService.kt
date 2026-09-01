package dev.amdphreak.cursorsmsbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.amdphreak.cursorsmsbridge.MainActivity
import dev.amdphreak.cursorsmsbridge.R
import dev.amdphreak.cursorsmsbridge.SettingsRepository
import dev.amdphreak.cursorsmsbridge.network.BridgeWebSocket
import dev.amdphreak.cursorsmsbridge.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BridgeService : Service(), BridgeWebSocket.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private var webSocket: BridgeWebSocket? = null
    private var connected = false

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        connectFromSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INBOUND_SMS -> {
                val id = intent.getStringExtra(EXTRA_SMS_ID) ?: return START_STICKY
                val from = intent.getStringExtra(EXTRA_SMS_FROM) ?: return START_STICKY
                val body = intent.getStringExtra(EXTRA_SMS_BODY) ?: return START_STICKY
                val at = intent.getLongExtra(EXTRA_SMS_AT, System.currentTimeMillis())
                webSocket?.sendInboundSms(id, from, body, at)
            }
            ACTION_RECONNECT -> connectFromSettings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        webSocket?.disconnect("Service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConnected() {
        connected = true
        updateNotification("Connected to desktop relay")
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).putExtra(EXTRA_CONNECTED, true))
    }

    override fun onDisconnected(reason: String?) {
        connected = false
        updateNotification("Disconnected: ${reason ?: "unknown"}")
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .putExtra(EXTRA_CONNECTED, false)
                .putExtra(EXTRA_REASON, reason),
        )
    }

    override fun onConfig(allowedNumbers: Set<String>, forwardAll: Boolean) {
        settingsRepository.saveAllowedNumbers(allowedNumbers, forwardAll)
    }

    override fun onSendSmsRequested(requestId: String, to: String, body: String) {
        scope.launch {
            val result = SmsSender.send(this@BridgeService, to, body)
            webSocket?.sendOutboundAck(
                requestId = requestId,
                success = result.isSuccess,
                error = result.exceptionOrNull()?.message,
            )
        }
    }

    override fun onError(message: String) {
        updateNotification("Error: $message")
    }

    private fun connectFromSettings() {
        val settings = settingsRepository.load()
        if (settings.host.isBlank() || settings.token.isBlank()) {
            updateNotification("Not paired — open app to scan QR")
            return
        }

        if (webSocket == null) {
            webSocket = BridgeWebSocket(scope, this)
        }
        updateNotification("Connecting to ${settings.host}:${settings.port}")
        webSocket?.connect(settings.host, settings.port, settings.token)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SMS bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the desktop SMS relay connected"
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_INBOUND_SMS = "dev.amdphreak.cursorsmsbridge.INBOUND_SMS"
        const val ACTION_RECONNECT = "dev.amdphreak.cursorsmsbridge.RECONNECT"
        const val ACTION_STATUS_CHANGED = "dev.amdphreak.cursorsmsbridge.STATUS_CHANGED"
        const val EXTRA_SMS_ID = "sms_id"
        const val EXTRA_SMS_FROM = "sms_from"
        const val EXTRA_SMS_BODY = "sms_body"
        const val EXTRA_SMS_AT = "sms_at"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_REASON = "reason"

        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1001
    }
}
