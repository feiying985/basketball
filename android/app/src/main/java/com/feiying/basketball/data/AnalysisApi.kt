package com.feiying.basketball.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(
    val statusCode: Int,
    message: String,
    val detail: String? = null,
) : IOException(message)

data class HealthInfo(val version: String, val detector: String)

/** Thin HTTP client around the FastAPI service. */
class AnalysisApi(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrlProvider: suspend () -> String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val pollClient: OkHttpClient = client.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun health(): HealthInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl()}/api/v1/health").get().build()
        execute(pollClient, request).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, "健康检查失败", errorDetail(body))
            }
            val dto = json.decodeFromString<HealthDto>(body)
            HealthInfo(version = dto.version, detector = dto.detector)
        }
    }

    suspend fun upload(
        file: File,
        filename: String,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit,
    ): AnalysisJobDto = withContext(Dispatchers.IO) {
        val totalBytes = file.length()
        val mediaType = mediaTypeFor(filename)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                filename,
                ProgressRequestBody(file, mediaType) { sent -> onProgress(sent, totalBytes) },
            )
            .build()
        val request = Request.Builder()
            .url("${baseUrl()}/api/v1/analyses")
            .post(body)
            .build()
        execute(client, request).use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.code == HttpURLConnection.HTTP_ACCEPTED || response.isSuccessful) {
                json.decodeFromString<AnalysisJobDto>(responseBody)
            } else {
                throw ApiException(
                    statusCode = response.code,
                    message = when (response.code) {
                        HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> "视频超过大小限制"
                        HttpURLConnection.HTTP_UNSUPPORTED_TYPE -> "不支持的视频格式"
                        else -> "上传失败"
                    },
                    detail = errorDetail(responseBody),
                )
            }
        }
    }

    suspend fun getJob(jobId: String): AnalysisJobDto = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/v1/analyses/$jobId")
            .get()
            .build()
        execute(pollClient, request).use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND ->
                    throw ApiException(404, "分析任务不存在", errorDetail(body))
                response.isSuccessful -> json.decodeFromString<AnalysisJobDto>(body)
                else -> throw ApiException(response.code, "查询任务失败", errorDetail(body))
            }
        }
    }

    private suspend fun baseUrl(): String = baseUrlProvider().trimEnd('/')

    private fun errorDetail(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun mediaTypeFor(filename: String): MediaType? {
        val extension = filename.substringAfterLast('.', "").lowercase()
        val mime = when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
        return mime.toMediaTypeOrNull()
    }

    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private class ProgressRequestBody(
        private val file: File,
        private val contentType: MediaType?,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            val buffer = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress(sent)
                }
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
