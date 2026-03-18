package com.watchdog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import org.webrtc.PeerConnection
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val HTTP_PORT = 8080
        private const val ENCODE_WIDTH = 1280
        private const val ENCODE_HEIGHT = 720
        private const val SEGMENT_DURATION_MS = 30L * 60 * 1000 // 30 minutes
        private const val MAX_VIDEO_FILES = 5
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityMainBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var outputDir: File
    private lateinit var snapshotDir: File
    private var httpServer: VideoHttpServer? = null
    private var webRtcService: WebRtcService? = null
    private lateinit var accessToken: String
    private var tokenEnabled: Boolean = false
    private lateinit var analysisExecutor: ExecutorService
    private val segmentHandler = Handler(Looper.getMainLooper())
    private var isSegmenting = false
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private var currentImageProxy: ImageProxy? = null

    private val autoRotateRunnable = Runnable {
        if (isSegmenting) {
            rotateRecordingSegment()
        }
    }

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

        snapshotDir = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
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
            restartServers()
            updateServerUi()
        }

        startServers()
        updateServerUi()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isSegmenting = false
        segmentHandler.removeCallbacks(autoRotateRunnable)
        snapshotHandler.removeCallbacksAndMessages(null)
        recording?.stop()
        httpServer?.stop()
        webRtcService?.close()
        currentImageProxy?.close()
        analysisExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                // --- WebRTC setup ---
                webRtcService = WebRtcService(this)
                webRtcService?.initialize()
                webRtcService?.startCapture(ENCODE_WIDTH, ENCODE_HEIGHT, 30)
                webRtcService?.createPeerConnection()

                // --- Preview for UI display ---
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                // --- Video recording ---
                val qualitySelector = QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                // --- ImageAnalysis → store latest frame for snapshot ---
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(ENCODE_WIDTH, ENCODE_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                    // Store latest image for snapshot
                    currentImageProxy?.close()
                    currentImageProxy = image
                }

                // Start snapshot timer (every 10 seconds)
                snapshotHandler.post(object : Runnable {
                    override fun run() {
                        captureSnapshot()
                        snapshotHandler.postDelayed(this, 10_000)
                    }
                })

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
                    Log.e(TAG, "Camera bind failed", exc)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    // ---- Recording (auto-segmented, 30-min per file, max 5 files) ----

    private fun toggleRecording() {
        if (!isSegmenting) {
            isSegmenting = true
            startRecordingSegment()
        } else {
            stopRecordingCompletely()
        }
    }

    private fun startRecordingSegment() {
        val videoCapture = this.videoCapture ?: return
        enforceMaxFiles()
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
                    if (event.hasError()) {
                        binding.txtStatus.text = "Recording error: ${event.error}"
                    } else {
                        binding.txtStatus.text = "Saved: ${outputFile.name}"
                        Log.i(TAG, "Segment saved: ${outputFile.name}")
                    }
                    if (isSegmenting) {
                        startRecordingSegment()
                    } else {
                        binding.btnRecord.text = getString(R.string.start_recording)
                    }
                }
            }
        }

        segmentHandler.removeCallbacks(autoRotateRunnable)
        segmentHandler.postDelayed(autoRotateRunnable, SEGMENT_DURATION_MS)
    }

    private fun rotateRecordingSegment() {
        Log.i(TAG, "Auto-rotating recording segment")
        recording?.stop()
    }

    private fun stopRecordingCompletely() {
        isSegmenting = false
        segmentHandler.removeCallbacks(autoRotateRunnable)
        recording?.stop()
        recording = null
        binding.btnRecord.text = getString(R.string.start_recording)
        binding.txtStatus.text = "Stopping..."
    }

    private fun enforceMaxFiles() {
        val files = outputDir.listFiles()?.filter { it.isFile && it.extension == "mp4" }
            ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        while (files.size >= MAX_VIDEO_FILES) {
            val oldest = files.removeFirst()
            Log.i(TAG, "Deleting oldest recording: ${oldest.name}")
            oldest.delete()
        }
    }

    // ---- Servers ----

    private fun startServers() {
        webRtcService = WebRtcService(this).apply {
            initialize()
            startCapture(ENCODE_WIDTH, ENCODE_HEIGHT, 30)
            createPeerConnection()
        }

        httpServer = VideoHttpServer(
            HTTP_PORT,
            outputDir,
            snapshotDir,
            currentServerToken(),
            { getLatestVideoFile() },
            { getLatestSnapshotFile() },
            webRtcService!!
        )
        try {
            httpServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            binding.txtStatus.text = getString(R.string.server_running)
        } catch (exc: Exception) {
            binding.txtStatus.text = "Server error: ${exc.message}"
        }
    }

    private fun restartServers() {
        httpServer?.stop()
        webRtcService?.close()
        startServers()
    }

    private fun updateServerUi() {
        val ip = getLocalIpAddress() ?: "unknown"
        val httpBase = "http://$ip:$HTTP_PORT/"
        binding.txtServer.text = if (tokenEnabled) {
            "HTTP: ${httpBase}?token=$accessToken"
        } else {
            "HTTP: $httpBase"
        }
    }

    // ---- Utilities ----

    private fun getLatestVideoFile(): File? {
        val files = outputDir.listFiles()?.filter { it.isFile && it.extension == "mp4" }
        return files?.maxByOrNull { it.lastModified() }
    }

    private fun getLatestSnapshotFile(): File? {
        val files = snapshotDir.listFiles()?.filter { it.isFile && it.extension == "jpg" }
        return files?.maxByOrNull { it.lastModified() }
    }

    private fun captureSnapshot() {
        val image = currentImageProxy ?: return
        try {
            val bitmap = image.toBitmap()
            val filename = "snapshot_${timestamp()}.jpg"
            val file = File(snapshotDir, filename)
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            val files = snapshotDir.listFiles()?.filter { it.isFile && it.extension == "jpg" }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size > 100) {
                files.take(files.size - 100).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot capture failed", e)
        }
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
        return prefs.getBoolean("token_enabled", false)
    }

    private fun setTokenEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("token_enabled", enabled).apply()
    }

    private fun currentServerToken(): String {
        return if (tokenEnabled) accessToken else ""
    }

    private fun generateAccessToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
