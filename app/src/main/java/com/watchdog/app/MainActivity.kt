package com.watchdog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.watchdog.app.databinding.ActivityMainBinding
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var outputDir: File
    private var httpServer: VideoHttpServer? = null
    private lateinit var accessToken: String

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            startCamera()
        } else {
            binding.txtStatus.text = "Camera or microphone permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        outputDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "WatchDog"
        ).apply { mkdirs() }

        accessToken = getOrCreateAccessToken()

        binding.btnRecord.setOnClickListener { toggleRecording() }

        startHttpServer()
        updateServerUi()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        httpServer?.stop()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val qualitySelector = QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )

                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()

                videoCapture = VideoCapture.withOutput(recorder)

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        videoCapture
                    )
                } catch (exc: Exception) {
                    binding.txtStatus.text = "Failed to start camera: ${exc.message}"
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun toggleRecording() {
        if (recording == null) {
            startRecording()
        } else {
            stopRecording()
        }
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return
        val fileName = "ipcam_${timestamp()}.mp4"
        val outputFile = File(outputDir, fileName)
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val pendingRecording = videoCapture.output.prepareRecording(this, outputOptions).apply {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                withAudioEnabled()
            }
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.btnRecord.text = getString(R.string.stop_recording)
                    binding.txtStatus.text = "Recording..."
                }

                is VideoRecordEvent.Finalize -> {
                    recording = null
                    binding.btnRecord.text = getString(R.string.start_recording)
                    if (event.hasError()) {
                        binding.txtStatus.text = "Recording error: ${event.error}"
                    } else {
                        binding.txtStatus.text = "Saved: ${outputFile.name}"
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        binding.btnRecord.text = getString(R.string.start_recording)
        binding.txtStatus.text = "Stopping..."
    }

    private fun startHttpServer() {
        httpServer = VideoHttpServer(SERVER_PORT, outputDir, accessToken) { getLatestVideoFile() }
        try {
            httpServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            binding.txtStatus.text = getString(R.string.server_running)
        } catch (exc: Exception) {
            binding.txtStatus.text = "Server error: ${exc.message}"
        }
    }

    private fun updateServerUi() {
        val ip = getLocalIpAddress() ?: "unknown"
        binding.txtServer.text = "Server: http://$ip:$SERVER_PORT/?token=$accessToken"
    }

    private fun getLatestVideoFile(): File? {
        val files = outputDir.listFiles()?.filter { it.isFile && it.extension == "mp4" }
        return files?.maxByOrNull { it.lastModified() }
    }

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return formatter.format(Date())
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (exc: Exception) {
            null
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getOrCreateAccessToken(): String {
        val prefs = getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
        val existing = prefs.getString("access_token", null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val token = generateAccessToken()
        prefs.edit().putString("access_token", token).apply()
        return token
    }

    private fun generateAccessToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    companion object {
        private const val SERVER_PORT = 8080
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
