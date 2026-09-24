package com.gamehub.android.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore by preferencesDataStore(name = "gamehub_tokens")

/**
 * Persists the access and refresh tokens.
 *
 * DataStore rather than SharedPreferences: the read API is a Flow and writes
 * are transactional, so a torn write cannot leave an access token stored
 * without its matching refresh token.
 *
 * `allowBackup="false"` in the manifest keeps these off Google Drive backups.
 *
 * <h3>On encryption</h3>
 * These are stored unencrypted in app-private storage. On a non-rooted device
 * that is already inaccessible to other apps, and the honest position is that
 * EncryptedSharedPreferences would add ceremony without changing the threat
 * model much: an attacker with root has the key too. What actually limits
 * damage is the 15-minute access token lifetime.
 *
 * This is a deliberate trade, stated rather than hidden. A real product
 * handling payment would use the Keystore-backed option.
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")

    val accessToken: Flow<String?> =
        context.tokenDataStore.data.map { it[accessKey] }

    val isSignedIn: Flow<Boolean> =
        context.tokenDataStore.data.map { it[refreshKey] != null }

    suspend fun save(access: String, refresh: String) {
        context.tokenDataStore.edit { prefs ->
            prefs[accessKey] = access
            prefs[refreshKey] = refresh
        }
    }

    suspend fun clear() {
        context.tokenDataStore.edit { it.clear() }
    }

    suspend fun refreshToken(): String? =
        context.tokenDataStore.data.first()[refreshKey]

    /**
     * Blocking read, used only by the OkHttp interceptor.
     *
     * Interceptors run on OkHttp threads and cannot suspend. This is the one
     * place a blocking read is acceptable, and it is confined here rather
     * than exposed as a general API, because calling it from the main thread
     * would block the UI.
     */
    fun accessTokenBlocking(): String? = runBlocking {
        context.tokenDataStore.data.first()[accessKey]
    }
}
