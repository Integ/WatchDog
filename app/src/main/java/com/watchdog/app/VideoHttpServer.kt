package com.watchdog.app

import fi.iki.elonen.NanoHTTPD
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
    private val rtspUrl: String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        return when (session.uri) {
            "/" -> serveIndex()
            "/video" -> serveRtspInfo()
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
                    if (isSafePath(target)) {
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
                    if (isSafePath(target)) {
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

    private fun serveIndex(): Response {
        val body = buildString {
            append("<html><head><title>WatchDog</title></head><body>")
            append("<h2>WatchDog</h2>")
            append("<ul>")
            append("<li><a href=\"${withToken("/snapshot")}\">Latest Snapshot</a></li>")
            append("<li><a href=\"${withToken("/snapshots")}\">All Snapshots</a></li>")
            append("<li><a href=\"${withToken("/latest")}\">Latest Recording</a></li>")
            append("<li><a href=\"${withToken("/files")}\">All Recordings</a></li>")
            append("<li><a href=\"${withToken("/video")}\">Live Video (RTSP)</a></li>")
            append("</ul>")
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    private fun serveRtspInfo(): Response {
        val body = buildString {
            append("<html><head><title>WatchDog - Live RTSP</title>")
            append("<style>body{font-family:sans-serif;margin:2em;}code{background:#eee;padding:4px 8px;border-radius:4px;}</style>")
            append("</head><body>")
            append("<h2>Live Video Stream (RTSP)</h2>")
            append("<p>The live video stream is available via RTSP at:</p>")
            append("<p><code>$rtspUrl</code></p>")
            append("<h3>How to watch</h3>")
            append("<ul>")
            append("<li><b>VLC</b>: Media → Open Network Stream → paste the URL above</li>")
            append("<li><b>ffplay</b>: <code>ffplay ${'$'}rtspUrl</code></li>")
            append("<li><b>ffmpeg</b>: <code>ffmpeg -i ${'$'}rtspUrl -c copy output.mp4</code></li>")
            append("</ul>")

            append("<h3>Integration with Homebridge (Apple HomeKit)</h3>")
            append("<p>To view this camera in the Apple Home app via Homebridge, install the <code>homebridge-camera-ffmpeg</code> plugin and add the following configuration to your <b>config.json</b>:</p>")
            append("<pre style=\"background:#eee;padding:10px;border-radius:4px;overflow-x:auto;\"><code>")
            
            // Re-format the base rtsp URL to ensure it shows exactly what needs to be pasted
            // including potential token query strings
            val homebridgeConfig = """
{
  "platforms": [
    {
      "platform": "Camera-ffmpeg",
      "cameras": [
        {
          "name": "WatchDog Camera",
          "videoConfig": {
            "source": "-rtsp_transport tcp -i ${'$'}rtspUrl",
            "vcodec": "copy",
            "audio": false
          }
        }
      ]
    }
  ]
}
            """.trimIndent()
            append(homebridgeConfig)
            append("</code></pre>")

            append("<p><a href=\"${'$'}${withToken("/")}\">← Back</a></p>")
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    private fun serveFileList(): Response {
        val files = rootDir.listFiles()?.filter { it.isFile && it.extension == "mp4" } ?: emptyList()
        val body = buildString {
            append("<html><head><title>Recordings</title></head><body>")
            append("<h2>Recordings</h2>")
            if (files.isEmpty()) {
                append("<p>No recordings yet.</p>")
            } else {
                append("<ul>")
                for (file in files.sortedByDescending { it.lastModified() }) {
                    val name = file.name
                    val safeName = URLEncoder.encode(name, "UTF-8")
                    append("<li><a href=\"${withToken("/file/$safeName")}\">$name</a> (${file.length() / 1024} KB)</li>")
                }
                append("</ul>")
            }
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    }

    private fun serveSnapshotList(): Response {
        val files = snapshotDir.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: emptyList()
        val body = buildString {
            append("<html><head><title>Snapshots</title>")
            append("<style>body{font-family:sans-serif;margin:2em;}img{max-width:400px;}</style>")
            append("</head><body>")
            append("<h2>Snapshots</h2>")
            append("<p><a href=\"${'$'}${withToken("/snapshot")}\">Latest Snapshot</a></p>")
            if (files.isEmpty()) {
                append("<p>No snapshots yet.</p>")
            } else {
                append("<ul>")
                for (file in files.sortedByDescending { it.lastModified() }) {
                    val name = file.name
                    val safeName = URLEncoder.encode(name, "UTF-8")
                    append("<li><a href=\"${withToken("/snapshot/$safeName")}\">$name</a> (${file.length() / 1024} KB)</li>")
                }
                append("</ul>")
            }
            append("<p><a href=\"${'$'}${withToken("/")}\">← Back</a></p>")
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

    private fun isSafePath(file: File): Boolean {
        return try {
            val rootPath = rootDir.canonicalPath
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

    private fun tokenQuery(): String {
        if (accessToken.isBlank()) {
            return ""
        }
        return "token=${URLEncoder.encode(accessToken, "UTF-8")}"
    }

    private fun withToken(path: String): String {
        val tokenParam = tokenQuery()
        return if (tokenParam.isBlank()) path else "$path?$tokenParam"
    }
}
