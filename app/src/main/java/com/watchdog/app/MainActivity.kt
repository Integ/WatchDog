package com.watchdog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.watchdog.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val ENCODE_WIDTH = 1280
        private const val ENCODE_HEIGHT = 720
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityMainBinding
    private var webRtcClient: WebRtcClient? = null
    private lateinit var analysisExecutor: ExecutorService
    
    private var isConnected = false

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            onPermissionsGranted()
        } else {
            binding.txtStatus.text = "Camera or microphone permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.btnCall.setOnClickListener { toggleCall() }

        if (allPermissionsGranted()) {
            onPermissionsGranted()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun onPermissionsGranted() {
        initWebRtc()
        startCamera()
        binding.txtStatus.text = "Ready to connect"
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcClient?.close()
        analysisExecutor.shutdown()
    }

    private fun initWebRtc() {
        webRtcClient = WebRtcClient(this) { status ->
            binding.txtStatus.text = status
        }.apply {
            initialize()
        }
        Log.i(TAG, "WebRTC initialized")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(ENCODE_WIDTH, ENCODE_HEIGHT), ResolutionStrategy.FALLBACK_RULE_NONE)
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                if (isConnected) {
                    webRtcClient?.pushFrame(image)
                }
                image.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                binding.txtStatus.text = "Failed to start camera: ${exc.message}"
                Log.e(TAG, "Camera bind failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleCall() {
        if (!isConnected) {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                binding.txtStatus.text = "Please enter a Server URL"
                return
            }
            isConnected = true
            binding.btnCall.text = getString(R.string.hang_up)
            webRtcClient?.connect(url)
        } else {
            isConnected = false
            binding.btnCall.text = getString(R.string.call)
            webRtcClient?.disconnect()
            binding.txtStatus.text = "Disconnected"
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
