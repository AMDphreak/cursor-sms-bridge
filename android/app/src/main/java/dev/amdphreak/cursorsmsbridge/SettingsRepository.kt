package dev.amdphreak.cursorsmsbridge

import android.content.Context
import android.content.SharedPreferences

data class BridgeSettings(
    val host: String,
    val port: Int,
    val token: String,
    val allowedNumbers: Set<String>,
    val forwardAll: Boolean,
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): BridgeSettings {
        val allowedRaw = prefs.getString(KEY_ALLOWED, "") ?: ""
        val allowed = allowedRaw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return BridgeSettings(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 8787),
            token = prefs.getString(KEY_TOKEN, "") ?: "",
            allowedNumbers = allowed,
            forwardAll = prefs.getBoolean(KEY_FORWARD_ALL, allowed.isEmpty()),
        )
    }

    fun saveHostPortToken(host: String, port: Int, token: String) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun saveAllowedNumbers(numbers: Set<String>, forwardAll: Boolean) {
        prefs.edit()
            .putString(KEY_ALLOWED, numbers.joinToString(","))
            .putBoolean(KEY_FORWARD_ALL, forwardAll)
            .apply()
    }

    fun isConfigured(): Boolean {
        val settings = load()
        return settings.host.isNotBlank() && settings.token.isNotBlank()
    }

    companion object {
        private const val PREFS_NAME = "cursor_sms_bridge"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_ALLOWED = "allowed_numbers"
        private const val KEY_FORWARD_ALL = "forward_all"
    }
}
