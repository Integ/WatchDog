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

    /** Callback when SPS/PPS are available (called once after format change). */
    var onSpsPpsReady: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null

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

                        onNalUnit?.invoke(data, info.presentationTimeUs, isConfig)
                    }
                    mc.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = mc.outputFormat
                    Log.i(TAG, "Output format changed: $newFormat")
                    // Extract SPS/PPS from CSD buffers when format changes
                    var spsBytes: ByteArray? = null
                    var ppsBytes: ByteArray? = null
                    newFormat.getByteBuffer("csd-0")?.let { csd0 ->
                        val bytes = ByteArray(csd0.remaining())
                        csd0.get(bytes)
                        // csd-0 typically contains SPS with start code
                        sps = bytes
                        spsBytes = bytes
                        Log.i(TAG, "SPS cached from csd-0 (${bytes.size} bytes)")
                    }
                    newFormat.getByteBuffer("csd-1")?.let { csd1 ->
                        val bytes = ByteArray(csd1.remaining())
                        csd1.get(bytes)
                        // csd-1 typically contains PPS with start code
                        pps = bytes
                        ppsBytes = bytes
                        Log.i(TAG, "PPS cached from csd-1 (${bytes.size} bytes)")
                    }
                    if (spsBytes != null && ppsBytes != null) {
                        onSpsPpsReady?.invoke(spsBytes!!, ppsBytes!!)
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

    // parseSpsAndPps removed as it's no longer reliable/needed since we use csd-0/csd-1 directly
}
