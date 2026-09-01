package dev.amdphreak.cursorsmsbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import dev.amdphreak.cursorsmsbridge.databinding.ActivityPairingBinding
import java.util.concurrent.Executors

class PairingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPairingBinding
    private lateinit var settingsRepository: SettingsRepository
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var handled = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsRepository = SettingsRepository(this)

        binding.manualPairButton.setOnClickListener { pairFromManualInput() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    private fun pairFromManualInput() {
        val raw = binding.manualPayloadInput.text?.toString().orEmpty()
        val parsed = MainActivity.parsePairingPayload(raw)
        if (parsed == null) {
            Toast.makeText(this, R.string.invalid_pairing_payload, Toast.LENGTH_LONG).show()
            return
        }
        applyPairing(parsed.first, parsed.second, parsed.third)
    }

    private fun applyPairing(host: String, port: Int, token: String) {
        settingsRepository.saveHostPortToken(host, port, token)
        Toast.makeText(this, getString(R.string.paired_success, host, port), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val scanner = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                if (handled) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees,
                )
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (raw != null) {
                            val parsed = MainActivity.parsePairingPayload(raw)
                            if (parsed != null) {
                                handled = true
                                runOnUiThread {
                                    applyPairing(parsed.first, parsed.second, parsed.third)
                                }
                            }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }
}
