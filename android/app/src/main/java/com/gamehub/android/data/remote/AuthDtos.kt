package com.gamehub.android.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String,
    val region: String,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    // Lets the client refresh proactively rather than waiting for a 401 and
    // retrying every in-flight request.
    val expiresInSeconds: Long,
    val profile: UserProfile,
)

@Serializable
data class ApiError(
    val timestamp: String? = null,
    val status: Int = 0,
    // The stable machine-readable code. The client branches on this and never
    // on the message, which is free to change.
    val error: String = "UNKNOWN",
    val message: String? = null,
    val path: String? = null,
    val traceId: String? = null,
    val details: List<FieldError> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
)

@Serializable
data class FieldError(val field: String, val rejectedValue: String?, val reason: String)
