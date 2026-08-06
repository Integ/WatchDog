package com.watchdog.app

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecordingPhase {
    IDLE,
    STARTING,
    RECORDING
}

data class RecordingState(
    val phase: RecordingPhase,
    val message: String? = null
)

/**
 * Writes the H.264 stream that is already produced for RTSP into an MP4 file.
 * No camera use case or encoder is added, so recording only costs file I/O and
 * MediaMuxer container work.
 */
class GalleryVideoRecorder(
    context: Context,
    private val onStateChanged: (RecordingState) -> Unit
) {
    companion object {
        private const val TAG = "GalleryVideoRecorder"
        private const val MIME_TYPE = "video/mp4"
        private const val ALBUM_NAME = "WatchDog"
        private const val SEGMENT_DURATION_US = 15L * 60L * 1_000_000L
        private const val SEGMENT_MAX_BYTES = 256L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 8_000_000_000L
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private var latestVideoFormat: MediaFormat? = null
    private var session: RecordingSession? = null

    val isActive: Boolean
        get() = synchronized(lock) { session != null }

    fun setVideoFormat(format: MediaFormat) {
        synchronized(lock) {
            latestVideoFormat = format
            val activeSession = session ?: return
            if (!activeSession.muxerStarted) {
                runCatching { startMuxerLocked(activeSession, format) }
                    .onFailure { failSessionLocked(activeSession, it) }
            }
        }
    }

    fun clearVideoFormat() {
        synchronized(lock) {
            latestVideoFormat = null
        }
    }

    fun start(): Boolean {
        synchronized(lock) {
            if (session != null) {
                return false
            }

            return try {
                runCatching { enforceStorageLimit() }
                    .onFailure { Log.w(TAG, "Unable to prune old recordings before start", it) }
                val newSession = createSession()
                session = newSession
                latestVideoFormat?.let { startMuxerLocked(newSession, it) }
                onStateChanged(
                    RecordingState(
                        RecordingPhase.STARTING,
                        "Preparing circular recording…"
                    )
                )
                true
            } catch (exc: Exception) {
                Log.e(TAG, "Unable to start recording", exc)
                session?.let { discardSessionLocked(it) }
                session = null
                onStateChanged(
                    RecordingState(
                        RecordingPhase.IDLE,
                        "Unable to start recording: ${exc.message ?: "storage error"}"
                    )
                )
                false
            }
        }
    }

    fun writeSample(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        synchronized(lock) {
            var activeSession = session ?: return
            if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || data.isEmpty()) {
                return
            }

            try {
                if (!activeSession.muxerStarted) {
                    val format = latestVideoFormat ?: return
                    startMuxerLocked(activeSession, format)
                }

                val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (activeSession.sampleCount == 0 && !isKeyFrame) {
                    return
                }

                if (shouldRotate(activeSession, presentationTimeUs, data.size, isKeyFrame)) {
                    activeSession = rotateSessionLocked(activeSession) ?: return
                }

                if (activeSession.firstPresentationTimeUs == Long.MIN_VALUE) {
                    activeSession.firstPresentationTimeUs = presentationTimeUs
                }
                var normalizedTimeUs =
                    (presentationTimeUs - activeSession.firstPresentationTimeUs).coerceAtLeast(0L)
                if (normalizedTimeUs <= activeSession.lastPresentationTimeUs) {
                    normalizedTimeUs = activeSession.lastPresentationTimeUs + 1L
                }

                val sampleFlags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                val bufferInfo = MediaCodec.BufferInfo().apply {
                    set(0, data.size, normalizedTimeUs, sampleFlags)
                }
                activeSession.muxer.writeSampleData(
                    activeSession.trackIndex,
                    ByteBuffer.wrap(data),
                    bufferInfo
                )
                activeSession.lastPresentationTimeUs = normalizedTimeUs
                activeSession.sampleCount++
                activeSession.encodedBytes += data.size

                if (activeSession.sampleCount == 1) {
                    onStateChanged(
                        RecordingState(
                            RecordingPhase.RECORDING,
                            "Circular recording: 15 min / 256 MiB segments, 8 GB limit"
                        )
                    )
                }
            } catch (exc: Exception) {
                failSessionLocked(activeSession, exc)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val activeSession = session ?: return
            session = null

            val saved = finishSessionLocked(activeSession)
            val message = if (saved) {
                "Saved to gallery: ${activeSession.displayName}"
            } else {
                "Recording was too short to save"
            }
            onStateChanged(RecordingState(RecordingPhase.IDLE, message))
        }
    }

    private fun startMuxerLocked(activeSession: RecordingSession, format: MediaFormat) {
        if (activeSession.muxerStarted) {
            return
        }
        activeSession.trackIndex = activeSession.muxer.addTrack(format)
        activeSession.muxer.start()
        activeSession.muxerStarted = true
    }

    private fun shouldRotate(
        activeSession: RecordingSession,
        presentationTimeUs: Long,
        nextSampleSize: Int,
        isKeyFrame: Boolean
    ): Boolean {
        if (!isKeyFrame || activeSession.sampleCount == 0) {
            return false
        }
        val durationUs = presentationTimeUs - activeSession.firstPresentationTimeUs
        return durationUs >= SEGMENT_DURATION_US ||
            activeSession.encodedBytes + nextSampleSize > SEGMENT_MAX_BYTES
    }

    private fun rotateSessionLocked(previousSession: RecordingSession): RecordingSession? {
        session = null
        if (!finishSessionLocked(previousSession)) {
            onStateChanged(
                RecordingState(
                    RecordingPhase.IDLE,
                    "Unable to finalize a recording segment"
                )
            )
            return null
        }

        return try {
            val nextSession = createSession()
            session = nextSession
            latestVideoFormat?.let { startMuxerLocked(nextSession, it) }
            onStateChanged(
                RecordingState(
                    RecordingPhase.RECORDING,
                    "Circular recording active · saved ${previousSession.displayName}"
                )
            )
            Log.i(TAG, "Rotated recording segment: ${previousSession.displayName}")
            nextSession
        } catch (exc: Exception) {
            Log.e(TAG, "Unable to create the next recording segment", exc)
            session?.let { discardSessionLocked(it) }
            session = null
            onStateChanged(
                RecordingState(
                    RecordingPhase.IDLE,
                    "Segment saved, but circular recording stopped: ${exc.message ?: "storage error"}"
                )
            )
            null
        }
    }

    private fun finishSessionLocked(activeSession: RecordingSession): Boolean {
        var saved = activeSession.muxerStarted && activeSession.sampleCount > 0
        if (activeSession.muxerStarted) {
            try {
                activeSession.muxer.stop()
            } catch (exc: Exception) {
                Log.e(TAG, "Unable to finalize MP4", exc)
                saved = false
            }
        }
        runCatching { activeSession.muxer.release() }
        runCatching { activeSession.fileDescriptor?.close() }

        if (saved) {
            try {
                publishSession(activeSession)
            } catch (exc: Exception) {
                Log.e(TAG, "Unable to publish video to the gallery", exc)
                saved = false
            }
        }
        if (!saved) {
            deleteSession(activeSession)
        } else {
            runCatching { enforceStorageLimit() }
                .onFailure { Log.w(TAG, "Unable to prune old recordings", it) }
        }
        return saved
    }

    private fun failSessionLocked(activeSession: RecordingSession, error: Throwable) {
        if (session !== activeSession) {
            return
        }
        Log.e(TAG, "Recording failed", error)
        session = null
        discardSessionLocked(activeSession)
        onStateChanged(
            RecordingState(
                RecordingPhase.IDLE,
                "Recording failed: ${error.message ?: "storage error"}"
            )
        )
    }

    private fun discardSessionLocked(activeSession: RecordingSession) {
        if (activeSession.muxerStarted) {
            runCatching { activeSession.muxer.stop() }
        }
        runCatching { activeSession.muxer.release() }
        runCatching { activeSession.fileDescriptor?.close() }
        deleteSession(activeSession)
    }

    private fun createSession(): RecordingSession {
        val displayName = "WatchDog_${timestamp()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreSession(displayName)
        } else {
            createLegacySession(displayName)
        }
    }

    private fun createMediaStoreSession(displayName: String): RecordingSession {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create a MediaStore item")

        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: error("Unable to open the MediaStore item")
            val muxer = MediaMuxer(
                descriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            return RecordingSession(
                displayName = displayName,
                muxer = muxer,
                mediaStoreUri = uri,
                fileDescriptor = descriptor
            )
        } catch (exc: Exception) {
            resolver.delete(uri, null, null)
            throw exc
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacySession(displayName: String): RecordingSession {
        val albumDirectory = getLegacyAlbumDirectory(createIfMissing = true)
            ?: error("Unable to create the WatchDog media album")
        val outputFile = File(albumDirectory, displayName)
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        return RecordingSession(
            displayName = displayName,
            muxer = muxer,
            legacyFile = outputFile
        )
    }

    @Suppress("DEPRECATION")
    private fun getLegacyAlbumDirectory(createIfMissing: Boolean): File? {
        val mediaDirectory = appContext.externalMediaDirs
            .firstOrNull { directory ->
                directory != null && (
                    directory.exists() ||
                        (createIfMissing && directory.mkdirs())
                    )
            }
            ?: return null
        val albumDirectory = File(mediaDirectory, ALBUM_NAME)
        return when {
            albumDirectory.exists() -> albumDirectory
            createIfMissing && albumDirectory.mkdirs() -> albumDirectory
            else -> null
        }
    }

    private fun publishSession(activeSession: RecordingSession) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = activeSession.mediaStoreUri ?: return
            appContext.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
        } else {
            val file = activeSession.legacyFile ?: return
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(file.absolutePath),
                arrayOf(MIME_TYPE),
                null
            )
        }
    }

    private fun deleteSession(activeSession: RecordingSession) {
        activeSession.mediaStoreUri?.let { uri ->
            runCatching { appContext.contentResolver.delete(uri, null, null) }
        }
        activeSession.legacyFile?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }
    }

    private fun enforceStorageLimit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enforceMediaStoreLimit()
        } else {
            enforceLegacyLimit()
        }
    }

    private fun enforceMediaStoreLimit() {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf(
            "%/$ALBUM_NAME/%",
            "WatchDog%.mp4"
        )
        val sortOrder =
            "${MediaStore.MediaColumns.DATE_ADDED} ASC, ${MediaStore.Video.Media._ID} ASC"
        val recordings = mutableListOf<StoredRecording>()

        resolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (!name.startsWith("WatchDog_") || !name.endsWith(".mp4")) {
                    continue
                }
                val id = cursor.getLong(idColumn)
                recordings += StoredRecording(
                    size = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    timestamp = cursor.getLong(dateColumn),
                    delete = {
                        resolver.delete(ContentUris.withAppendedId(collection, id), null, null) > 0
                    }
                )
            }
        }
        deleteOldestOverLimit(recordings)
    }

    @Suppress("DEPRECATION")
    private fun enforceLegacyLimit() {
        val albumDirectory = getLegacyAlbumDirectory(createIfMissing = false) ?: return
        val deletedPaths = mutableListOf<String>()
        val recordings = albumDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile && file.name.startsWith("WatchDog_") && file.extension == "mp4"
            }
            .map { file ->
                StoredRecording(
                    size = file.length(),
                    timestamp = file.lastModified(),
                    delete = {
                        val deleted = file.delete()
                        if (deleted) {
                            deletedPaths += file.absolutePath
                        }
                        deleted
                    }
                )
            }
            .toList()

        deleteOldestOverLimit(recordings)
        if (deletedPaths.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                appContext,
                deletedPaths.toTypedArray(),
                null,
                null
            )
        }
    }

    private fun deleteOldestOverLimit(recordings: List<StoredRecording>) {
        var totalBytes = recordings.sumOf(StoredRecording::size)
        if (totalBytes <= MAX_TOTAL_BYTES) {
            return
        }

        for (recording in recordings.sortedWith(compareBy(StoredRecording::timestamp))) {
            if (totalBytes <= MAX_TOTAL_BYTES) {
                break
            }
            if (recording.delete()) {
                totalBytes -= recording.size
                Log.i(TAG, "Deleted oldest recording; retained bytes=$totalBytes")
            }
        }
    }

    private fun timestamp(): String = SimpleDateFormat(
        "yyyyMMdd_HHmmss",
        Locale.US
    ).format(Date())

    private data class StoredRecording(
        val size: Long,
        val timestamp: Long,
        val delete: () -> Boolean
    )

    private data class RecordingSession(
        val displayName: String,
        val muxer: MediaMuxer,
        val mediaStoreUri: Uri? = null,
        val fileDescriptor: ParcelFileDescriptor? = null,
        val legacyFile: File? = null,
        var trackIndex: Int = -1,
        var muxerStarted: Boolean = false,
        var sampleCount: Int = 0,
        var encodedBytes: Long = 0L,
        var firstPresentationTimeUs: Long = Long.MIN_VALUE,
        var lastPresentationTimeUs: Long = -1L
    )
}
