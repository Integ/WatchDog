package com.watchdog.app

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * Lightweight RTSP server (RFC 2326) that streams H.264 video over RTP/UDP.
 *
 * Supports OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN.
 * RTP packetization follows RFC 3984 (H.264 RTP payload) with FU-A fragmentation.
 */
class RtspServer(
    private val port: Int = 8554,
    private val accessToken: String = ""
) {
    companion object {
        private const val TAG = "RtspServer"
        private const val RTSP_VERSION = "RTSP/1.0"
        private const val MAX_RTP_PAYLOAD = 1400
        private const val RTP_HEADER_SIZE = 12
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    private val clients = CopyOnWriteArrayList<ClientSession>()

    /** Set by encoder once SPS is available. */
    @Volatile
    var sps: ByteArray? = null

    /** Set by encoder once PPS is available. */
    @Volatile
    var pps: ByteArray? = null

    private var rtpSequenceNumber = 0
    private var ssrc = (System.nanoTime() and 0xFFFFFFFFL).toInt()

    fun start() {
        running = true
        serverSocket = ServerSocket(port)
        acceptThread = Thread({
            acceptLoop()
        }, "RtspServer-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "RTSP server started on port $port")
    }

    fun stop() {
        running = false
        for (c in clients) {
            c.close()
        }
        clients.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
        acceptThread?.join(2000)
        Log.i(TAG, "RTSP server stopped")
    }

    /**
     * Feed an encoded H.264 NAL unit (with 00 00 00 01 start code) to all
     * playing clients.
     */
    fun feedNalUnit(data: ByteArray, presentationTimeUs: Long, isConfig: Boolean) {
        // Strip start codes and split into individual NAL units
        val nalUnits = splitNalUnits(data)
        for (nal in nalUnits) {
            if (isConfig) {
                continue
            }
            val timestamp = (presentationTimeUs * 90 / 1000).toInt() // 90 kHz clock
            val nalType = nal[0].toInt() and 0x1F
            val isIdr = nalType == 5 // IDR slice

            for (client in clients) {
                if (client.playing) {
                    // Send SPS/PPS before IDR frames to ensure decoder initialization
                    if (isIdr) {
                        sendSpsPpsIfAvailable(client, timestamp)
                    }
                    sendNalUnitRtp(client, nal, timestamp)
                }
            }
        }
    }

    private fun sendSpsPpsIfAvailable(client: ClientSession, timestamp: Int) {
        val spsData = sps
        val ppsData = pps
        if (spsData != null) {
            val spsNal = if (spsData.size > 4 && spsData[0] == 0.toByte() &&
                spsData[1] == 0.toByte() && spsData[2] == 0.toByte() && spsData[3] == 1.toByte()
            ) {
                spsData.copyOfRange(4, spsData.size)
            } else spsData
            sendSingleNalRtp(client, spsNal, timestamp)
        }
        if (ppsData != null) {
            val ppsNal = if (ppsData.size > 4 && ppsData[0] == 0.toByte() &&
                ppsData[1] == 0.toByte() && ppsData[2] == 0.toByte() && ppsData[3] == 1.toByte()
            ) {
                ppsData.copyOfRange(4, ppsData.size)
            } else ppsData
            sendSingleNalRtp(client, ppsNal, timestamp)
        }
    }

    // ---- Accept loop ----

    private fun acceptLoop() {
        try {
            while (running) {
                val socket = serverSocket?.accept() ?: break
                Thread({
                    handleClient(socket)
                }, "RtspClient-${socket.inetAddress.hostAddress}").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (_: Exception) {
            // Server socket closed
        }
    }

    // ---- Client handling ----

    private fun handleClient(socket: Socket) {
        val session = ClientSession(socket)
        clients.add(session)
        Log.i(TAG, "Client connected: ${socket.inetAddress.hostAddress}")
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            while (running && !socket.isClosed) {
                val request = readRtspRequest(reader) ?: break
                val response = handleRequest(request, session)
                output.write(response.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            session.close()
            clients.remove(session)
            Log.i(TAG, "Client disconnected: ${socket.inetAddress.hostAddress}")
        }
    }

    private fun readRtspRequest(reader: BufferedReader): RtspRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null

        val method = parts[0]
        val uri = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }

        return RtspRequest(method, uri, headers)
    }

    private fun handleRequest(request: RtspRequest, session: ClientSession): String {
        val cseq = request.headers["CSeq"] ?: "0"

        // Token authentication (session-based: authenticate once on DESCRIBE,
        // then allow SETUP/PLAY/TEARDOWN without re-checking the token,
        // because ffmpeg sends those with the control URL which has no token).
        if (accessToken.isNotBlank() && !session.authenticated) {
            if (request.method != "OPTIONS") {
                val uri = request.uri
                val queryStart = uri.indexOf('?')
                var tokenValid = false
                if (queryStart >= 0) {
                    val query = uri.substring(queryStart + 1)
                    val params = query.split("&")
                    for (param in params) {
                        val kv = param.split("=", limit = 2)
                        if (kv.size == 2 && kv[0] == "token" && kv[1] == accessToken) {
                            tokenValid = true
                            break
                        }
                    }
                }
                if (tokenValid) {
                    session.authenticated = true
                } else {
                    return buildResponse(401, "Unauthorized", cseq)
                }
            }
        }

        return when (request.method) {
            "OPTIONS" -> handleOptions(cseq)
            "DESCRIBE" -> handleDescribe(request, cseq)
            "SETUP" -> handleSetup(request, session, cseq)
            "PLAY" -> handlePlay(session, cseq)
            "TEARDOWN" -> handleTeardown(session, cseq)
            "GET_PARAMETER" -> handleGetParameter(cseq)
            else -> buildResponse(405, "Method Not Allowed", cseq)
        }
    }

    private fun handleOptions(cseq: String): String {
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n" +
                "\r\n"
    }

    private fun handleDescribe(request: RtspRequest, cseq: String): String {
        val spsData = sps
        val ppsData = pps

        val spsB64 = if (spsData != null && spsData.size > 4) {
            Base64.encodeToString(
                spsData.copyOfRange(4, spsData.size),
                Base64.NO_WRAP
            )
        } else ""

        val ppsB64 = if (ppsData != null && ppsData.size > 4) {
            Base64.encodeToString(
                ppsData.copyOfRange(4, ppsData.size),
                Base64.NO_WRAP
            )
        } else ""

        // Profile-level-id from SPS header (3 bytes after NAL type byte)
        val profileLevelId = if (spsData != null && spsData.size > 7) {
            String.format(
                "%02X%02X%02X",
                spsData[5].toInt() and 0xFF,
                spsData[6].toInt() and 0xFF,
                spsData[7].toInt() and 0xFF
            )
        } else "42C01F" // Baseline profile, level 3.1 fallback

        val sdp = buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0\r\n")
            append("s=WatchDog\r\n")
            append("t=0 0\r\n")
            append("m=video 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            if (spsB64.isNotEmpty() && ppsB64.isNotEmpty()) {
                append("a=fmtp:96 packetization-mode=1;profile-level-id=$profileLevelId;sprop-parameter-sets=$spsB64,$ppsB64\r\n")
            } else {
                append("a=fmtp:96 packetization-mode=1;profile-level-id=$profileLevelId\r\n")
            }
            append("a=control:trackID=0\r\n")
        }

        val baseUri = request.uri.split("?")[0]
        val controlUri = if (baseUri.endsWith("/")) "${baseUri}trackID=0" else "$baseUri/trackID=0"
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Base: $baseUri\r\n" +
                "Content-Length: ${sdp.length}\r\n" +
                "\r\n" +
                sdp
    }

    private fun handleSetup(
        request: RtspRequest,
        session: ClientSession,
        cseq: String
    ): String {
        val transport = request.headers["Transport"] ?: ""

        // Check if TCP Interleaved is requested
        val isTcp = transport.contains("interleaved=")
        var tcpChannelRtp = 0
        var tcpChannelRtcp = 1

        val transportReply: String

        if (isTcp) {
            val interleavedMatch = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
            if (interleavedMatch != null) {
                tcpChannelRtp = interleavedMatch.groupValues[1].toInt()
                tcpChannelRtcp = interleavedMatch.groupValues[2].toInt()
            }
            session.isTcpInterleaved = true
            session.tcpChannelRtp = tcpChannelRtp
            session.outputStream = session.socket.getOutputStream()
            
            transportReply = "RTP/AVP/TCP;unicast;interleaved=$tcpChannelRtp-$tcpChannelRtcp"
        } else {
            // Parse client_port from Transport header for UDP
            val clientPortMatch = Regex("client_port=(\\d+)-(\\d+)").find(transport)
            val clientRtpPort: Int
            val clientRtcpPort: Int
            if (clientPortMatch != null) {
                clientRtpPort = clientPortMatch.groupValues[1].toInt()
                clientRtcpPort = clientPortMatch.groupValues[2].toInt()
            } else {
                clientRtpPort = 5000
                clientRtcpPort = 5001
            }

            session.clientRtpPort = clientRtpPort
            session.clientRtcpPort = clientRtcpPort
            session.clientAddress = session.socket.inetAddress

            // Create server-side UDP socket for sending RTP
            if (session.rtpSocket == null) {
                session.rtpSocket = DatagramSocket()
            }
            val serverRtpPort = session.rtpSocket!!.localPort
            transportReply = "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;server_port=$serverRtpPort-${serverRtpPort + 1}"
        }

        val sessionId = session.sessionId

        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Session: $sessionId\r\n" +
                "Transport: $transportReply\r\n" +
                "\r\n"
    }

    private fun handlePlay(session: ClientSession, cseq: String): String {
        session.playing = true
        Log.i(TAG, "Client PLAY: ${session.socket.inetAddress.hostAddress}")

        // Send SPS & PPS as first RTP packets so client can initialize decoder
        val spsData = sps
        val ppsData = pps
        if (spsData != null) {
            val spsNal = if (spsData.size > 4 && spsData[0] == 0.toByte() &&
                spsData[1] == 0.toByte() && spsData[2] == 0.toByte() && spsData[3] == 1.toByte()
            ) {
                spsData.copyOfRange(4, spsData.size)
            } else spsData
            sendSingleNalRtp(session, spsNal, 0)
        }
        if (ppsData != null) {
            val ppsNal = if (ppsData.size > 4 && ppsData[0] == 0.toByte() &&
                ppsData[1] == 0.toByte() && ppsData[2] == 0.toByte() && ppsData[3] == 1.toByte()
            ) {
                ppsData.copyOfRange(4, ppsData.size)
            } else ppsData
            sendSingleNalRtp(session, ppsNal, 0)
        }

        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Session: ${session.sessionId}\r\n" +
                "Range: npt=0.000-\r\n" +
                "\r\n"
    }

    private fun handleTeardown(session: ClientSession, cseq: String): String {
        session.playing = false
        session.close()
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "\r\n"
    }

    private fun handleGetParameter(cseq: String): String {
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "\r\n"
    }

    // ---- RTP packetization (RFC 3984) ----

    /**
     * Send a single NAL unit (without start code) via RTP.
     * If the NAL exceeds the MTU, FU-A fragmentation is used.
     */
    private fun sendNalUnitRtp(client: ClientSession, nal: ByteArray, timestamp: Int) {
        if (nal.size <= MAX_RTP_PAYLOAD) {
            sendSingleNalRtp(client, nal, timestamp)
        } else {
            sendFuaNalRtp(client, nal, timestamp)
        }
    }

    private fun sendSingleNalRtp(client: ClientSession, nal: ByteArray, timestamp: Int) {
        val packet = ByteArray(RTP_HEADER_SIZE + nal.size)
        writeRtpHeader(packet, marker = true, timestamp = timestamp)
        System.arraycopy(nal, 0, packet, RTP_HEADER_SIZE, nal.size)
        sendRtpData(client, packet)
    }

    private fun sendFuaNalRtp(client: ClientSession, nal: ByteArray, timestamp: Int) {
        val nalHeader = nal[0]
        val nri = nalHeader.toInt() and 0x60
        val nalType = nalHeader.toInt() and 0x1F
        val fuIndicator = (nri or 28).toByte() // FU-A type = 28

        var offset = 1 // skip original NAL header byte
        var isStart = true

        while (offset < nal.size) {
            val remaining = nal.size - offset
            val chunkSize = min(remaining, MAX_RTP_PAYLOAD - 2) // 2 bytes: FU indicator + FU header
            val isEnd = (offset + chunkSize >= nal.size)

            var fuHeader = nalType
            if (isStart) fuHeader = fuHeader or 0x80 // S bit
            if (isEnd) fuHeader = fuHeader or 0x40   // E bit

            val packet = ByteArray(RTP_HEADER_SIZE + 2 + chunkSize)
            writeRtpHeader(packet, marker = isEnd, timestamp = timestamp)
            packet[RTP_HEADER_SIZE] = fuIndicator
            packet[RTP_HEADER_SIZE + 1] = fuHeader.toByte()
            System.arraycopy(nal, offset, packet, RTP_HEADER_SIZE + 2, chunkSize)
            sendRtpData(client, packet)

            offset += chunkSize
            isStart = false
        }
    }

    @Synchronized
    private fun writeRtpHeader(packet: ByteArray, marker: Boolean, timestamp: Int) {
        val seq = rtpSequenceNumber++

        // V=2, P=0, X=0, CC=0
        packet[0] = 0x80.toByte()
        // M, PT=96
        packet[1] = if (marker) (0x80 or 96).toByte() else 96.toByte()
        // Sequence number
        packet[2] = (seq shr 8).toByte()
        packet[3] = (seq and 0xFF).toByte()
        // Timestamp
        packet[4] = (timestamp ushr 24).toByte()
        packet[5] = (timestamp ushr 16).toByte()
        packet[6] = (timestamp ushr 8).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        // SSRC
        packet[8] = (ssrc ushr 24).toByte()
        packet[9] = (ssrc ushr 16).toByte()
        packet[10] = (ssrc ushr 8).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
    }

    private fun sendRtpData(client: ClientSession, rtpPacket: ByteArray) {
        try {
            if (client.isTcpInterleaved) {
                // RFC 2326 Section 10.12 Embedded (Interleaved) Binary Data
                // Format: Magic '$' (1 byte) | Channel ID (1 byte) | Length (2 bytes) | RTP Packet
                val out = client.outputStream ?: return
                val length = rtpPacket.size
                val header = ByteArray(4)
                header[0] = 0x24.toByte() // '$'
                header[1] = client.tcpChannelRtp.toByte()
                header[2] = (length shr 8).toByte()
                header[3] = (length and 0xFF).toByte()

                // Synchronize on the client session to prevent mixed writes (requests and RTP)
                synchronized(client) {
                    out.write(header)
                    out.write(rtpPacket)
                    out.flush()
                }
            } else {
                val address = client.clientAddress ?: return
                val datagram = DatagramPacket(rtpPacket, rtpPacket.size, address, client.clientRtpPort)
                client.rtpSocket?.send(datagram)
            }
        } catch (_: Exception) {
            // Send failure (client may have disconnected)
            client.playing = false
        }
    }

    // ---- helpers ----

    /**
     * Split raw H.264 data (which may contain multiple NAL units separated by
     * 00 00 00 01 start codes) into individual NAL units (without start codes).
     */
    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i <= data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                starts.add(i + 4) // index of first byte after start code
                i += 4
            } else {
                i++
            }
        }
        if (starts.isEmpty()) {
            // No start code found — treat entire data as single NAL
            return listOf(data)
        }
        val result = mutableListOf<ByteArray>()
        for (j in starts.indices) {
            val nalStart = starts[j]
            val nalEnd = if (j + 1 < starts.size) starts[j + 1] - 4 else data.size
            if (nalEnd > nalStart) {
                result.add(data.copyOfRange(nalStart, nalEnd))
            }
        }
        return result
    }

    private fun buildResponse(code: Int, reason: String, cseq: String): String {
        return "$RTSP_VERSION $code $reason\r\n" +
                "CSeq: $cseq\r\n" +
                "\r\n"
    }

    // ---- data classes ----

    private data class RtspRequest(
        val method: String,
        val uri: String,
        val headers: Map<String, String>
    )

    class ClientSession(val socket: Socket) {
        val sessionId: String = (System.nanoTime() / 1000).toString()
        var isTcpInterleaved: Boolean = false
        var tcpChannelRtp: Int = 0
        var outputStream: OutputStream? = null

        var clientRtpPort: Int = 0
        var clientRtcpPort: Int = 0
        var clientAddress: InetAddress? = null
        var rtpSocket: DatagramSocket? = null
        var authenticated: Boolean = false

        @Volatile
        var playing: Boolean = false

        fun close() {
            playing = false
            try {
                rtpSocket?.close()
            } catch (_: Exception) {
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
