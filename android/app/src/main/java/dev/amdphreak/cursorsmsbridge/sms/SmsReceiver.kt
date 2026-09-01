package dev.amdphreak.cursorsmsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.amdphreak.cursorsmsbridge.PhoneNumbers
import dev.amdphreak.cursorsmsbridge.SettingsRepository
import dev.amdphreak.cursorsmsbridge.service.BridgeService
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) {
            return
        }

        val settings = SettingsRepository(context).load()
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            return
        }

        val from = messages.first().displayOriginatingAddress ?: return
        if (!PhoneNumbers.isAllowed(from, settings.allowedNumbers, settings.forwardAll)) {
            return
        }

        val body = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }
        if (body.isBlank()) {
            return
        }

        val payload = Intent(context, BridgeService::class.java).apply {
            action = BridgeService.ACTION_INBOUND_SMS
            putExtra(BridgeService.EXTRA_SMS_ID, UUID.randomUUID().toString())
            putExtra(BridgeService.EXTRA_SMS_FROM, from)
            putExtra(BridgeService.EXTRA_SMS_BODY, body)
            putExtra(BridgeService.EXTRA_SMS_AT, System.currentTimeMillis())
        }
        context.startForegroundService(payload)
    }
}
