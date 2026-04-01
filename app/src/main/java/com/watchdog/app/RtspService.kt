package com.watchdog.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class CameraOption(
    val id: String,
    val label: String
)

data class VideoStreamInfo(
    val width: Int,
    val height: Int,
    val frameRate: Int
)

class RtspService : LifecycleService() {

    companion object {
        private const val TAG = "RtspService"
        private const val NOTIFICATION_CHANNEL_ID = "RtspServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "rtsp_service_prefs"
        private const val KEY_SELECTED_CAMERA_ID = "selected_camera_id"

        private const val PREFERRED_WIDTH = 1280
        private const val PREFERRED_HEIGHT = 720
        private const val ENCODE_BITRATE = 5_000_000
        private const val RTSP_PORT = 8554
    }

    private val binder = LocalBinder()
    private val streamingLock = Any()

    private lateinit var analysisExecutor: ExecutorService
    private var wakeLock: PowerManager.WakeLock? = null

    private var rtspServer: RtspServer? = null
    private var h264Encoder: H264Encoder? = null
    private var encoderInputBuffer: ByteArray? = null
    private var encoderInputMode: H264Encoder.InputMode? = null
    private var encoderWidth = 0
    private var encoderHeight = 0

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null
    private var availableCameras: List<CameraOption> = emptyList()
    private var selectedCameraId: String? = null
    private var cameraStateListener: CameraStateListener? = null

    @Volatile
    private var isDestroyed = false

    interface CameraStateListener {
        fun onCameraOptionsChanged(options: List<CameraOption>, selectedCameraId: String?)
    }

    interface VideoInfoListener {
        fun onVideoInfoChanged(info: VideoStreamInfo)
    }

    private var videoInfoListener: VideoInfoListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): RtspService = this@RtspService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        analysisExecutor = Executors.newSingleThreadExecutor()
        selectedCameraId = loadSelectedCameraId()
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        startRtspServerIfNeeded()
        startCameraAnalysis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        isDestroyed = true
        Log.i(TAG, "onDestroy")
        stopCamera()
        stopStreamingPipeline()
        releaseWakeLock()
        analysisExecutor.shutdownNow()
        pendingSurfaceProvider = null
        encoderInputBuffer = null
        super.onDestroy()
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        pendingSurfaceProvider = surfaceProvider
        previewUseCase = Preview.Builder().build().also { preview ->
            preview.setSurfaceProvider(surfaceProvider)
        }

        try {
            rebindCameraUseCases()
            Log.i(TAG, "Preview attached")
        } catch (exc: Exception) {
            Log.e(TAG, "Preview bind failed", exc)
        }
    }

    fun detachPreview() {
        pendingSurfaceProvider = null
        previewUseCase = null
        try {
            rebindCameraUseCases()
            Log.i(TAG, "Preview detached")
        } catch (exc: Exception) {
            Log.e(TAG, "Preview detach failed", exc)
        }
    }

    fun getRtspUrl(): String {
        val ip = getLocalIpAddress() ?: "unknown"
        return "rtsp://$ip:$RTSP_PORT/video"
    }

    fun getAvailableCameras(): List<CameraOption> = availableCameras

    fun getSelectedCameraId(): String? = selectedCameraId

    fun setCameraStateListener(listener: CameraStateListener?) {
        cameraStateListener = listener
        notifyCameraState(listener)
    }

    fun setVideoInfoListener(listener: VideoInfoListener?) {
        videoInfoListener = listener
    }

    private var currentVideoInfo: VideoStreamInfo? = null

    private fun notifyVideoInfo(info: VideoStreamInfo) {
        if (info == currentVideoInfo) {
            return
        }
        currentVideoInfo = info
        videoInfoListener?.onVideoInfoChanged(info)
    }

    fun setSelectedCamera(cameraId: String): Boolean {
        if (cameraId == selectedCameraId) {
            return true
        }

        if (availableCameras.isNotEmpty() && availableCameras.none { it.id == cameraId }) {
            return false
        }

        val previousCameraId = selectedCameraId
        selectedCameraId = cameraId
        saveSelectedCameraId(cameraId)
        synchronized(streamingLock) {
            stopEncoderLocked()
        }

        try {
            rebindCameraUseCases()
            Log.i(TAG, "Switched to camera $cameraId")
        } catch (exc: Exception) {
            selectedCameraId = previousCameraId
            saveSelectedCameraId(previousCameraId)
            runCatching { rebindCameraUseCases() }
            Log.e(TAG, "Failed to switch camera to $cameraId", exc)
            return false
        }

        notifyCameraState()
        return true
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WatchDog::RtspWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun startRtspServerIfNeeded() {
        synchronized(streamingLock) {
            if (rtspServer != null) {
                return
            }

            rtspServer = RtspServer(RTSP_PORT, "").also { server ->
                server.start()
            }
            Log.i(TAG, "RTSP server started")
        }
    }

    private fun ensureEncoderForFrame(width: Int, height: Int): H264Encoder = synchronized(streamingLock) {
        startRtspServerIfNeeded()

        val existing = h264Encoder
        if (existing != null && encoderWidth == width && encoderHeight == height) {
            return existing
        }

        if (existing != null) {
            Log.i(
                TAG,
                "Camera frame size changed ${encoderWidth}x${encoderHeight} -> ${width}x${height}, restarting encoder"
            )
        } else {
            Log.i(TAG, "Starting encoder for camera frame size ${width}x${height}")
        }

        stopEncoderLocked()

        return H264Encoder(width, height, ENCODE_BITRATE).apply {
            onNalUnit = { data, pts, isConfig ->
                rtspServer?.feedNalUnit(data, pts, isConfig)
            }
            onSpsPpsReady = { sps, pps ->
                rtspServer?.sps = sps
                rtspServer?.pps = pps
            }
            onVideoFormatChanged = { w, h, fps ->
                notifyVideoInfo(VideoStreamInfo(w, h, fps))
            }
            start()
            h264Encoder = this
            encoderWidth = width
            encoderHeight = height
            encoderInputMode = inputMode
            encoderInputBuffer = null
            Log.i(TAG, "Encoder ready: ${width}x${height}, mode=$inputMode")
        }
    }

    private fun stopStreamingPipeline() = synchronized(streamingLock) {
        stopEncoderLocked()
        rtspServer?.stop()
        rtspServer = null
    }

    private fun stopEncoderLocked() {
        h264Encoder?.onNalUnit = null
        h264Encoder?.onSpsPpsReady = null
        h264Encoder?.onVideoFormatChanged = null
        h264Encoder?.stop()
        h264Encoder = null
        encoderWidth = 0
        encoderHeight = 0
        encoderInputMode = null
        encoderInputBuffer = null
        currentVideoInfo = null
    }

    private fun startCameraAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (isDestroyed) {
                return@addListener
            }

            try {
                cameraProvider = cameraProviderFuture.get()
                imageAnalysisUseCase = buildImageAnalysisUseCase()
                refreshAvailableCameras()
                rebindCameraUseCases()
            } catch (exc: Exception) {
                Log.e(TAG, "Camera initialization failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildImageAnalysisUseCase(): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(PREFERRED_WIDTH, PREFERRED_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        return ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { image ->
                    val timestampUs = image.imageInfo.timestamp / 1000
                    try {
                        val encoder = ensureEncoderForFrame(image.width, image.height)
                        val input = yuv420ToEncoderInput(
                            image = image,
                            inputMode = encoder.inputMode
                        )
                        if (input != null) {
                            encoder.feedFrame(input, timestampUs)
                        }
                    } catch (exc: Exception) {
                        Log.e(TAG, "Frame analysis failed", exc)
                    } finally {
                        image.close()
                    }
                }
            }
    }

    private fun rebindCameraUseCases() {
        val provider = cameraProvider ?: return
        val analysis = imageAnalysisUseCase ?: return
        refreshAvailableCameras()
        val useCases = mutableListOf<UseCase>(analysis)

        previewUseCase?.let { preview ->
            pendingSurfaceProvider?.let(preview::setSurfaceProvider)
            useCases.add(0, preview)
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            buildSelectedCameraSelector(),
            *useCases.toTypedArray()
        )
    }

    private fun stopCamera() {
        try {
            imageAnalysisUseCase?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (exc: Exception) {
            Log.w(TAG, "Camera cleanup failed", exc)
        } finally {
            previewUseCase = null
            imageAnalysisUseCase = null
            cameraProvider = null
            availableCameras = emptyList()
        }
    }

    private fun refreshAvailableCameras() {
        val provider = cameraProvider ?: return
        val cameras = provider.availableCameraInfos
            .mapNotNull { cameraInfo ->
                val cameraId = runCatching { Camera2CameraInfo.from(cameraInfo).cameraId }
                    .getOrNull()
                    ?: return@mapNotNull null

                CameraOption(
                    id = cameraId,
                    label = buildCameraLabel(cameraInfo.lensFacing, cameraId)
                )
            }
            .distinctBy(CameraOption::id)
            .sortedWith(
                compareBy<CameraOption>(
                    { cameraSortKey(it.label) },
                    { it.label }
                )
            )

        availableCameras = cameras

        val resolvedSelection = resolveSelectedCameraId(cameras)
        if (resolvedSelection != selectedCameraId) {
            selectedCameraId = resolvedSelection
            saveSelectedCameraId(resolvedSelection)
            synchronized(streamingLock) {
                stopEncoderLocked()
            }
        }

        notifyCameraState()
    }

    private fun resolveSelectedCameraId(options: List<CameraOption>): String? {
        val current = selectedCameraId
        if (current != null && options.any { it.id == current }) {
            return current
        }

        return options.firstOrNull { it.label.startsWith("Rear camera") }?.id
            ?: options.firstOrNull()?.id
    }

    private fun buildSelectedCameraSelector(): CameraSelector {
        val targetCameraId = selectedCameraId
        if (targetCameraId == null) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }

        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { cameraInfo ->
                    runCatching { Camera2CameraInfo.from(cameraInfo).cameraId == targetCameraId }
                        .getOrDefault(false)
                }
            }
            .build()
    }

    private fun buildCameraLabel(lensFacing: Int?, cameraId: String): String {
        val facingLabel = when (lensFacing) {
            CameraSelector.LENS_FACING_BACK -> "Rear camera"
            CameraSelector.LENS_FACING_FRONT -> "Front camera"
            CameraSelector.LENS_FACING_EXTERNAL -> "External camera"
            else -> "Camera"
        }
        return "$facingLabel (ID $cameraId)"
    }

    private fun cameraSortKey(label: String): Int {
        return when {
            label.startsWith("Rear camera") -> 0
            label.startsWith("Front camera") -> 1
            label.startsWith("External camera") -> 2
            else -> 3
        }
    }

    private fun loadSelectedCameraId(): String? {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_CAMERA_ID, null)
    }

    private fun saveSelectedCameraId(cameraId: String?) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (cameraId.isNullOrBlank()) {
                    remove(KEY_SELECTED_CAMERA_ID)
                } else {
                    putString(KEY_SELECTED_CAMERA_ID, cameraId)
                }
            }
            .apply()
    }

    private fun notifyCameraState(listener: CameraStateListener? = cameraStateListener) {
        val targetListener = listener ?: return
        val optionsSnapshot = availableCameras
        val selectedCameraSnapshot = selectedCameraId
        ContextCompat.getMainExecutor(this).execute {
            targetListener.onCameraOptionsChanged(optionsSnapshot, selectedCameraSnapshot)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "RTSP Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WatchDog Streaming")
            .setContentText("Camera is running in the background")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.wd_launcher)
            .build()
    }

    private fun yuv420ToEncoderInput(
        image: ImageProxy,
        inputMode: H264Encoder.InputMode
    ): ByteArray? {
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

        val requiredSize = width * height + width * height / 2
        var output = encoderInputBuffer
        if (
            output == null ||
            output.size != requiredSize ||
            encoderWidth != width ||
            encoderHeight != height ||
            encoderInputMode != inputMode
        ) {
            output = ByteArray(requiredSize)
            encoderInputBuffer = output
        }

        var yPos = 0
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            if (yPixelStride == 1) {
                yBuffer.position(yRowStart)
                yBuffer.get(output, yPos, width)
                yPos += width
            } else {
                for (col in 0 until width) {
                    output[yPos++] = yBuffer.get(yRowStart + col * yPixelStride)
                }
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uPlaneOffset = width * height

        when (inputMode) {
            H264Encoder.InputMode.YUV420_PLANAR -> {
                val vPlaneOffset = uPlaneOffset + chromaWidth * chromaHeight
                var uPos = uPlaneOffset
                var vPos = vPlaneOffset
                for (row in 0 until chromaHeight) {
                    val uRowStart = row * uRowStride
                    val vRowStart = row * vRowStride
                    for (col in 0 until chromaWidth) {
                        val uIndex = uRowStart + col * uPixelStride
                        val vIndex = vRowStart + col * vPixelStride
                        output[uPos++] = uBuffer.get(uIndex)
                        output[vPos++] = vBuffer.get(vIndex)
                    }
                }
            }

            H264Encoder.InputMode.YUV420_SEMIPLANAR -> {
                var uvPos = uPlaneOffset
                for (row in 0 until chromaHeight) {
                    val uRowStart = row * uRowStride
                    val vRowStart = row * vRowStride
                    for (col in 0 until chromaWidth) {
                        val uIndex = uRowStart + col * uPixelStride
                        val vIndex = vRowStart + col * vPixelStride
                        output[uvPos++] = uBuffer.get(uIndex)
                        output[uvPos++] = vBuffer.get(vIndex)
                    }
                }
            }
        }

        return output
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
        } catch (_: Exception) {
            null
        }
    }
}
