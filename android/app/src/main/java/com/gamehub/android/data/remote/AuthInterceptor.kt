package com.gamehub.android.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Attaches the bearer token to every request that needs one.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Auth endpoints must not carry a stale token. Sending an expired one
        // to /refresh would get it rejected by the filter before the refresh
        // logic ever ran.
        if (request.url.encodedPath.contains("/api/auth/")) {
            return chain.proceed(request)
        }

        val token = tokenStore.accessTokenBlocking()
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build(),
        )
    }
}

/**
 * Refreshes the access token once, on a 401.
 *
 * <h3>Why an Authenticator and not an Interceptor</h3>
 * OkHttp calls an `Authenticator` only after a 401, and it **serialises**
 * those calls. Doing the refresh in an interceptor would mean ten concurrent
 * requests that all 401 would trigger ten refreshes, and nine of them would
 * race to overwrite the token store.
 *
 * <h3>Why it gives up after one attempt</h3>
 * If the retried request also 401s, the refresh token is itself invalid, and
 * retrying further is an infinite loop against the server. `priorResponse`
 * is the guard.
 *
 * The `Provider<GameHubApi>` breaks a circular dependency: the API needs the
 * OkHttp client, which needs this authenticator, which needs the API.
 */
@Singleton
class TokenRefreshAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val apiProvider: Provider<GameHubApi>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Already retried once. The refresh token is bad; stop.
        if (response.priorResponse != null) {
            return null
        }

        val refreshToken = runBlocking { tokenStore.refreshToken() } ?: return null

        val refreshed = runBlocking {
            runCatching { apiProvider.get().refresh(RefreshRequest(refreshToken)) }
                .getOrNull()
        }

        val body = refreshed?.body()
        if (refreshed?.isSuccessful != true || body == null) {
            // The refresh token is dead. Clear everything so the UI observes
            // isSignedIn = false and routes to sign-in, rather than leaving a
            // token that will fail on every future request.
            runBlocking { tokenStore.clear() }
            return null
        }

        runBlocking { tokenStore.save(body.accessToken, body.refreshToken) }

        return response.request.newBuilder()
            .header("Authorization", "Bearer ${body.accessToken}")
            .build()
    }
}
