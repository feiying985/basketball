package com.feiying.basketball.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

interface SessionStorage {
    suspend fun load(): AuthSession?
    suspend fun save(session: AuthSession)
    suspend fun clear()
}

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "basketball_auth",
)

/**
 * Stores the bearer token encrypted with an AES/GCM key held by Android
 * Keystore.  The DataStore preference itself never contains a plain token.
 */
class KeystoreSessionStore(context: Context) : SessionStorage {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): AuthSession? {
        val payload = appContext.authDataStore.data.first()[SESSION_KEY] ?: return null
        val plain = decrypt(payload) ?: run {
            clear()
            return null
        }
        val stored = runCatching {
            json.decodeFromString(StoredSession.serializer(), plain)
        }.getOrNull() ?: return null
        return AuthSession(
            token = stored.token,
            expiresAt = stored.expiresAt,
            user = stored.user.toDomain(),
        )
    }

    override suspend fun save(session: AuthSession) {
        val stored = StoredSession(
            token = session.token,
            expiresAt = session.expiresAt,
            user = session.user.toDto(),
        )
        val payload = encrypt(json.encodeToString(StoredSession.serializer(), stored))
        appContext.authDataStore.edit { preferences ->
            preferences[SESSION_KEY] = payload
        }
    }

    override suspend fun clear() {
        appContext.authDataStore.edit { preferences ->
            preferences.remove(SESSION_KEY)
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String? = runCatching {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        if (bytes.size <= IV_SIZE) return@runCatching null
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "basketball_auth_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        val SESSION_KEY = stringPreferencesKey("session")
    }
}

private fun AuthUser.toDto(): AuthUserDto = AuthUserDto(
    id = id,
    phone = phone,
    username = username,
    displayName = displayName,
    createdAt = createdAt,
)
