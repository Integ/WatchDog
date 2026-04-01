package com.watchdog.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Hardware H.264 encoder that outputs NAL units via callback.
 *
 * The encoder chooses a YUV420 input layout that is actually supported by the
 * device codec so we can match CameraX frame conversion to the codec's
 * expectation.
 */
class H264Encoder(
    val width: Int,
    val height: Int,
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 30
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1
    }

    enum class InputMode {
        YUV420_PLANAR,
        YUV420_SEMIPLANAR
    }

    var onNalUnit: ((data: ByteArray, presentationTimeUs: Long, isConfig: Boolean) -> Unit)? = null
    var onSpsPpsReady: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null
    var onVideoFormatChanged: ((width: Int, height: Int, frameRate: Int) -> Unit)? = null

    val inputMode: InputMode
        get() = selectedInputMode

    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null

    @Volatile
    private var running = false

    private var selectedInputMode = InputMode.YUV420_PLANAR

    var sps: ByteArray? = null
        private set

    var pps: ByteArray? = null
        private set

    fun start() {
        val mc = MediaCodec.createEncoderByType(MIME)
        val capabilities = mc.codecInfo.getCapabilitiesForType(MIME)
        val colorFormat = chooseColorFormat(capabilities.colorFormats)
        selectedInputMode = when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> InputMode.YUV420_PLANAR
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> InputMode.YUV420_SEMIPLANAR
            else -> error("Unsupported selected color format: $colorFormat")
        }

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        }

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
        Log.i(
            TAG,
            "Encoder started ${width}x${height} @ ${bitRate / 1000}kbps, colorFormat=$colorFormat, inputMode=$selectedInputMode"
        )
    }

    fun feedFrame(data: ByteArray, presentationTimeUs: Long) {
        val mc = codec ?: return
        if (!running) return

        try {
            val inputIndex = mc.dequeueInputBuffer(10_000)
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
        val mc = codec
        codec = null
        running = false
        outputThread?.interrupt()
        outputThread?.join(2000)
        outputThread = null
        onNalUnit = null
        onSpsPpsReady = null
        sps = null
        pps = null

        try {
            mc?.stop()
        } catch (_: Exception) {
        }
        try {
            mc?.release()
        } catch (_: Exception) {
        }
        Log.i(TAG, "Encoder stopped")
    }

    private fun chooseColorFormat(colorFormats: IntArray): Int {
        val preferredFormats = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )

        for (preferred in preferredFormats) {
            if (colorFormats.contains(preferred)) {
                return preferred
            }
        }

        throw IllegalStateException(
            "No supported YUV420 color format found. Supported=${colorFormats.joinToString()}"
        )
    }

    private fun drainLoop(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = mc.dequeueOutputBuffer(info, 10_000)
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
                    val actualWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val actualHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    val actualFrameRate = newFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                    onVideoFormatChanged?.invoke(actualWidth, actualHeight, actualFrameRate)
                    var spsBytes: ByteArray? = null
                    var ppsBytes: ByteArray? = null
                    newFormat.getByteBuffer("csd-0")?.let { csd0 ->
                        val bytes = ByteArray(csd0.remaining())
                        csd0.get(bytes)
                        sps = bytes
                        spsBytes = bytes
                        Log.i(TAG, "SPS cached from csd-0 (${bytes.size} bytes)")
                    }
                    newFormat.getByteBuffer("csd-1")?.let { csd1 ->
                        val bytes = ByteArray(csd1.remaining())
                        csd1.get(bytes)
                        pps = bytes
                        ppsBytes = bytes
                        Log.i(TAG, "PPS cached from csd-1 (${bytes.size} bytes)")
                    }
                    val readySps = spsBytes
                    val readyPps = ppsBytes
                    if (readySps != null && readyPps != null) {
                        onSpsPpsReady?.invoke(readySps, readyPps)
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "Drain loop error", e)
        }
    }
}
