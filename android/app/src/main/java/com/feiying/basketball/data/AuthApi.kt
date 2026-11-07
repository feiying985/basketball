package com.feiying.basketball.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class AuthApiException(
    val statusCode: Int,
    message: String,
    val detail: String? = null,
    val retryAfterSeconds: Int? = null,
) : IOException(message)

/** Thin HTTP client for the account/SMS endpoints. */
class AuthApi(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrlProvider: suspend () -> String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val requestClient: OkHttpClient = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun sendCode(phone: String, purpose: String): SmsCodeResponse =
        withContext(Dispatchers.IO) {
            val request = jsonRequest(
                path = "/api/v1/auth/sms-code",
                body = json.encodeToString(SmsCodeRequest.serializer(), SmsCodeRequest(phone, purpose)),
            )
            execute(requestClient, request).use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    json.decodeFromString(SmsCodeResponse.serializer(), body)
                } else {
                    throw error(response, body, "发送验证码失败")
                }
            }
        }

    suspend fun register(phone: String, code: String, password: String): TokenResponseDto =
        tokenRequest(
            path = "/api/v1/auth/register",
            body = json.encodeToString(
                RegisterRequest.serializer(),
                RegisterRequest(phone = phone, code = code, password = password),
            ),
            fallback = "注册失败",
        )

    suspend fun login(account: String, password: String): TokenResponseDto =
        tokenRequest(
            path = "/api/v1/auth/login",
            body = json.encodeToString(
                LoginRequest.serializer(),
                LoginRequest(account = account, password = password),
            ),
            fallback = "登录失败",
        )

    suspend fun loginWithCode(phone: String, code: String): TokenResponseDto =
        tokenRequest(
            path = "/api/v1/auth/login/code",
            body = json.encodeToString(
                CodeLoginRequest.serializer(),
                CodeLoginRequest(phone = phone, code = code),
            ),
            fallback = "登录失败",
        )

    suspend fun me(): AuthUserDto = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/v1/auth/me")
            .get()
            .build()
        execute(requestClient, request).use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                json.decodeFromString(AuthUserDto.serializer(), body)
            } else {
                throw error(response, body, "获取登录状态失败")
            }
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/v1/auth/logout")
            .post(emptyBody())
            .build()
        execute(requestClient, request).use { response ->
            if (!response.isSuccessful) {
                throw error(response, response.body?.string().orEmpty(), "退出登录失败")
            }
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Unit =
        withContext(Dispatchers.IO) {
            val request = jsonRequest(
                path = "/api/v1/auth/password",
                body = json.encodeToString(
                    ChangePasswordRequest.serializer(),
                    ChangePasswordRequest(currentPassword, newPassword),
                ),
            )
            execute(requestClient, request).use { response ->
                if (!response.isSuccessful) {
                    throw error(response, response.body?.string().orEmpty(), "修改密码失败")
                }
            }
        }

    suspend fun resetPassword(phone: String, code: String, newPassword: String): Unit =
        withContext(Dispatchers.IO) {
            val request = jsonRequest(
                path = "/api/v1/auth/password/reset",
                body = json.encodeToString(
                    ResetPasswordRequest.serializer(),
                    ResetPasswordRequest(phone, code, newPassword),
                ),
            )
            execute(requestClient, request).use { response ->
                if (!response.isSuccessful) {
                    throw error(response, response.body?.string().orEmpty(), "重置密码失败")
                }
            }
        }

    private suspend fun tokenRequest(
        path: String,
        body: String,
        fallback: String,
    ): TokenResponseDto = withContext(Dispatchers.IO) {
        val request = jsonRequest(path, body)
        execute(requestClient, request).use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                json.decodeFromString(TokenResponseDto.serializer(), responseBody)
            } else {
                throw error(response, responseBody, fallback)
            }
        }
    }

    private suspend fun baseUrl(): String = baseUrlProvider().trimEnd('/')

    private suspend fun jsonRequest(path: String, body: String): Request = Request.Builder()
        .url("${baseUrl()}$path")
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private fun error(response: Response, body: String, fallback: String): AuthApiException {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val retryAfter = response.header("Retry-After")?.toIntOrNull()
        return AuthApiException(
            statusCode = response.code,
            message = detail ?: fallback,
            detail = detail,
            retryAfterSeconds = retryAfter,
        )
    }

    private fun emptyBody() = ByteArray(0).toRequestBody(null)

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
