package com.feiying.basketball.data

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** Holds the current bearer token in memory for the network layer. */
class TokenProvider {
    @Volatile
    var token: String? = null
}

/**
 * Adds the bearer token to every request and reports a 401 from protected
 * endpoints.  Login/register/code/reset endpoints return 401 for bad input,
 * so they must not trigger a session-expired event.
 */
class AuthInterceptor(
    private val tokenProvider: TokenProvider,
    private val onUnauthorized: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider.token
        val request = if (token.isNullOrBlank()) {
            original
        } else {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            throw error
        }
        if (response.code == 401 && !isPublicAuthPath(original.url.encodedPath)) {
            onUnauthorized()
        }
        return response
    }

    private fun isPublicAuthPath(path: String): Boolean =
        path == "/api/v1/auth/login" ||
            path == "/api/v1/auth/login/code" ||
            path == "/api/v1/auth/register" ||
            path == "/api/v1/auth/sms-code" ||
            path == "/api/v1/auth/password/reset"
}
