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

class RtspService : LifecycleService() {

    companion object {
        private const val TAG = "RtspService"
        private const val NOTIFICATION_CHANNEL_ID = "RtspServiceChannel"
        private const val NOTIFICATION_ID = 1

        private const val ENCODE_WIDTH = 1280
        private const val ENCODE_HEIGHT = 720
        private const val ENCODE_BITRATE = 5_000_000
        private const val RTSP_PORT = 8554
    }

    private val binder = LocalBinder()
    private val streamingLock = Any()

    private lateinit var analysisExecutor: ExecutorService
    private var wakeLock: PowerManager.WakeLock? = null

    private var rtspServer: RtspServer? = null
    private var h264Encoder: H264Encoder? = null
    private var nv12Buffer: ByteArray? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    @Volatile
    private var isDestroyed = false

    inner class LocalBinder : Binder() {
        fun getService(): RtspService = this@RtspService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        analysisExecutor = Executors.newSingleThreadExecutor()
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        startStreamingPipeline()
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
        nv12Buffer = null
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

    private fun startStreamingPipeline() = synchronized(streamingLock) {
        stopStreamingPipelineLocked()
        Log.i(TAG, "Starting RTSP server and encoder")

        rtspServer = RtspServer(RTSP_PORT, "").also { server ->
            server.start()
        }

        h264Encoder = H264Encoder(ENCODE_WIDTH, ENCODE_HEIGHT, ENCODE_BITRATE).apply {
            onNalUnit = { data, pts, isConfig ->
                rtspServer?.feedNalUnit(data, pts, isConfig)
            }
            onSpsPpsReady = { sps, pps ->
                rtspServer?.sps = sps
                rtspServer?.pps = pps
            }
            start()
        }
    }

    private fun stopStreamingPipeline() = synchronized(streamingLock) {
        stopStreamingPipelineLocked()
    }

    private fun stopStreamingPipelineLocked() {
        h264Encoder?.onNalUnit = null
        h264Encoder?.onSpsPpsReady = null
        h264Encoder?.stop()
        h264Encoder = null

        rtspServer?.stop()
        rtspServer = null
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
                    Size(ENCODE_WIDTH, ENCODE_HEIGHT),
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
                        val nv12 = yuv420ToNv12(image)
                        if (nv12 != null) {
                            h264Encoder?.feedFrame(nv12, timestampUs)
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
        val useCases = mutableListOf<UseCase>(analysis)

        previewUseCase?.let { preview ->
            pendingSurfaceProvider?.let(preview::setSurfaceProvider)
            useCases.add(0, preview)
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
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

        val requiredSize = width * height + width * height / 2
        var nv12 = nv12Buffer
        if (nv12 == null || nv12.size != requiredSize) {
            nv12 = ByteArray(requiredSize)
            nv12Buffer = nv12
        }

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

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var uvPos = width * height
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                nv12[uvPos++] = uBuffer.get(uIndex)
                nv12[uvPos++] = vBuffer.get(vIndex)
            }
        }

        return nv12
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
