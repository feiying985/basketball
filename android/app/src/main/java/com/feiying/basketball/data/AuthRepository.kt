package com.feiying.basketball.data

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the login state, token persistence and account operations.
 *
 * Restoring a locally unexpired session is optimistic: the app opens without
 * blocking on the network, then ``/auth/me`` validates the token in the
 * background.  A 401 clears the session and returns the user to login.
 */
class AuthRepository(
    private val api: AuthApi,
    private val store: SessionStorage,
    private val tokenProvider: TokenProvider,
    private val scope: CoroutineScope,
    private val onSessionChanged: suspend (AuthUser?) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var initialized = false

    suspend fun restore() {
        if (initialized) return
        initialized = true
        val session = store.load()
        if (session == null || session.isExpired(now())) {
            if (session != null) store.clear()
            tokenProvider.token = null
            _state.value = AuthState.LoggedOut
            notifySessionChanged(null)
            return
        }
        tokenProvider.token = session.token
        _state.value = AuthState.LoggedIn(session.user)
        notifySessionChanged(session.user)
        scope.launch {
            try {
                val user = api.me().toDomain()
                val latest = store.load() ?: return@launch
                store.save(latest.copy(user = user))
                _state.value = AuthState.LoggedIn(user)
                notifySessionChanged(user)
            } catch (error: AuthApiException) {
                if (error.statusCode == 401) onUnauthorized()
            } catch (error: IOException) {
                // Offline start: keep the locally valid session.
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    suspend fun sendCode(phone: String, purpose: String): AuthResult<SmsCodeResponse> =
        call { api.sendCode(phone, purpose) }

    suspend fun register(phone: String, code: String, password: String): AuthResult<Unit> =
        call {
            persist(api.register(phone, code, password))
            Unit
        }

    suspend fun login(account: String, password: String): AuthResult<Unit> =
        call {
            persist(api.login(account, password))
            Unit
        }

    suspend fun loginWithCode(phone: String, code: String): AuthResult<Unit> =
        call {
            persist(api.loginWithCode(phone, code))
            Unit
        }

    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit> =
        call { api.changePassword(currentPassword, newPassword) }

    suspend fun resetPassword(
        phone: String,
        code: String,
        newPassword: String,
    ): AuthResult<Unit> = call {
        api.resetPassword(phone, code, newPassword)
        clearSession()
    }

    suspend fun logout() {
        try {
            api.logout()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Local logout must still work when the network/server is down.
        }
        clearSession()
    }

    fun onUnauthorized() {
        if (_state.value is AuthState.LoggedOut) return
        scope.launch { clearSession() }
    }

    private suspend fun persist(response: TokenResponseDto) {
        val user = response.user.toDomain()
        val session = AuthSession(
            token = response.accessToken,
            expiresAt = response.expiresAt,
            user = user,
        )
        store.save(session)
        tokenProvider.token = session.token
        notifySessionChanged(user)
        _state.value = AuthState.LoggedIn(user)
    }

    private suspend fun clearSession() {
        tokenProvider.token = null
        store.clear()
        _state.value = AuthState.LoggedOut
        notifySessionChanged(null)
    }

    private suspend fun notifySessionChanged(user: AuthUser?) {
        try {
            onSessionChanged(user)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A local history refresh must not break login/logout.
        }
    }

    private suspend fun <T> call(block: suspend () -> T): AuthResult<T> = try {
        AuthResult.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        AuthResult.Failure(error.toAuthError())
    }

    private fun Throwable.toAuthError(): AuthError = when (this) {
        is AuthApiException -> AuthError(
            message = detail ?: message ?: "请求失败",
            retryAfterSeconds = retryAfterSeconds,
        )

        is IOException -> AuthError("网络连接失败，请检查网络")
        else -> AuthError(message ?: "请求失败")
    }
}
