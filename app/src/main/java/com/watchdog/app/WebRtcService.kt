package com.watchdog.app

import android.content.Context
import android.util.Log
import org.webrtc.*

/**
 * Manages WebRTC video capture and per-session PeerConnections.
 *
 * - A single camera capturer feeds frames into a shared VideoSource/VideoTrack.
 * - Each browser viewer gets its own PeerConnection via [createSessionPeerConnection].
 */
class WebRtcService(private val context: Context) {
    companion object {
        private const val TAG = "WebRtcService"
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

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

        Log.i(TAG, "WebRTC initialized (shared EglBase)")
    }

    fun startCapture(width: Int, height: Int, fps: Int) {
        val eglContext = eglBase?.eglBaseContext
            ?: throw IllegalStateException("Must call initialize() first")

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
        videoSource = peerConnectionFactory?.createVideoSource(false)

        videoCapturer = createCameraCapturer()
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(width, height, fps)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)

        Log.i(TAG, "Camera capture started: ${width}x${height} @ ${fps}fps")
    }

    /**
     * Creates a camera capturer using Camera2 API.
     * Prefers back-facing camera for surveillance use case.
     */
    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Prefer back camera for surveillance
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    Log.i(TAG, "Using back camera: $deviceName")
                    return capturer
                }
            }
        }
        // Fallback to front camera
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    Log.i(TAG, "Fallback to front camera: $deviceName")
                    return capturer
                }
            }
        }
        throw IllegalStateException("No camera found")
    }

    /**
     * Creates a new PeerConnection for a single viewer session.
     * Each browser gets its own PeerConnection sharing the same local video track.
     */
    fun createSessionPeerConnection(
        onIceCandidate: (IceCandidate) -> Unit,
        onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit = {}
    ): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val pc = peerConnectionFactory?.createPeerConnection(config, object : PeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Server ICE candidate: ${candidate.sdp}")
                onIceCandidate(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE connection state: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onPeerConnectionState(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "Peer connection state: $state")
                onConnectionStateChange(state)
            }
        })

        // Add the shared video track to this connection using addTrack (not deprecated addStream)
        localVideoTrack?.let { track ->
            pc?.addTrack(track, listOf("stream0"))
            Log.i(TAG, "Added video track to new PeerConnection")
        }

        return pc
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: InterruptedException) {}
        try {
            videoCapturer?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            videoSource?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnectionFactory?.dispose()
            eglBase?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebRTC", e)
        }
        Log.i(TAG, "WebRTC closed")
    }
}
