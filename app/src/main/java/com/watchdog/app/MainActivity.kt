package com.watchdog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var outputDir: File
    private var httpServer: VideoHttpServer? = null
    private lateinit var accessToken: String
    private var tokenEnabled: Boolean = true
    private lateinit var analysisExecutor: ExecutorService
    private val latestJpeg = AtomicReference<ByteArray?>(null)

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

        analysisExecutor = Executors.newSingleThreadExecutor()

        outputDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "WatchDog"
        ).apply { mkdirs() }

        accessToken = getOrCreateAccessToken()
        tokenEnabled = getTokenEnabled()

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.chkEnableToken.isChecked = tokenEnabled
        binding.chkEnableToken.setOnCheckedChangeListener { _, isChecked ->
            if (tokenEnabled == isChecked) {
                return@setOnCheckedChangeListener
            }
            tokenEnabled = isChecked
            setTokenEnabled(isChecked)
            restartHttpServer()
            updateServerUi()
        }

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
        analysisExecutor.shutdown()
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
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                    latestJpeg.set(imageProxyToJpeg(image))
                    image.close()
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        videoCapture,
                        imageAnalysis
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
        val fileName = "${timestamp()}.mp4"
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
        httpServer = VideoHttpServer(
            SERVER_PORT,
            outputDir,
            currentServerToken(),
            { getLatestVideoFile() },
            { latestJpeg.get() }
        )
        try {
            httpServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            binding.txtStatus.text = getString(R.string.server_running)
        } catch (exc: Exception) {
            binding.txtStatus.text = "Server error: ${exc.message}"
        }
    }

    private fun restartHttpServer() {
        httpServer?.stop()
        startHttpServer()
    }

    private fun updateServerUi() {
        val ip = getLocalIpAddress() ?: "unknown"
        val baseUrl = "http://$ip:$SERVER_PORT/"
        binding.txtServer.text = if (tokenEnabled) {
            "Server: ${baseUrl}?token=$accessToken"
        } else {
            "Server: $baseUrl"
        }
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

    private fun getTokenEnabled(): Boolean {
        val prefs = getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
        return prefs.getBoolean("token_enabled", true)
    }

    private fun setTokenEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("token_enabled", enabled).apply()
    }

    private fun currentServerToken(): String {
        return if (tokenEnabled) accessToken else ""
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        val ok = yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 70, out)
        return if (ok) out.toByteArray() else null
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        val width = image.width
        val height = image.height
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height + width * height / 2)

        var yPos = 0
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            if (yPixelStride == 1) {
                yBuffer.position(yRowStart)
                yBuffer.get(nv21, yPos, width)
                yPos += width
            } else {
                for (col in 0 until width) {
                    nv21[yPos++] = yBuffer.get(yRowStart + col * yPixelStride)
                }
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var uvPos = width * height
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                nv21[uvPos++] = vBuffer.get(vIndex)
                nv21[uvPos++] = uBuffer.get(uIndex)
            }
        }

        return nv21
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
