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
    private val accessToken: String,
    private val latestProvider: () -> File?,
    private val mjpegFrameProvider: () -> ByteArray?
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        return when (session.uri) {
            "/" -> serveIndex()
            "/video" -> serveMjpegStream()
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
                    if (isSafePath(target)) {
                        if (target.exists() && target.isFile) {
                            serveFile(target, session)
                        } else {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
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
            append("<li><a href=\"${withToken("/latest")}\">Latest Recording</a></li>")
            append("<li><a href=\"${withToken("/files")}\">All Recordings</a></li>")
            append("<li><a href=\"${withToken("/video")}\">Live MJPEG</a></li>")
            append("</ul>")
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

    private fun serveFile(file: File, session: IHTTPSession): Response {
        val fileLen = file.length()
        val rangeHeader = session.headers["range"]
        val mime = "video/mp4"

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

    private fun serveMjpegStream(): Response {
        val boundary = "frame"
        val stream = createMjpegStream(boundary)
        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            stream
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Connection", "close")
        return response
    }

    private fun createMjpegStream(boundary: String): java.io.InputStream {
        val output = java.io.PipedOutputStream()
        val input = java.io.PipedInputStream(output)
        val writer = java.io.BufferedOutputStream(output, 16 * 1024)
        val thread = Thread {
            try {
                while (true) {
                    val frame = mjpegFrameProvider()
                    if (frame == null) {
                        Thread.sleep(100)
                        continue
                    }
                    writer.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    writer.write("Content-Type: image/jpeg\r\n".toByteArray(Charsets.UTF_8))
                    writer.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
                    writer.write(frame)
                    writer.write("\r\n".toByteArray(Charsets.UTF_8))
                    writer.flush()
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
                // Client disconnected or stream closed.
            } finally {
                try {
                    writer.close()
                } catch (_: Exception) {
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return input
    }
}
