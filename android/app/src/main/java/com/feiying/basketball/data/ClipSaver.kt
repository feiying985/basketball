package com.feiying.basketball.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of copying a downloaded clip into the user's gallery. */
sealed interface ClipSaveResult {
    data class Saved(val uri: Uri?) : ClipSaveResult
    data class Failed(val detail: String?) : ClipSaveResult
}

/**
 * Saves an MP4 into `Movies/Basketball`.
 *
 * Android 10+ uses MediaStore and needs no storage permission.  Android 8-9
 * writes to the public Movies directory after the caller obtained
 * ``WRITE_EXTERNAL_STORAGE``; the caller asks for it before calling [save].
 */
object ClipSaver {
    private const val RELATIVE_DIR = "Basketball"
    private const val MIME_TYPE = "video/mp4"

    suspend fun save(
        context: Context,
        source: File,
        displayName: String,
    ): ClipSaveResult = withContext(Dispatchers.IO) {
        if (!source.isFile) {
            return@withContext ClipSaveResult.Failed("片段文件不存在")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, source, displayName)
            } else {
                saveToPublicMovies(context, source, displayName)
            }
        } catch (error: Exception) {
            ClipSaveResult.Failed(error.message)
        }
    }

    private fun saveWithMediaStore(
        context: Context,
        source: File,
        displayName: String,
    ): ClipSaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$RELATIVE_DIR",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val uri = resolver.insert(collection, values)
            ?: return ClipSaveResult.Failed("无法在相册中创建文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入相册文件")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return ClipSaveResult.Saved(uri)
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicMovies(
        context: Context,
        source: File,
        displayName: String,
    ): ClipSaveResult {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            RELATIVE_DIR,
        )
        if (!directory.isDirectory && !directory.mkdirs()) {
            return ClipSaveResult.Failed("无法创建相册目录")
        }
        val target = uniqueFile(directory, displayName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(MIME_TYPE),
            null,
        )
        return ClipSaveResult.Saved(Uri.fromFile(target))
    }

    private fun uniqueFile(directory: File, displayName: String): File {
        val initial = File(directory, displayName)
        if (!initial.exists()) return initial
        val stem = initial.nameWithoutExtension
        val extension = initial.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem-$index$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
