package com.watchdog.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Hardware H.264 encoder that outputs NAL units via callback.
 *
 * Uses ByteBuffer input mode — call [feedFrame] to submit raw NV12 frames
 * obtained from CameraX ImageAnalysis.  Encoded NAL units, including
 * SPS/PPS parameter sets, are delivered through [onNalUnit].
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 30
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 2 // seconds
    }

    /** Callback receiving raw H.264 NAL units (including 00 00 00 01 start code). */
    var onNalUnit: ((data: ByteArray, presentationTimeUs: Long, isConfig: Boolean) -> Unit)? = null

    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null

    @Volatile
    private var running = false

    /** Cached SPS NAL unit (with start code). */
    var sps: ByteArray? = null
        private set

    /** Cached PPS NAL unit (with start code). */
    var pps: ByteArray? = null
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
        }

        val mc = MediaCodec.createEncoderByType(MIME)
        mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mc.start()
        codec = mc
        running = true

        outputThread = Thread({
            drainLoop(mc)
        }, "H264Encoder-drain").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Encoder started ${width}x${height} @ ${bitRate / 1000}kbps (ByteBuffer mode)")
    }

    /**
     * Submit a raw NV12 frame for encoding.
     * The [data] must be width*height*3/2 bytes in NV12 (Y plane + interleaved UV).
     */
    fun feedFrame(data: ByteArray, presentationTimeUs: Long) {
        val mc = codec ?: return
        if (!running) return

        try {
            val inputIndex = mc.dequeueInputBuffer(10_000) // 10 ms timeout
            if (inputIndex >= 0) {
                val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                val size = minOf(data.size, inputBuffer.capacity())
                inputBuffer.put(data, 0, size)
                mc.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feedFrame error", e)
        }
    }

    fun stop() {
        running = false
        outputThread?.interrupt()
        outputThread?.join(2000)
        outputThread = null

        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        Log.i(TAG, "Encoder stopped")
    }

    // ---- internal ----

    private fun drainLoop(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = mc.dequeueOutputBuffer(info, 10_000) // 10 ms timeout
                if (index >= 0) {
                    val buffer = mc.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buffer.get(data)

                        val isConfig =
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                        if (isConfig) {
                            parseSpsAndPps(data)
                        }

                        onNalUnit?.invoke(data, info.presentationTimeUs, isConfig)
                    }
                    mc.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = mc.outputFormat
                    Log.i(TAG, "Output format changed: $newFormat")
                    // Extract SPS/PPS from CSD buffers when format changes
                    newFormat.getByteBuffer("csd-0")?.let { csd0 ->
                        val bytes = ByteArray(csd0.remaining())
                        csd0.get(bytes)
                        parseSpsAndPps(bytes)
                    }
                }
                // INFO_TRY_AGAIN_LATER (-1) is normal, just loop
            }
        } catch (_: InterruptedException) {
            // expected on stop
        } catch (e: Exception) {
            Log.e(TAG, "Drain loop error", e)
        }
    }

    /**
     * Parse concatenated SPS+PPS from codec config data.
     * Typical layout: 00 00 00 01 <SPS> 00 00 00 01 <PPS>
     */
    private fun parseSpsAndPps(data: ByteArray) {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i <= data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                starts.add(i)
                i += 4
            } else {
                i++
            }
        }
        if (starts.size >= 2) {
            sps = data.copyOfRange(starts[0], starts[1])
            pps = data.copyOfRange(starts[1], data.size)
            Log.i(TAG, "SPS (${sps!!.size} bytes) + PPS (${pps!!.size} bytes) cached")
        } else if (starts.size == 1) {
            // Single NAL — check type
            val nalType = data[starts[0] + 4].toInt() and 0x1F
            if (nalType == 7) {
                sps = data.copyOfRange(starts[0], data.size)
            } else if (nalType == 8) {
                pps = data.copyOfRange(starts[0], data.size)
            }
        }
    }
}
