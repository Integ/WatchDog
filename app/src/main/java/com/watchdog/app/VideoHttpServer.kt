package com.watchdog.app

import fi.iki.elonen.NanoHTTPD
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.min

class VideoHttpServer(
    port: Int,
    private val rootDir: File,
    private val snapshotDir: File,
    private val accessToken: String,
    private val latestProvider: () -> File?,
    private val latestSnapshotProvider: () -> File?,
    private val webRtcService: WebRtcService
) : NanoHTTPD(port) {

    private var pendingIceCandidates = mutableListOf<IceCandidate>()
    private var pendingAnswer: org.json.JSONObject? = null
    private val answerLock = Object()

    override fun serve(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }

        return when (session.uri) {
            "/" -> serveIndex()
            "/webrtc/offer" -> handleWebRtcOffer(session)
            "/webrtc/answer" -> handleWebRtcAnswer(session)
            "/webrtc/ice" -> handleIceCandidate(session)
            "/webrtc/ice-candidates" -> getPendingIceCandidates()
            "/latest" -> {
                val latest = latestProvider()
                if (latest == null) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No recordings yet")
                } else {
                    serveFile(latest, session)
                }
            }
            "/snapshot" -> {
                val latest = latestSnapshotProvider()
                if (latest == null) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No snapshots yet")
                } else {
                    serveFile(latest, session)
                }
            }
            "/snapshots" -> serveSnapshotList()
            "/files" -> serveFileList()
            else -> {
                if (session.uri.startsWith("/file/")) {
                    val name = session.uri.removePrefix("/file/")
                    val decoded = URLDecoder.decode(name, "UTF-8")
                    val target = File(rootDir, decoded)
                    if (isSafePath(target, rootDir)) {
                        if (target.exists() && target.isFile) {
                            serveFile(target, session)
                        } else {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                        }
                    } else {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
                    }
                } else if (session.uri.startsWith("/snapshot/")) {
                    val name = session.uri.removePrefix("/snapshot/")
                    val decoded = URLDecoder.decode(name, "UTF-8")
                    val target = File(snapshotDir, decoded)
                    if (isSafePath(target, snapshotDir)) {
                        if (target.exists() && target.isFile) {
                            serveFile(target, session)
                        } else {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Snapshot not found")
                        }
                    } else {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            }
        }
    }

    private fun handleWebRtcOffer(session: IHTTPSession): Response {
        return try {
            val params = HashMap<String, String>()
            session.parseBody(params)
            val body = params["postData"] ?: ""

            val sdpJson = org.json.JSONObject(body)
            val sdp = sdpJson.getString("sdp")
            val type = sdpJson.getString("type")

            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sdp
            )

            // Set up callback for local description
            webRtcService.onLocalSessionDescription = { answer ->
                synchronized(answerLock) {
                    pendingAnswer = org.json.JSONObject().apply {
                        put("sdp", answer.description)
                        put("type", answer.type.canonicalForm())
                    }
                    answerLock.notifyAll()
                }
            }

            webRtcService.setRemoteDescription(
                sessionDescription,
                onSuccess = {
                    webRtcService.createAnswer()
                },
                onFailure = { error ->
                    android.util.Log.e("WebRTC", "Failed to set remote description: $error")
                }
            )

            // Wait for answer with timeout
            synchronized(answerLock) {
                var waitCount = 0
                while (pendingAnswer == null && waitCount < 50) {
                    answerLock.wait(100)
                    waitCount++
                }
            }

            val answer = pendingAnswer
            pendingAnswer = null

            if (answer != null) {
                newFixedLengthResponse(Response.Status.OK, "application/json", answer.toString())
            } else {
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"processing\"}")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebRTC", "Error handling offer", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleWebRtcAnswer(session: IHTTPSession): Response {
        return try {
            val params = HashMap<String, String>()
            session.parseBody(params)
            val body = params["postData"] ?: ""

            val sdpJson = org.json.JSONObject(body)
            val sdp = sdpJson.getString("sdp")
            val type = sdpJson.getString("type")

            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sdp
            )

            webRtcService.setRemoteDescription(
                sessionDescription,
                onSuccess = {
                    pendingIceCandidates.forEach { candidate ->
                        webRtcService.addIceCandidate(candidate)
                    }
                    pendingIceCandidates.clear()
                },
                onFailure = { error ->
                    android.util.Log.e("WebRTC", "Failed to set remote description: $error")
                }
            )

            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
        } catch (e: Exception) {
            android.util.Log.e("WebRTC", "Error handling answer", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleIceCandidate(session: IHTTPSession): Response {
        return try {
            val params = HashMap<String, String>()
            session.parseBody(params)
            val body = params["postData"] ?: ""

            val candidateJson = org.json.JSONObject(body)
            val candidate = candidateJson.getString("candidate")
            val sdpMid = candidateJson.optString("sdpMid", "0")
            val sdpMLineIndex = candidateJson.getInt("sdpMLineIndex")

            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

            webRtcService.addIceCandidate(iceCandidate)

            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
        } catch (e: Exception) {
            android.util.Log.e("WebRTC", "Error handling ICE candidate", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun getPendingIceCandidates(): Response {
        val candidates = pendingIceCandidates.map { candidate ->
            org.json.JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            org.json.JSONArray(candidates).toString()
        )
    }

    private fun serveIndex(): Response {
        val ip = getLocalIpAddress() ?: "0.0.0.0"
        val body = buildString {
            append("""<!DOCTYPE html>
<html>
<head>
    <title>WatchDog Camera</title>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
        .container { max-width: 800px; margin: 0 auto; }
        h1 { color: #333; }
        .card { background: white; border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
        .video-container { position: relative; background: #000; border-radius: 8px; overflow: hidden; }
        video { width: 100%; display: block; }
        .status { padding: 10px; background: #e3f2fd; border-radius: 4px; margin-bottom: 10px; }
        .status.connected { background: #e8f5e9; }
        .status.error { background: #ffebee; }
        .btn { display: inline-block; padding: 10px 20px; background: #2196F3; color: white; text-decoration: none; border-radius: 6px; margin: 5px; }
        .btn:hover { background: #1976D2; }
        ul { list-style: none; padding: 0; }
        li { padding: 8px 0; border-bottom: 1px solid #eee; }
        a { color: #2196F3; }
        img { max-width: 100%; border-radius: 8px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🐕 WatchDog Camera</h1>
        
        <div class="card">
            <h2>📹 Live Video</h2>
            <div id="status" class="status">Connecting...</div>
            <div class="video-container">
                <video id="video" autoplay playsinline></video>
            </div>
            <button class="btn" onclick="startStream()">Start Stream</button>
            <button class="btn" onclick="stopStream()">Stop Stream</button>
        </div>

        <div class="card">
            <h2>📸 Latest Snapshot</h2>
            <img id="snapshot-img" src="/snapshot?token=" alt="No snapshot" onerror="this.style.display='none'">
            <p><a href="/snapshot">View Snapshot</a></p>
        </div>

        <div class="card">
            <h2>📁 Recordings</h2>
            <ul id="files-list"></ul>
            <p><a href="/files">View All Recordings</a></p>
        </div>

        <div class="card">
            <h2>📷 All Snapshots</h2>
            <ul id="snapshots-list"></ul>
            <p><a href="/snapshots">View All Snapshots</a></p>
        </div>
    </div>

    <script>
        const pcConfig = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };
        
        let pc = null;
        let dc = null;
        const video = document.getElementById('video');
        const statusEl = document.getElementById('status');
        let ws = null;

        async function startStream() {
            statusEl.textContent = 'Connecting...';
            statusEl.className = 'status';
            
            pc = new RTCPeerConnection(pcConfig);
            
            pc.onicecandidate = (e) => {
                if (e.candidate) {
                    fetch('/webrtc/ice', {
                        method: 'POST',
                        body: JSON.stringify({
                            candidate: e.candidate.candidate,
                            sdpMid: e.candidate.sdpMid,
                            sdpMLineIndex: e.candidate.sdpMLineIndex
                        })
                    });
                }
            };
            
            pc.ontrack = (e) => {
                video.srcObject = e.streams[0];
                statusEl.textContent = 'Connected!';
                statusEl.className = 'status connected';
            };
            
            pc.oniceconnectionstatechange = () => {
                if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected') {
                    statusEl.textContent = 'Connection failed';
                    statusEl.className = 'status error';
                }
            };

            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);

            try {
                const response = await fetch('/webrtc/offer', {
                    method: 'POST',
                    body: JSON.stringify({
                        sdp: offer.sdp,
                        type: offer.type
                    })
                });
                
                const data = await response.json();
                
                if (data.sdp) {
                    await pc.setRemoteDescription(new RTCSessionDescription({
                        sdp: data.sdp,
                        type: data.type
                    }));
                    statusEl.textContent = 'Stream started';
                    statusEl.className = 'status connected';
                } else {
                    statusEl.textContent = data.status || 'Processing...';
                }
            } catch (e) {
                statusEl.textContent = 'Error: ' + e.message;
                statusEl.className = 'status error';
            }
        }

        function stopStream() {
            if (pc) {
                pc.close();
                pc = null;
            }
            video.srcObject = null;
            statusEl.textContent = 'Stopped';
            statusEl.className = 'status';
        }

        // Load file lists
        fetch('/files').then(r => r.text()).then(html => {
            const parser = new DOMParser();
            const doc = parser.parseFromString(html, 'text/html');
            const items = doc.querySelectorAll('li');
            items.forEach(item => {
                document.getElementById('files-list').appendChild(item);
            });
        });
        
        fetch('/snapshots').then(r => r.text()).then(html => {
            const parser = new DOMParser();
            const doc = parser.parseFromString(html, 'text/html');
            const items = doc.querySelectorAll('li');
            items.forEach(item => {
                document.getElementById('snapshots-list').appendChild(item);
            });
        });

        // Auto start
        startStream();
    </script>
</body>
</html>""")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    private fun serveFileList(): Response {
        val files = rootDir.listFiles()?.filter { it.isFile && it.extension == "mp4" } ?: emptyList()
        val body = buildString {
            append("<html><head><title>Recordings</title>")
            append("<style>body{font-family:sans-serif;margin:2em;}a{color:#2196F3;}</style>")
            append("</head><body>")
            append("<h2>📁 Recordings</h2>")
            if (files.isEmpty()) {
                append("<p>No recordings yet.</p>")
            } else {
                append("<ul>")
                for (file in files.sortedByDescending { it.lastModified() }) {
                    val name = file.name
                    val safeName = URLEncoder.encode(name, "UTF-8")
                    append("<li><a href=\"/file/$safeName\">$name</a> (${file.length() / 1024} KB)</li>")
                }
                append("</ul>")
            }
            append("<p><a href=\"/\">← Back</a></p>")
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    private fun serveSnapshotList(): Response {
        val files = snapshotDir.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: emptyList()
        val body = buildString {
            append("<html><head><title>Snapshots</title>")
            append("<style>body{font-family:sans-serif;margin:2em;}img{max-width:300px;margin:10px;}a{color:#2196F3;}</style>")
            append("</head><body>")
            append("<h2>📷 Snapshots</h2>")
            append("<p><a href=\"/snapshot\">Latest Snapshot</a></p>")
            if (files.isEmpty()) {
                append("<p>No snapshots yet.</p>")
            } else {
                append("<ul>")
                for (file in files.sortedByDescending { it.lastModified() }) {
                    val name = file.name
                    val safeName = URLEncoder.encode(name, "UTF-8")
                    append("<li><a href=\"/snapshot/$safeName\">$name</a> (${file.length() / 1024} KB)</li>")
                }
                append("</ul>")
            }
            append("<p><a href=\"/\">← Back</a></p>")
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

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

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mime,
                input,
                contentLength
            )
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

    private fun isSafePath(file: File, baseDir: File): Boolean {
        return try {
            val rootPath = baseDir.canonicalPath
            val targetPath = file.canonicalPath
            targetPath.startsWith(rootPath)
        } catch (exc: Exception) {
            false
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (accessToken.isBlank()) {
            return true
        }
        val authHeader = session.headers["authorization"]
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.removePrefix("Bearer ").trim()
            if (token == accessToken) {
                return true
            }
        }
        val queryToken = extractQueryToken(session.queryParameterString)
        return queryToken == accessToken
    }

    private fun extractQueryToken(query: String?): String? {
        if (query.isNullOrBlank()) {
            return null
        }
        val parts = query.split("&")
        for (part in parts) {
            val kv = part.split("=", limit = 2)
            if (kv.isNotEmpty() && kv[0] == "token") {
                val raw = kv.getOrNull(1) ?: ""
                return URLDecoder.decode(raw, "UTF-8")
            }
        }
        return null
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
