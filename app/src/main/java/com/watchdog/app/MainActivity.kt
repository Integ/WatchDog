package com.watchdog.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
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
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
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
        private const val RTSP_PORT = 8554
        private const val ENCODE_WIDTH = 1280
        private const val ENCODE_HEIGHT = 720
        private const val ENCODE_BITRATE = 5_000_000 // 5 Mbps for 720p
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
    private var rtspServer: RtspServer? = null
    private var h264Encoder: H264Encoder? = null
    private lateinit var accessToken: String
    private var tokenEnabled: Boolean = false
    private lateinit var analysisExecutor: ExecutorService
    private val segmentHandler = Handler(Looper.getMainLooper())
    private var isSegmenting = false // true while auto-rotating recording is active
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
        rtspServer?.stop()
        h264Encoder?.stop()
        currentImageProxy?.close()
        analysisExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                // --- H.264 encoder setup ---
                val encoder = H264Encoder(ENCODE_WIDTH, ENCODE_HEIGHT, ENCODE_BITRATE)
                encoder.onNalUnit = { data, pts, isConfig ->
                    rtspServer?.feedNalUnit(data, pts, isConfig)
                }
                encoder.onSpsPpsReady = { sps, pps ->
                    rtspServer?.let {
                        it.sps = sps
                        it.pps = pps
                    }
                }
                encoder.start()
                h264Encoder = encoder

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

                // --- ImageAnalysis → feed H.264 encoder ---
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(ENCODE_WIDTH, ENCODE_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                    val nv12 = yuv420ToNv12(image)
                    if (nv12 != null) {
                        val timestampUs = image.imageInfo.timestamp / 1000 // ns → µs
                        encoder.feedFrame(nv12, timestampUs)
                    }
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

    // ---- YUV conversion ----

    /**
     * Convert ImageProxy (YUV_420_888) to NV12 byte array for MediaCodec encoder.
     * NV12 layout: Y plane followed by interleaved UV.
     */
    private fun yuv420ToNv12(image: ImageProxy): ByteArray? {
        val width = image.width
        val height = image.height
        if (image.format != ImageFormat.YUV_420_888) return null

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

        val nv12 = ByteArray(width * height + width * height / 2)

        // Copy Y plane
        var yPos = 0
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            if (yPixelStride == 1) {
                yBuffer.position(yRowStart)
                yBuffer.get(nv12, yPos, width)
                yPos += width
            } else {
                for (col in 0 until width) {
                    nv12[yPos++] = yBuffer.get(yRowStart + col * yPixelStride)
                }
            }
        }

        // Copy UV plane (NV12: U then V interleaved)
        // Note: Some devices require NV21 (V then U), try swapping if colors look wrong
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var uvPos = width * height
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                // NV21 format: V then U
                nv12[uvPos++] = vBuffer.get(vIndex)
                nv12[uvPos++] = uBuffer.get(uIndex)
            }
        }

        return nv12
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

    /** Start a single recording segment and schedule the next rotation. */
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
                    // If still in segmenting mode, the next segment is started
                    // by rotateRecordingSegment's callback chain.
                    if (isSegmenting) {
                        startRecordingSegment()
                    } else {
                        binding.btnRecord.text = getString(R.string.start_recording)
                    }
                }
            }
        }

        // Schedule auto-rotation after SEGMENT_DURATION_MS
        segmentHandler.removeCallbacks(autoRotateRunnable)
        segmentHandler.postDelayed(autoRotateRunnable, SEGMENT_DURATION_MS)
    }

    /** Called by the timer to rotate to a new segment. */
    private fun rotateRecordingSegment() {
        Log.i(TAG, "Auto-rotating recording segment")
        // Stop current segment; Finalize callback will start the next one
        recording?.stop()
    }

    /** Called when the user presses the Stop button. */
    private fun stopRecordingCompletely() {
        isSegmenting = false
        segmentHandler.removeCallbacks(autoRotateRunnable)
        recording?.stop()
        recording = null
        binding.btnRecord.text = getString(R.string.start_recording)
        binding.txtStatus.text = "Stopping..."
    }

    /**
     * If there are already [MAX_VIDEO_FILES] mp4 files, delete the oldest
     * ones until we have room for one more.
     */
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
        val token = currentServerToken()
        rtspServer = RtspServer(RTSP_PORT, token).also {
            it.sps = h264Encoder?.sps
            it.pps = h264Encoder?.pps
            it.start()
        }

        httpServer = VideoHttpServer(
            HTTP_PORT,
            outputDir,
            snapshotDir,
            token,
            { getLatestVideoFile() },
            { getLatestSnapshotFile() },
            buildRtspUrl()
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
        rtspServer?.stop()
        startServers()
    }

    private fun updateServerUi() {
        val ip = getLocalIpAddress() ?: "unknown"
        val httpBase = "http://$ip:$HTTP_PORT/"
        val rtspBase = buildRtspUrl()
        binding.txtServer.text = if (tokenEnabled) {
            "HTTP: ${httpBase}?token=$accessToken\nRTSP: $rtspBase"
        } else {
            "HTTP: $httpBase\nRTSP: $rtspBase"
        }
    }

    private fun buildRtspUrl(): String {
        val ip = getLocalIpAddress() ?: "0.0.0.0"
        val base = "rtsp://$ip:$RTSP_PORT/video"
        return if (tokenEnabled) {
            "$base?token=${URLEncoder.encode(accessToken, "UTF-8")}"
        } else {
            base
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
            // Keep only last 100 snapshots
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
