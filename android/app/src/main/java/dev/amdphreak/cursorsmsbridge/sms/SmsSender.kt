package dev.amdphreak.cursorsmsbridge.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat

object SmsSender {
    fun send(context: Context, to: String, body: String): Result<Unit> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(IllegalStateException("SEND_SMS permission missing"))
        }

        return runCatching {
            val manager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
            manager.sendTextMessage(to, null, body, null, null)
        }.onFailure {
            Toast.makeText(context, "Failed to send SMS: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
