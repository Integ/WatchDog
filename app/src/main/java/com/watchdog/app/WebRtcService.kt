package com.watchdog.app

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * Manages WebRTC video streaming using frames pushed from CameraX.
 *
 * Instead of opening its own camera, this service receives frames via [pushFrame]
 * from CameraX's ImageAnalysis and forwards them to all connected WebRTC peers.
 */
class WebRtcService(private val context: Context) {
    companion object {
        private const val TAG = "WebRtcService"
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    fun initialize() {
        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val eglContext = eglBase!!.eglBaseContext

        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        // Create video source (no capturer — frames are pushed from CameraX)
        videoSource = peerConnectionFactory?.createVideoSource(false)
        localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)

        Log.i(TAG, "WebRTC initialized (frames will be pushed from CameraX)")
    }

    /**
     * Push a camera frame from CameraX's ImageAnalysis into WebRTC.
     * Call this from the analyzer callback BEFORE closing the ImageProxy.
     */
    fun pushFrame(image: ImageProxy) {
        val vs = videoSource ?: return
        try {
            val width = image.width
            val height = image.height
            val nv21 = imageProxyToNv21(image)
            val rotation = image.imageInfo.rotationDegrees
            val timestampNs = image.imageInfo.timestamp * 1000 // microseconds → nanoseconds

            val buffer = NV21Buffer(nv21, width, height, null)
            val frame = VideoFrame(buffer, rotation, timestampNs)
            vs.capturerObserver.onFrameCaptured(frame)
            frame.release()
        } catch (e: Exception) {
            Log.w(TAG, "pushFrame failed", e)
        }
    }

    /**
     * Convert ImageProxy (YUV_420_888) to NV21 byte array.
     */
    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Copy Y plane
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        yBuffer.rewind()
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // Copy VU interleaved (NV21 format = VUVUVU...)
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride

        vBuffer.rewind()
        uBuffer.rewind()

        var pos = ySize
        if (uvPixelStride == 2 && uvRowStride == width) {
            // Fast path: UV planes are already interleaved (common on most devices)
            // V plane contains VUVU... with pixelStride=2
            // We can just copy the V plane buffer which has interleaved VU data
            vBuffer.get(nv21, ySize, uvSize - 1)
            // The last U byte is missing, get it from U plane
            nv21[ySize + uvSize - 1] = uBuffer.get(uvSize - 2)
        } else {
            // Slow path: pixel-by-pixel copy
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vIndex = row * uvRowStride + col * uvPixelStride
                    val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                    nv21[pos++] = vBuffer.get(vIndex)
                    nv21[pos++] = uBuffer.get(uIndex)
                }
            }
        }

        return nv21
    }

    /**
     * Creates a new PeerConnection for a single viewer session.
     */
    fun createSessionPeerConnection(
        onIceCandidate: (IceCandidate) -> Unit,
        onConnectionStateChange: (PeerConnection.IceConnectionState) -> Unit = {}
    ): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val pc = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Server ICE candidate: ${candidate.sdp}")
                onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ICE connection state: $state")
                state?.let { onConnectionStateChange(it) }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        // Add the shared video track
        localVideoTrack?.let { track ->
            pc?.addTrack(track, listOf("stream0"))
            Log.i(TAG, "Added video track to new PeerConnection")
        }

        return pc
    }

    fun close() {
        try {
            localVideoTrack?.dispose()
            videoSource?.dispose()
            peerConnectionFactory?.dispose()
            eglBase?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebRTC", e)
        }
        Log.i(TAG, "WebRTC closed")
    }
}
