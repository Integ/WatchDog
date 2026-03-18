package com.watchdog.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
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
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Object()

    private val autoRotateRunnable = Runnable {
        if (isSegmenting) {
            rotateRecordingSegment()
        }
    }

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

        // Keep screen on for surveillance
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        outputDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES), "WatchDog"
        ).apply { mkdirs() }

        snapshotDir = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES), "WatchDog"
        ).apply { mkdirs() }

        accessToken = getOrCreateAccessToken()
        tokenEnabled = getTokenEnabled()

        // UI setup
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.chkEnableToken.isChecked = tokenEnabled
        binding.chkEnableToken.setOnCheckedChangeListener { _, isChecked ->
            if (tokenEnabled == isChecked) return@setOnCheckedChangeListener
            tokenEnabled = isChecked
            setTokenEnabled(isChecked)
            restartServers()
            updateServerUi()
        }

        if (allPermissionsGranted()) {
            onPermissionsGranted()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    /**
     * Called after camera + audio permissions are granted.
     * Initializes WebRTC, starts camera, then starts HTTP server.
     */
    private fun onPermissionsGranted() {
        initWebRtc()
        startCamera()
        startHttpServer()
        updateServerUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        isSegmenting = false
        segmentHandler.removeCallbacks(autoRotateRunnable)
        snapshotHandler.removeCallbacksAndMessages(null)
        recording?.stop()
        httpServer?.stop()
        webRtcService?.close()
        analysisExecutor.shutdown()
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }

    // ---- WebRTC ----

    private fun initWebRtc() {
        webRtcService = WebRtcService(this).apply {
            initialize()
        }
        Log.i(TAG, "WebRTC initialized")
    }

    // ---- Camera (CameraX for preview + recording + snapshot) ----

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview for the Android device screen
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // Video recording
            val qualitySelector = QualitySelector.from(
                Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // ImageAnalysis for periodic snapshots
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(ENCODE_WIDTH, ENCODE_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                // Push frame to WebRTC for live streaming (before closing)
                webRtcService?.pushFrame(image)

                // Convert to Bitmap for snapshot storage
                try {
                    val bmp = image.toBitmap()
                    synchronized(bitmapLock) {
                        latestBitmap?.recycle()
                        latestBitmap = bmp
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame conversion failed", e)
                } finally {
                    image.close()
                }
            }

            // Snapshot every 10 seconds
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
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Recording (auto-segmented) ----

    private fun toggleRecording() {
        if (!isSegmenting) {
            isSegmenting = true
            startRecordingSegment()
            updateRecordingUi(true)
        } else {
            stopRecordingCompletely()
        }
    }

    private fun startRecordingSegment() {
        val vc = this.videoCapture ?: return
        enforceMaxFiles()
        val fileName = "${timestamp()}.mp4"
        val outputFile = File(outputDir, fileName)
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val pendingRecording = vc.output.prepareRecording(this, outputOptions).apply {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                withAudioEnabled()
            }
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.txtStatus.text = "● Recording…"
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (event.hasError()) {
                        binding.txtStatus.text = "Recording error: ${event.error}"
                    } else {
                        Log.i(TAG, "Segment saved: ${outputFile.name}")
                    }
                    if (isSegmenting) {
                        startRecordingSegment()
                    } else {
                        updateRecordingUi(false)
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
        updateRecordingUi(false)
    }

    private fun updateRecordingUi(isRecording: Boolean) {
        if (isRecording) {
            binding.btnRecord.text = getString(R.string.stop_recording)
            binding.recordIndicator.visibility = View.VISIBLE
        } else {
            binding.btnRecord.text = getString(R.string.start_recording)
            binding.recordIndicator.visibility = View.GONE
            binding.txtStatus.text = getString(R.string.server_running)
        }
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

    // ---- HTTP Server ----

    private fun startHttpServer() {
        val rtc = webRtcService ?: return
        httpServer = VideoHttpServer(
            HTTP_PORT,
            outputDir,
            snapshotDir,
            currentServerToken(),
            { getLatestVideoFile() },
            { getLatestSnapshotFile() },
            rtc
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
        startHttpServer()
    }

    private fun updateServerUi() {
        val ip = getLocalIpAddress() ?: "unknown"
        val httpBase = "http://$ip:$HTTP_PORT/"
        binding.txtServer.text = if (tokenEnabled) {
            "$httpBase?token=$accessToken"
        } else {
            httpBase
        }
    }

    // ---- Utilities ----

    private fun getLatestVideoFile(): File? {
        return outputDir.listFiles()?.filter { it.isFile && it.extension == "mp4" }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun getLatestSnapshotFile(): File? {
        return snapshotDir.listFiles()?.filter { it.isFile && it.extension == "jpg" }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun captureSnapshot() {
        val bitmap: Bitmap
        synchronized(bitmapLock) {
            bitmap = latestBitmap?.copy(latestBitmap!!.config, false) ?: return
        }
        try {
            val filename = "snapshot_${timestamp()}.jpg"
            val file = File(snapshotDir, filename)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            // Keep max 100 snapshots
            val files = snapshotDir.listFiles()?.filter { it.isFile && it.extension == "jpg" }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size > 100) {
                files.take(files.size - 100).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot capture failed", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
        } catch (_: Exception) { null }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getOrCreateAccessToken(): String {
        val prefs = getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
        val existing = prefs.getString("access_token", null)
        if (!existing.isNullOrBlank()) return existing
        val token = generateAccessToken()
        prefs.edit().putString("access_token", token).apply()
        return token
    }

    private fun getTokenEnabled(): Boolean {
        return getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
            .getBoolean("token_enabled", false)
    }

    private fun setTokenEnabled(enabled: Boolean) {
        getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
            .edit().putBoolean("token_enabled", enabled).apply()
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
