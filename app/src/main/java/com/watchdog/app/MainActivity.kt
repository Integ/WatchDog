package com.watchdog.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.watchdog.app.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val ENCODE_WIDTH = 1280
        private const val ENCODE_HEIGHT = 720
        private const val ENCODE_BITRATE = 5_000_000 // 5 Mbps for 720p
        private const val RTSP_PORT = 8554

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    private var rtspServer: RtspServer? = null
    private var h264Encoder: H264Encoder? = null
    private val encoderLock = Any()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            onPermissionsGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            onPermissionsGranted()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun onPermissionsGranted() {
        startServers()
        startCamera()
        updateServerUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        rtspServer?.stop()
        h264Encoder?.stop()
        analysisExecutor.shutdown()
    }

    private fun startServers() {
        rtspServer = RtspServer(RTSP_PORT, "").also {
            it.start()
        }
    }

    private fun updateServerUi() {
        val ip = getLocalIpAddress() ?: "unknown"
        val rtspUrl = "rtsp://$ip:$RTSP_PORT/video"
        binding.txtServer.text = "RTSP Server URL:\n$rtspUrl"
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
                val rotation = image.imageInfo.rotationDegrees
                val nv12 = yuv420ToNv12(image)
                image.close()

                if (nv12 != null) {
                    val timestampUs = image.imageInfo.timestamp / 1000 // ns → µs
                    val rotatedNv12 = rotateNv12(nv12, image.width, image.height, rotation)

                    val outWidth = if (rotation == 90 || rotation == 270) image.height else image.width
                    val outHeight = if (rotation == 90 || rotation == 270) image.width else image.height

                    synchronized(encoderLock) {
                        if (h264Encoder == null) {
                            h264Encoder = H264Encoder(outWidth, outHeight, ENCODE_BITRATE).apply {
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
                    }

                    h264Encoder?.feedFrame(rotatedNv12, timestampUs)
                }
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
                Log.e(TAG, "Camera bind failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateNv12(data: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
        if (rotation == 0) return data
        val out = ByteArray(data.size)
        val frameSize = width * height

        when (rotation) {
            90 -> {
                // rotate Y
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[x * height + height - y - 1] = data[y * width + x]
                    }
                }
                // rotate UV
                val chromaHeight = height / 2
                val chromaWidth = width / 2
                for (y in 0 until chromaHeight) {
                    for (x in 0 until chromaWidth) {
                        val outOffset = frameSize + (x * chromaHeight + chromaHeight - y - 1) * 2
                        val inOffset = frameSize + (y * chromaWidth + x) * 2
                        out[outOffset] = data[inOffset]
                        out[outOffset + 1] = data[inOffset + 1]
                    }
                }
            }
            180 -> {
                // rotate Y
                for (i in 0 until frameSize) {
                    out[frameSize - 1 - i] = data[i]
                }
                // rotate UV
                var i = frameSize
                while (i < data.size) {
                    out[data.size - 2 - (i - frameSize)] = data[i]
                    out[data.size - 1 - (i - frameSize)] = data[i + 1]
                    i += 2
                }
            }
            270 -> {
                // rotate Y
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(width - x - 1) * height + y] = data[y * width + x]
                    }
                }
                // rotate UV
                val chromaHeight = height / 2
                val chromaWidth = width / 2
                for (y in 0 until chromaHeight) {
                    for (x in 0 until chromaWidth) {
                        val outOffset = frameSize + ((chromaWidth - x - 1) * chromaHeight + y) * 2
                        val inOffset = frameSize + (y * chromaWidth + x) * 2
                        out[outOffset] = data[inOffset]
                        out[outOffset + 1] = data[inOffset + 1]
                    }
                }
            }
        }
        return out
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
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var uvPos = width * height
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                nv12[uvPos++] = vBuffer.get(vIndex) // V
                nv12[uvPos++] = uBuffer.get(uIndex) // U
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
        } catch (exc: Exception) {
            null
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
