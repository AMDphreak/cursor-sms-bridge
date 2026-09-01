package dev.amdphreak.cursorsmsbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.amdphreak.cursorsmsbridge.databinding.ActivityMainBinding
import dev.amdphreak.cursorsmsbridge.service.BridgeService
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val denied = results.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            startBridgeService()
        } else {
            Toast.makeText(this, "SMS permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private val pairingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        renderStatus()
        ensurePermissionsAndStart()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BridgeService.ACTION_STATUS_CHANGED) {
                renderStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsRepository = SettingsRepository(this)

        binding.scanButton.setOnClickListener {
            pairingLauncher.launch(Intent(this, PairingActivity::class.java))
        }
        binding.saveAllowlistButton.setOnClickListener { saveAllowlist() }
        binding.reconnectButton.setOnClickListener {
            startService(
                Intent(this, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_RECONNECT
                },
            )
        }

        handleDeepLink(intent)
        renderStatus()
        ensurePermissionsAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        renderStatus()
        ensurePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BridgeService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "cursorsms" || data.host != "pair") {
            return
        }
        val host = data.getQueryParameter("host") ?: return
        val port = data.getQueryParameter("port")?.toIntOrNull() ?: 8787
        val token = data.getQueryParameter("token") ?: return
        settingsRepository.saveHostPortToken(host, port, token)
        Toast.makeText(this, "Paired with $host:$port", Toast.LENGTH_SHORT).show()
    }

    private fun ensurePermissionsAndStart() {
        val required = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBridgeService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBridgeService() {
        if (!settingsRepository.isConfigured()) {
            binding.statusText.text = getString(R.string.status_not_paired)
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, BridgeService::class.java),
        )
    }

    private fun saveAllowlist() {
        val raw = binding.allowlistInput.text?.toString().orEmpty()
        val numbers = raw.split(",")
            .map { PhoneNumbers.normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val forwardAll = numbers.isEmpty()
        settingsRepository.saveAllowedNumbers(numbers, forwardAll)
        Toast.makeText(this, R.string.allowlist_saved, Toast.LENGTH_SHORT).show()
        startService(
            Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_RECONNECT
            },
        )
    }

    private fun renderStatus() {
        val settings = settingsRepository.load()
        binding.hostText.text = if (settings.host.isBlank()) {
            getString(R.string.status_not_paired)
        } else {
            "${settings.host}:${settings.port}"
        }
        binding.allowlistInput.setText(settings.allowedNumbers.joinToString(", "))
        binding.forwardHint.text = if (settings.forwardAll) {
            getString(R.string.forward_all_hint)
        } else {
            getString(R.string.forward_filtered_hint)
        }
    }

    companion object {
        fun parsePairingPayload(raw: String): Triple<String, Int, String>? {
            return runCatching {
                val json = JSONObject(raw.trim())
                Triple(
                    json.getString("host"),
                    json.optInt("port", 8787),
                    json.getString("token"),
                )
            }.getOrNull()
    }
}
