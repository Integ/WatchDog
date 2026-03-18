package com.watchdog.app

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

class VideoHttpServer(
    port: Int,
    private val rootDir: File,
    private val accessToken: String,
    private val latestProvider: () -> File?,
    private val webRtcService: WebRtcService
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "VideoHttpServer"
    }

    /**
     * Holds per-session state: a PeerConnection and its gathered ICE candidates.
     */
    private data class ViewerSession(
        val pc: PeerConnection,
        val serverCandidates: CopyOnWriteArrayList<IceCandidate> = CopyOnWriteArrayList()
    )

    private val sessions = ConcurrentHashMap<String, ViewerSession>()
    private var sessionCounter = 0

    val activeSessionCount: Int get() = sessions.size

    override fun serve(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }

        val response = when (session.uri) {
            "/" -> serveIndex()
            "/webrtc/offer" -> handleWebRtcOffer(session)
            "/webrtc/ice" -> handleIceCandidate(session)
            "/latest" -> {
                val latest = latestProvider()
                if (latest == null) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No recordings yet")
                } else {
                    serveFile(latest, session)
                }
            }
            "/files" -> serveFileList()
            else -> {
                if (session.uri.startsWith("/file/")) {
                    val name = session.uri.removePrefix("/file/")
                    val decoded = URLDecoder.decode(name, "UTF-8")
                    val target = File(rootDir, decoded)
                    if (isSafePath(target, rootDir)) {
                        if (target.exists() && target.isFile) serveFile(target, session)
                        else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                    } else {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            }
        }

        // Add CORS headers
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    override fun stop() {
        super.stop()
        for ((_, session) in sessions) {
            try {
                session.pc.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error disposing PeerConnection on server stop", e)
            }
        }
        sessions.clear()
        Log.i(TAG, "VideoHttpServer stopped and peer connections cleaned up")
    }

    // ---- WebRTC Signaling ----

    private fun handleWebRtcOffer(session: IHTTPSession): Response {
        return try {
            val params = HashMap<String, String>()
            session.parseBody(params)
            val body = params["postData"] ?: ""

            val sdpJson = org.json.JSONObject(body)
            val sdp = sdpJson.getString("sdp")
            val type = sdpJson.getString("type")

            val offer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)

            // Create a NEW PeerConnection for this viewer
            val sessionId = "session_${++sessionCounter}"
            val serverCandidates = CopyOnWriteArrayList<IceCandidate>()
            val lock = Object()
            val answerReady = booleanArrayOf(false)
            var answerSdp: SessionDescription? = null

            val pc = webRtcService.createSessionPeerConnection(
                onIceCandidate = { candidate ->
                    serverCandidates.add(candidate)
                },
                onConnectionStateChange = { state ->
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                        state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.CLOSED) {
                        sessions.remove(sessionId)?.pc?.dispose()
                        Log.i(TAG, "Session $sessionId removed (state=$state), active=${sessions.size}")
                    }
                }
            ) ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to create PeerConnection")

            val viewerSession = ViewerSession(pc, serverCandidates)
            sessions[sessionId] = viewerSession

            // Set remote description (the offer from the browser)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    // Create answer
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    }
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp?.let {
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        synchronized(lock) {
                                            answerSdp = it
                                            answerReady[0] = true
                                            lock.notifyAll()
                                        }
                                    }
                                    override fun onCreateFailure(p0: String?) {
                                        Log.e(TAG, "Set local desc failed: $p0")
                                        synchronized(lock) { answerReady[0] = true; lock.notifyAll() }
                                    }
                                    override fun onSetFailure(p0: String?) {
                                        Log.e(TAG, "Set local desc failed: $p0")
                                        synchronized(lock) { answerReady[0] = true; lock.notifyAll() }
                                    }
                                }, it)
                            }
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create answer failed: $error")
                            synchronized(lock) { answerReady[0] = true; lock.notifyAll() }
                        }
                        override fun onSetFailure(error: String?) {}
                    }, constraints)
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "Set remote desc failed: $p0")
                    synchronized(lock) { answerReady[0] = true; lock.notifyAll() }
                }
            }, offer)

            // Wait for answer (max 5 seconds)
            synchronized(lock) {
                var waited = 0
                while (!answerReady[0] && waited < 50) {
                    lock.wait(100)
                    waited++
                }
            }

            if (answerSdp != null) {
                // Also wait a bit for ICE candidates to accumulate
                Thread.sleep(200)

                val candidatesArray = org.json.JSONArray()
                serverCandidates.forEach { c ->
                    candidatesArray.put(org.json.JSONObject().apply {
                        put("candidate", c.sdp)
                        put("sdpMid", c.sdpMid)
                        put("sdpMLineIndex", c.sdpMLineIndex)
                    })
                }

                val responseJson = org.json.JSONObject().apply {
                    put("sdp", answerSdp!!.description)
                    put("type", answerSdp!!.type.canonicalForm())
                    put("sessionId", sessionId)
                    put("iceCandidates", candidatesArray)
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
            } else {
                sessions.remove(sessionId)?.pc?.dispose()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Failed to create answer\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling offer", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleIceCandidate(session: IHTTPSession): Response {
        return try {
            val params = HashMap<String, String>()
            session.parseBody(params)
            val body = params["postData"] ?: ""

            val json = org.json.JSONObject(body)
            val sessionId = json.optString("sessionId", "")
            val candidate = json.getString("candidate")
            val sdpMid = json.optString("sdpMid", "0")
            val sdpMLineIndex = json.getInt("sdpMLineIndex")

            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

            val viewerSession = sessions[sessionId]
            if (viewerSession != null) {
                viewerSession.pc.addIceCandidate(iceCandidate)
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"session not found\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    // ---- HTML Pages ----

    private fun serveIndex(): Response {
        val tokenParam = if (accessToken.isNotBlank()) "?token=${URLEncoder.encode(accessToken, "UTF-8")}" else ""
        val body = buildWebPage(tokenParam)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    private fun buildWebPage(tokenParam: String): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <title>WatchDog Camera</title>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#0a0a0f">
    <style>
        :root {
            --bg: #0a0a0f;
            --surface: rgba(255,255,255,0.05);
            --surface-hover: rgba(255,255,255,0.08);
            --border: rgba(255,255,255,0.08);
            --text: #e4e4e7;
            --text-dim: #71717a;
            --accent: #22c55e;
            --accent-glow: rgba(34,197,94,0.3);
            --danger: #ef4444;
            --radius: 16px;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Inter, Roboto, sans-serif;
            background: var(--bg);
            color: var(--text);
            min-height: 100vh;
            min-height: 100dvh;
        }
        .app {
            max-width: 960px;
            margin: 0 auto;
            padding: 16px;
            padding-top: env(safe-area-inset-top, 16px);
        }
        .header {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 12px 0;
            margin-bottom: 8px;
        }
        .header h1 {
            font-size: 20px;
            font-weight: 700;
            letter-spacing: -0.02em;
        }
        .status-dot {
            width: 10px; height: 10px;
            border-radius: 50%;
            background: var(--text-dim);
            flex-shrink: 0;
        }
        .status-dot.connected {
            background: var(--accent);
            box-shadow: 0 0 8px var(--accent-glow);
            animation: pulse 2s ease-in-out infinite;
        }
        .status-dot.error { background: var(--danger); }
        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.5; }
        }
        .status-text {
            font-size: 13px;
            color: var(--text-dim);
            margin-left: auto;
        }

        /* Video */
        .video-wrap {
            position: relative;
            background: #000;
            border-radius: var(--radius);
            overflow: hidden;
            aspect-ratio: 16/9;
            margin-bottom: 12px;
            border: 1px solid var(--border);
        }
        .video-wrap video {
            width: 100%; height: 100%;
            object-fit: contain;
            display: block;
        }
        .video-controls {
            position: absolute;
            bottom: 0; left: 0; right: 0;
            padding: 12px;
            background: linear-gradient(transparent, rgba(0,0,0,0.7));
            display: flex;
            gap: 8px;
            align-items: center;
            opacity: 0;
            transition: opacity 0.3s;
        }
        .video-wrap:hover .video-controls { opacity: 1; }
        .video-wrap:active .video-controls { opacity: 1; }

        /* Buttons */
        .btn {
            display: inline-flex; align-items: center; gap: 6px;
            padding: 8px 16px;
            border: 1px solid var(--border);
            border-radius: 8px;
            background: var(--surface);
            color: var(--text);
            font-size: 13px; font-weight: 500;
            cursor: pointer;
            transition: all 0.2s;
            backdrop-filter: blur(12px);
            -webkit-backdrop-filter: blur(12px);
        }
        .btn:hover { background: var(--surface-hover); }
        .btn:active { transform: scale(0.97); }
        .btn-accent {
            background: var(--accent);
            color: #000;
            border-color: var(--accent);
            font-weight: 600;
        }
        .btn-accent:hover { background: #16a34a; border-color: #16a34a; }
        .btn svg { width: 16px; height: 16px; }

        /* Cards */
        .card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: var(--radius);
            backdrop-filter: blur(20px);
            -webkit-backdrop-filter: blur(20px);
            margin-bottom: 12px;
            overflow: hidden;
        }
        .card-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 14px 16px;
            cursor: pointer;
            user-select: none;
        }
        .card-header:hover { background: var(--surface-hover); }
        .card-header h2 {
            font-size: 14px;
            font-weight: 600;
            display: flex; align-items: center; gap: 8px;
        }
        .card-chevron {
            color: var(--text-dim);
            transition: transform 0.2s;
            font-size: 12px;
        }
        .card-body {
            max-height: 0;
            overflow: hidden;
            transition: max-height 0.3s ease;
        }
        .card-body.open { max-height: 2000px; }
        .card-body-inner { padding: 0 16px 14px; }

        /* File list */
        .file-list { list-style: none; }
        .file-list li {
            padding: 8px 0;
            border-top: 1px solid var(--border);
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 13px;
        }
        .file-list a {
            color: var(--accent);
            text-decoration: none;
        }
        .file-list a:hover { text-decoration: underline; }
        .file-list .size { color: var(--text-dim); font-size: 12px; }
        .empty { color: var(--text-dim); font-size: 13px; padding: 8px 0; }

        @media (max-width: 600px) {
            .app { padding: 8px; }
            .video-wrap { border-radius: 12px; }
            .video-controls { opacity: 1; }
        }
    </style>
</head>
<body>
    <div class="app">
        <div class="header">
            <span style="font-size:24px">🐕</span>
            <h1>WatchDog</h1>
            <span class="status-dot" id="dot"></span>
            <span class="status-text" id="status">Connecting…</span>
        </div>

        <div class="video-wrap" id="videoWrap">
            <video id="video" autoplay playsinline muted></video>
            <div class="video-controls">
                <button class="btn btn-accent" id="btnStream" onclick="toggleStream()">
                    ▶ Connect
                </button>
                <button class="btn" onclick="toggleFullscreen()">⛶ Fullscreen</button>
            </div>
        </div>

        <div class="card">
            <div class="card-header" onclick="toggleCard(this)">
                <h2>📁 Recordings</h2>
                <span class="card-chevron">▼</span>
            </div>
            <div class="card-body">
                <div class="card-body-inner">
                    <ul class="file-list" id="filesList"><li class="empty">Loading…</li></ul>
                </div>
            </div>
        </div>
    </div>

<script>
const tokenParam = '${tokenParam}';
const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent);
console.log('Browser:', isSafari ? 'Safari' : 'Chrome/Other');
const pcConfig = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
    ]
};

let pc = null;
let sessionId = null;
let reconnectTimer = null;
let reconnectDelay = 2000;
const video = document.getElementById('video');
const dot = document.getElementById('dot');
const statusEl = document.getElementById('status');
const btnStream = document.getElementById('btnStream');
let streaming = false;

function setStatus(text, state) {
    statusEl.textContent = text;
    dot.className = 'status-dot' + (state === 'ok' ? ' connected' : state === 'err' ? ' error' : '');
}

async function startStream() {
    if (pc) stopStream(false);
    streaming = true;
    btnStream.innerHTML = '⏹ Disconnect';
    setStatus('Connecting…', '');

    pc = new RTCPeerConnection(pcConfig);

    pc.onicecandidate = (e) => {
        if (e.candidate && sessionId) {
            fetch('/webrtc/ice' + tokenParam, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    sessionId: sessionId,
                    candidate: e.candidate.candidate,
                    sdpMid: e.candidate.sdpMid,
                    sdpMLineIndex: e.candidate.sdpMLineIndex
                })
            }).catch(() => {});
        }
    };

    pc.ontrack = (e) => {
        console.log('ontrack fired:', e);
        if (e.streams && e.streams[0]) {
            video.srcObject = e.streams[0];
        } else if (e.track) {
            if (!video.srcObject) {
                video.srcObject = new MediaStream();
            }
            video.srcObject.addTrack(e.track);
        }
    };

    pc.oniceconnectionstatechange = () => {
        const s = pc?.iceConnectionState;
        console.log('ICE connection state:', s);
        if (s === 'connected' || s === 'completed') {
            setStatus('Live', 'ok');
            reconnectDelay = 2000;
        } else if (s === 'failed' || s === 'disconnected') {
            setStatus('Disconnected', 'err');
            scheduleReconnect();
        } else if (s === 'closed') {
            setStatus('Closed', '');
        }
    };

    // Add transceiver to receive video
    // Safari needs explicit stream in transceiver
    const transceiverInit = isSafari 
        ? { direction: 'recvonly', streams: [new MediaStream()] }
        : { direction: 'recvonly' };
    const transceiver = pc.addTransceiver('video', transceiverInit);
    console.log('Created transceiver:', transceiver);

    try {
        const offer = await pc.createOffer();
        console.log('Created offer');
        await pc.setLocalDescription(offer);

        const resp = await fetch('/webrtc/offer' + tokenParam, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ sdp: offer.sdp, type: offer.type })
        });
        const data = await resp.json();

        if (data.error) { throw new Error(data.error); }
        if (!data.sdp) { throw new Error('No SDP in response'); }

        sessionId = data.sessionId;
        await pc.setRemoteDescription(new RTCSessionDescription({ sdp: data.sdp, type: data.type }));
        console.log('Remote description set');

        // Add server ICE candidates
        if (data.iceCandidates) {
            for (const c of data.iceCandidates) {
                await pc.addIceCandidate(new RTCIceCandidate({
                    candidate: c.candidate,
                    sdpMid: c.sdpMid,
                    sdpMLineIndex: c.sdpMLineIndex
                }));
            }
        }
        
        // Debug: check transceivers after negotiation
        setTimeout(() => {
            console.log('Transceivers after negotiation:');
            pc?.getTransceivers().forEach((t, i) => {
                console.log(`Transceiver ${i}:`, {
                    mid: t.mid,
                    direction: t.direction,
                    currentDirection: t.currentDirection
                });
            });
        }, 2000);
        
        setStatus('Stream started', 'ok');
    } catch (e) {
        console.error('Stream error:', e);
        setStatus('Error: ' + e.message, 'err');
        scheduleReconnect();
    }
}

function stopStream(clear) {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (pc) { pc.close(); pc = null; }
    sessionId = null;
    video.srcObject = null;
    if (clear !== false) {
        streaming = false;
        btnStream.innerHTML = '▶ Connect';
        setStatus('Stopped', '');
    }
}

function toggleStream() {
    if (streaming) stopStream(); else startStream();
}

function scheduleReconnect() {
    if (!streaming) return;
    if (reconnectTimer) return;
    const d = Math.min(reconnectDelay, 30000);
    setStatus('Reconnecting in ' + (d/1000) + 's…', 'err');
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        if (streaming) startStream();
    }, d);
    reconnectDelay = Math.min(reconnectDelay * 1.5, 30000);
}

function toggleFullscreen() {
    const w = document.getElementById('videoWrap');
    if (!document.fullscreenElement) {
        (w.requestFullscreen || w.webkitRequestFullscreen || w.msRequestFullscreen).call(w);
    } else {
        (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    }
}

function toggleCard(header) {
    const body = header.nextElementSibling;
    const chevron = header.querySelector('.card-chevron');
    body.classList.toggle('open');
    chevron.style.transform = body.classList.contains('open') ? 'rotate(180deg)' : '';
}

function loadList(url, elId, prefix) {
    fetch(url).then(r => r.text()).then(html => {
        const el = document.getElementById(elId);
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, 'text/html');
        const items = doc.querySelectorAll('li');
        if (items.length === 0) {
            el.innerHTML = '<li class="empty">Nothing yet</li>';
        } else {
            el.innerHTML = '';
            items.forEach(item => el.appendChild(item.cloneNode(true)));
        }
    }).catch(() => {
        document.getElementById(elId).innerHTML = '<li class="empty">Could not load</li>';
    });
}
loadList('/files' + tokenParam, 'filesList', 'file');

// Auto-start
startStream();
</script>
</body>
</html>"""
    }

    private fun serveFileList(): Response {
        val files = rootDir.listFiles()?.filter { it.isFile && it.extension == "mp4" } ?: emptyList()
        val body = buildString {
            append("<html><head><title>Recordings</title>")
            append("<style>body{font-family:sans-serif;margin:2em;background:#0a0a0f;color:#e4e4e7;}a{color:#22c55e;}</style>")
            append("</head><body>")
            append("<h2>📁 Recordings</h2>")
            if (files.isEmpty()) {
                append("<p>No recordings yet.</p>")
            } else {
                append("<ul>")
                for (file in files.sortedByDescending { it.lastModified() }) {
                    val name = file.name
                    val safeName = URLEncoder.encode(name, "UTF-8")
                    val sizeMb = String.format("%.1f", file.length() / (1024.0 * 1024.0))
                    append("<li><a href=\"/file/$safeName\">$name</a> <span style='color:#71717a'>($sizeMb MB)</span></li>")
                }
                append("</ul>")
            }
            append("<p><a href=\"/\">← Back</a></p>")
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    // ---- File serving with range support ----

    private fun serveFile(file: File, session: IHTTPSession): Response {
        val fileLen = file.length()
        val rangeHeader = session.headers["range"]
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.removePrefix("bytes=")
            val parts = range.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull() ?: (fileLen - 1)
            if (start >= fileLen) {
                val response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                response.addHeader("Content-Range", "bytes */$fileLen")
                return response
            }
            val safeEnd = min(end, fileLen - 1)
            if (safeEnd < start) {
                val response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                response.addHeader("Content-Range", "bytes */$fileLen")
                return response
            }
            val contentLength = safeEnd - start + 1
            val input = FileInputStream(file)
            input.channel.position(start)
            val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, input, contentLength)
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", contentLength.toString())
            response.addHeader("Content-Range", "bytes $start-$safeEnd/$fileLen")
            return response
        }

        val input = FileInputStream(file)
        val response = newFixedLengthResponse(Response.Status.OK, mime, input, fileLen)
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    // ---- Auth & helpers ----

    private fun isSafePath(file: File, baseDir: File): Boolean {
        return try {
            file.canonicalPath.startsWith(baseDir.canonicalPath)
        } catch (_: Exception) {
            false
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (accessToken.isBlank()) return true
        val authHeader = session.headers["authorization"]
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            if (authHeader.removePrefix("Bearer ").trim() == accessToken) return true
        }
        return extractQueryToken(session.queryParameterString) == accessToken
    }

    private fun extractQueryToken(query: String?): String? {
        if (query.isNullOrBlank()) return null
        for (part in query.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.isNotEmpty() && kv[0] == "token") {
                return URLDecoder.decode(kv.getOrNull(1) ?: "", "UTF-8")
            }
        }
        return null
    }
}
