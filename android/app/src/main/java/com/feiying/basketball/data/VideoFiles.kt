package com.feiying.basketball.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.feiying.basketball.domain.SelectedVideo
import com.feiying.basketball.domain.VideoRules
import com.feiying.basketball.domain.VideoSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ImportErrorReason {
    UNREADABLE,
    TOO_LARGE,
    STORAGE,
    COPY_FAILED,
}

class VideoImportException(
    val reason: ImportErrorReason,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

/** Reads video metadata and copies gallery picks into the app cache. */
object VideoFiles {
    private const val VIDEO_DIR = "videos"

    fun videoDirectory(context: Context): File =
        File(context.cacheDir, VIDEO_DIR).apply { mkdirs() }

    fun newRecordingFile(context: Context, timestamp: Long = System.currentTimeMillis()): File {
        val directory = videoDirectory(context)
        val extension = "mp4"
        var file = File(directory, VideoRules.buildCacheFilename("rec", extension, timestamp))
        var counter = 1
        while (file.exists()) {
            file = File(directory, "rec_${timestamp}_$counter.$extension")
            counter++
        }
        return file
    }

    suspend fun importFromGallery(context: Context, uri: Uri): Result<SelectedVideo> =
        withContext(Dispatchers.IO) {
            runCatching { importInternal(context, uri) }
        }

    private fun importInternal(context: Context, uri: Uri): SelectedVideo {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri)
        val mimeType = resolver.getType(uri)
        val extension = displayName
            ?.let(VideoRules::extensionOf)
            ?.takeIf { it in VideoRules.allowedExtensions }
            ?: VideoRules.extensionForMimeType(mimeType)
        val safeName = VideoRules.sanitizeFilename(
            displayName ?: "video.$extension",
            extension,
        )
        val declaredSize = querySize(context, uri)
        if (declaredSize != null && declaredSize > VideoRules.MAX_UPLOAD_BYTES) {
            throw VideoImportException(ImportErrorReason.TOO_LARGE)
        }
        val requiredSpace = (declaredSize ?: (50L * 1024 * 1024)) + 8L * 1024 * 1024
        if (context.cacheDir.usableSpace < requiredSpace) {
            throw VideoImportException(ImportErrorReason.STORAGE)
        }

        val destination = newImportFile(context, extension)
        var copied = 0L
        try {
            val input = resolver.openInputStream(uri)
                ?: throw VideoImportException(ImportErrorReason.UNREADABLE)
            input.use { source ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        copied += read
                        if (copied > VideoRules.MAX_UPLOAD_BYTES) {
                            throw VideoImportException(ImportErrorReason.TOO_LARGE)
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: VideoImportException) {
            destination.delete()
            throw error
        } catch (error: IOException) {
            destination.delete()
            throw VideoImportException(ImportErrorReason.COPY_FAILED, error)
        } catch (error: SecurityException) {
            destination.delete()
            throw VideoImportException(ImportErrorReason.UNREADABLE, error)
        }

        val durationMs = readDurationMs(context, Uri.fromFile(destination))
        if (durationMs == null) {
            destination.delete()
            throw VideoImportException(ImportErrorReason.UNREADABLE)
        }
        return SelectedVideo(
            file = destination,
            filename = safeName,
            sizeBytes = copied,
            durationMs = durationMs,
            source = VideoSource.GALLERY,
        )
    }

    private fun newImportFile(context: Context, extension: String): File {
        val directory = videoDirectory(context)
        val timestamp = System.currentTimeMillis()
        var file = File(
            directory,
            VideoRules.buildCacheFilename("import", extension, timestamp),
        )
        var counter = 1
        while (file.exists()) {
            file = File(directory, "import_${timestamp}_$counter.$extension")
            counter++
        }
        return file
    }

    fun readDurationMs(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun metadataForRecordedFile(context: Context, file: File): Pair<Long?, Long?> {
        val duration = readDurationMs(context, Uri.fromFile(file))
        return file.length() to duration
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun deleteQuietly(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }
}
