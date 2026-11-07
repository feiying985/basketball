package com.feiying.basketball.data

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthUserDto(
    val id: String,
    val phone: String? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_at") val expiresAt: String? = null,
    val user: AuthUserDto,
)

@Serializable
data class SmsCodeRequest(val phone: String, val purpose: String)

@Serializable
data class SmsCodeResponse(
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("resend_after") val resendAfter: Int,
)

@Serializable
data class RegisterRequest(
    val phone: String,
    val code: String,
    val password: String,
    val username: String? = null,
)

@Serializable
data class LoginRequest(val account: String, val password: String)

@Serializable
data class CodeLoginRequest(val phone: String, val code: String)

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ResetPasswordRequest(
    val phone: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class StoredSession(
    val token: String,
    val expiresAt: String? = null,
    val user: AuthUserDto,
)

data class AuthUser(
    val id: String,
    val phone: String?,
    val username: String?,
    val displayName: String,
    val createdAt: String,
) {
    val phoneMasked: String
        get() {
            val digits = phone?.removePrefix("+86") ?: return ""
            if (digits.length < 7) return digits
            return digits.take(3) + "****" + digits.takeLast(4)
        }

    val name: String
        get() = displayName.ifBlank { username ?: phoneMasked.ifBlank { "用户" } }
}

data class AuthSession(
    val token: String,
    val expiresAt: String?,
    val user: AuthUser,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean {
        val raw = expiresAt ?: return false
        return runCatching { Instant.parse(raw).isBefore(now) }.getOrDefault(true)
    }
}

data class AuthError(
    val message: String,
    val retryAfterSeconds: Int? = null,
)

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val user: AuthUser) : AuthState
}

sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}

fun AuthUserDto.toDomain(): AuthUser = AuthUser(
    id = id,
    phone = phone,
    username = username,
    displayName = displayName,
    createdAt = createdAt,
)
