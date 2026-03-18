package com.watchdog.app

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRtcService(private val context: Context) {
    companion object {
        private const val TAG = "WebRtcService"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var onLocalSessionDescription: ((SessionDescription) -> Unit)? = null
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onPeerConnectionStateChanged: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    fun initialize() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.i(TAG, "WebRTC initialized")
    }

    fun startCapture(width: Int, height: Int, fps: Int) {
        val eglBaseContext = EglBase.create().eglBaseContext
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)

        videoSource = peerConnectionFactory?.createVideoSource(false)
        videoCapturer = createCameraCapturer()

        val surfaceCapture = surfaceTextureHelper?.let {
            Camera1Capturer(
                videoCapturer as VideoCapturer,
                surfaceTextureHelper,
                false
            )
        }

        videoCapturer?.initialize(
            surfaceTextureHelper,
            context,
            videoSource?.capturerObserver
        )
        videoCapturer?.startCapture(width, height, fps)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("video_track", videoSource)
        Log.i(TAG, "Camera capture started: ${width}x${height} @ ${fps}fps")
    }

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera1Enumerator(false)
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        throw IllegalStateException("No camera found")
    }

    fun createPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "Ice connection state: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.i(TAG, "Signaling state: $state")
            }

            override fun onPeerConnectionState(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "Peer connection state: $state")
                onPeerConnectionStateChanged?.invoke(state)
            }
        })

        localVideoTrack?.let { videoTrack ->
            val stream = peerConnectionFactory?.createLocalMediaStream("local_stream")
            stream?.addTrack(videoTrack)
            peerConnection?.addStream(stream)
        }

        return peerConnection
    }

    fun setLocalDescription(sdp: SessionDescription) {
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.i(TAG, "Local description set successfully")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "Failed to set local description: $p0")
            }
        }, sdp)
    }

    fun setRemoteDescription(sdp: SessionDescription, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                onSuccess()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                onFailure(p0 ?: "Unknown error")
            }
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestarts", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    setLocalDescription(it)
                    onLocalSessionDescription?.invoke(it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    setLocalDescription(it)
                    onLocalSessionDescription?.invoke(it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            localVideoTrack?.dispose()
            videoSource?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.close()
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebRTC", e)
        }
        Log.i(TAG, "WebRTC closed")
    }
}
