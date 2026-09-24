package com.gamehub.android.data.repository

import com.gamehub.android.data.local.ProfileDao
import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.data.remote.LoginRequest
import com.gamehub.android.data.remote.RegisterRequest
import com.gamehub.android.data.remote.TokenStore
import com.gamehub.android.data.remote.UserProfile
import com.gamehub.android.domain.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sign-in, sign-out, and the observable signed-in state.
 *
 * `isSignedIn` is a Flow so navigation reacts to a token being cleared,
 * including when the authenticator clears it after a failed refresh. Polling
 * or checking once at startup would leave the user on a screen making requests
 * that all 401.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: GameHubApi,
    private val tokenStore: TokenStore,
    private val profileDao: ProfileDao,
    private val json: Json,
) {

    val isSignedIn: Flow<Boolean> = tokenStore.isSignedIn

    suspend fun register(
        username: String,
        email: String,
        password: String,
        displayName: String,
        region: String,
    ): ApiResult<UserProfile> =
        apiCall(json) {
            api.register(RegisterRequest(username, email, password, displayName, region))
        }.also { persistTokens(it) }.mapToProfile()

    suspend fun login(username: String, password: String): ApiResult<UserProfile> =
        apiCall(json) { api.login(LoginRequest(username, password)) }
            .also { persistTokens(it) }
            .mapToProfile()

    /**
     * Clears local state.
     *
     * The cached profile goes too. Leaving it would show the previous user
     * details on the next sign-in screen, which is both confusing and a small
     * privacy leak on a shared device.
     *
     * The catalogue cache is deliberately kept: it is public data and
     * re-downloading it on every sign-in would be wasteful.
     */
    suspend fun signOut() {
        tokenStore.clear()
        profileDao.clear()
    }

    private suspend fun persistTokens(
        result: ApiResult<com.gamehub.android.data.remote.AuthResponse>,
    ) {
        if (result is ApiResult.Success) {
            tokenStore.save(result.data.accessToken, result.data.refreshToken)
        }
    }

    private fun ApiResult<com.gamehub.android.data.remote.AuthResponse>.mapToProfile():
        ApiResult<UserProfile> = when (this) {
        is ApiResult.Success -> ApiResult.Success(data.profile)
        is ApiResult.Failure -> this
    }
}
