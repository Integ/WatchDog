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
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
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
        private const val MAX_CLIENTS = 4
        private const val MAX_HEADERS = 64
        private const val CLIENT_TIMEOUT_MS = 90_000
        private const val TCP_QUEUE_CAPACITY = 256
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
            if (isConfig || nal.isEmpty()) {
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
            sendSingleNalRtp(client, stripStartCode(spsData), timestamp)
        }
        if (ppsData != null) {
            sendSingleNalRtp(client, stripStartCode(ppsData), timestamp)
        }
    }

    // ---- Accept loop ----

    private fun acceptLoop() {
        try {
            while (running) {
                val socket = serverSocket?.accept() ?: break
                if (clients.size >= MAX_CLIENTS) {
                    Log.w(TAG, "Rejecting RTSP client: client limit reached")
                    socket.close()
                    continue
                }
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = CLIENT_TIMEOUT_MS
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
                synchronized(session) {
                    output.write(response.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                if (request.method == "PLAY") {
                    session.playing = true
                    Log.i(TAG, "Client PLAY: ${socket.inetAddress.hostAddress}")
                }
            }
        } catch (_: SocketTimeoutException) {
            Log.i(TAG, "Client timed out: ${socket.inetAddress.hostAddress}")
        } catch (exc: Exception) {
            if (running && !socket.isClosed) {
                Log.w(TAG, "RTSP client error: ${socket.inetAddress.hostAddress}", exc)
            }
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
        var headerCount = 0
        while (headerCount < MAX_HEADERS) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
            headerCount++
        }

        return RtspRequest(method, uri, headers)
    }

    private fun handleRequest(request: RtspRequest, session: ClientSession): String {
        val cseq = request.header("CSeq") ?: "0"

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
        val spsData = sps?.let(::stripStartCode)
        val ppsData = pps?.let(::stripStartCode)

        val spsB64 = if (!spsData.isNullOrEmpty()) {
            Base64.encodeToString(spsData, Base64.NO_WRAP)
        } else ""

        val ppsB64 = if (!ppsData.isNullOrEmpty()) {
            Base64.encodeToString(ppsData, Base64.NO_WRAP)
        } else ""

        // Profile-level-id from SPS header (3 bytes after NAL type byte)
        val profileLevelId = if (spsData != null && spsData.size >= 4) {
            String.format(
                "%02X%02X%02X",
                spsData[1].toInt() and 0xFF,
                spsData[2].toInt() and 0xFF,
                spsData[3].toInt() and 0xFF
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
        val transport = request.header("Transport") ?: ""

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
            session.startTcpSender()

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
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Session: ${session.sessionId}\r\n" +
                "Range: npt=0.000-\r\n" +
                "\r\n"
    }

    private fun handleTeardown(session: ClientSession, cseq: String): String {
        session.playing = false
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
        writeRtpHeader(client, packet, marker = true, timestamp = timestamp)
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
            writeRtpHeader(client, packet, marker = isEnd, timestamp = timestamp)
            packet[RTP_HEADER_SIZE] = fuIndicator
            packet[RTP_HEADER_SIZE + 1] = fuHeader.toByte()
            System.arraycopy(nal, offset, packet, RTP_HEADER_SIZE + 2, chunkSize)
            sendRtpData(client, packet)

            offset += chunkSize
            isStart = false
        }
    }

    private fun writeRtpHeader(
        client: ClientSession,
        packet: ByteArray,
        marker: Boolean,
        timestamp: Int
    ) {
        val seq = client.nextSequenceNumber()
        val ssrc = client.ssrc

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
                if (!client.enqueueTcpPacket(rtpPacket)) {
                    Log.w(TAG, "Closing slow RTSP/TCP client")
                    client.close()
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
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i <= data.size - 3) {
            val startCodeLength = when {
                i <= data.size - 4 &&
                    data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte() -> 4
                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeLength > 0) {
                starts.add(i to startCodeLength)
                i += startCodeLength
                continue
            }
            i++
        }
        if (starts.isEmpty()) {
            return splitLengthPrefixedNalUnits(data)
        }
        val result = mutableListOf<ByteArray>()
        for (j in starts.indices) {
            val nalStart = starts[j].first + starts[j].second
            val nalEnd = if (j + 1 < starts.size) starts[j + 1].first else data.size
            if (nalEnd > nalStart) {
                result.add(data.copyOfRange(nalStart, nalEnd))
            }
        }
        return result
    }

    private fun splitLengthPrefixedNalUnits(data: ByteArray): List<ByteArray> {
        if (data.size < 5) {
            return listOf(data)
        }

        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val length =
                ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (length <= 0 || offset + length > data.size) {
                return listOf(data)
            }
            result.add(data.copyOfRange(offset, offset + length))
            offset += length
        }
        return if (offset == data.size && result.isNotEmpty()) result else listOf(data)
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        val offset = when {
            data.size >= 4 &&
                data[0] == 0.toByte() &&
                data[1] == 0.toByte() &&
                data[2] == 0.toByte() &&
                data[3] == 1.toByte() -> 4
            data.size >= 3 &&
                data[0] == 0.toByte() &&
                data[1] == 0.toByte() &&
                data[2] == 1.toByte() -> 3
            else -> 0
        }
        return if (offset == 0) data else data.copyOfRange(offset, data.size)
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
    ) {
        fun header(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }
    }

    class ClientSession(val socket: Socket) {
        val sessionId: String = (System.nanoTime() / 1000).toString()
        val ssrc: Int = (System.nanoTime() xor socket.hashCode().toLong()).toInt()
        private var rtpSequenceNumber: Int = 0
        var isTcpInterleaved: Boolean = false
        var tcpChannelRtp: Int = 0
        var outputStream: OutputStream? = null
        private val tcpQueue = ArrayBlockingQueue<ByteArray>(TCP_QUEUE_CAPACITY)
        private var tcpSenderThread: Thread? = null

        var clientRtpPort: Int = 0
        var clientRtcpPort: Int = 0
        var clientAddress: InetAddress? = null
        var rtpSocket: DatagramSocket? = null
        var authenticated: Boolean = false

        @Volatile
        var playing: Boolean = false

        @Synchronized
        fun nextSequenceNumber(): Int = rtpSequenceNumber++

        fun startTcpSender() {
            if (tcpSenderThread != null) {
                return
            }
            tcpSenderThread = Thread({
                try {
                    while (!socket.isClosed) {
                        val packet = tcpQueue.take()
                        val out = outputStream ?: break
                        val length = packet.size
                        val header = byteArrayOf(
                            0x24,
                            tcpChannelRtp.toByte(),
                            (length shr 8).toByte(),
                            length.toByte()
                        )
                        synchronized(this) {
                            out.write(header)
                            out.write(packet)
                            out.flush()
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    close()
                }
            }, "RtspClient-sender-${socket.inetAddress.hostAddress}").apply {
                isDaemon = true
                start()
            }
        }

        fun enqueueTcpPacket(packet: ByteArray): Boolean = tcpQueue.offer(packet)

        fun close() {
            playing = false
            tcpSenderThread?.interrupt()
            tcpSenderThread = null
            tcpQueue.clear()
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
