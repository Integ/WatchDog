package com.watchdog.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

class WebRtcClient(
    private val context: Context,
    private val onStatusChange: (String) -> Unit
) {
    companion object {
        private const val TAG = "WebRtcClient"
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

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

        videoSource = peerConnectionFactory?.createVideoSource(false)
        localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)
        
        Log.i(TAG, "WebRtcClient initialized")
    }

    fun pushFrame(image: ImageProxy) {
        val vs = videoSource ?: return
        try {
            val width = image.width
            val height = image.height
            val nv21 = imageProxyToNv21(image)
            val rotation = image.imageInfo.rotationDegrees
            val timestampNs = image.imageInfo.timestamp * 1000

            val buffer = NV21Buffer(nv21, width, height, null)
            val frame = VideoFrame(buffer, rotation, timestampNs)
            vs.capturerObserver.onFrameCaptured(frame)
            frame.release()
        } catch (e: Exception) {
            Log.w(TAG, "pushFrame failed", e)
        }
    }

    fun connect(url: String) {
        if (webSocket != null) {
            disconnect()
        }
        
        onStatusChange("Connecting to Signalling...")
        
        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                mainHandler.post { 
                    onStatusChange("Signalling connected, creating offer...")
                    createPeerConnection()
                    createOffer()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS Message: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "answer" -> {
                            val sdp = SessionDescription(
                                SessionDescription.Type.ANSWER,
                                json.getString("sdp")
                            )
                            mainHandler.post {
                                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                            }
                        }
                        "candidate" -> {
                            val candidate = IceCandidate(
                                json.getString("sdpMid"),
                                json.getInt("sdpMLineIndex"),
                                json.getString("candidate")
                            )
                            mainHandler.post {
                                peerConnection?.addIceCandidate(candidate)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WS message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed")
                mainHandler.post {
                    onStatusChange("Disconnected")
                    closePeerConnection()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                mainHandler.post {
                    onStatusChange("Signalling error: ${t.message}")
                    closePeerConnection()
                }
            }
        })
    }

    private fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                mainHandler.post {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> onStatusChange("Streaming")
                        PeerConnection.IceConnectionState.FAILED -> onStatusChange("ICE Failed")
                        PeerConnection.IceConnectionState.DISCONNECTED -> onStatusChange("ICE Disconnected")
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            
            override fun onIceCandidate(candidate: IceCandidate) {
                // Send candidate to server
                val json = JSONObject().apply {
                    put("type", "candidate")
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                webSocket?.send(json.toString())
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        localVideoTrack?.let {
            // Need a sender to stream video
            peerConnection?.addTrack(it, listOf("stream0"))
        }
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                
                // Send offer
                val json = JSONObject().apply {
                    put("type", "offer")
                    put("sdp", sdp.description)
                }
                webSocket?.send(json.toString())
            }
        }, constraints)
    }

    fun disconnect() {
        webSocket?.close(1000, "User requested disconnect")
        webSocket = null
        closePeerConnection()
        mainHandler.post { onStatusChange("Disconnected") }
    }

    private fun closePeerConnection() {
        peerConnection?.close()
        peerConnection = null
    }

    fun close() {
        disconnect()
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

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

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

        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride

        vBuffer.rewind()
        uBuffer.rewind()

        var pos = ySize
        if (uvPixelStride == 2 && uvRowStride == width) {
            vBuffer.get(nv21, ySize, uvSize - 1)
            nv21[ySize + uvSize - 1] = uBuffer.get(uvSize - 2)
        } else {
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
}
