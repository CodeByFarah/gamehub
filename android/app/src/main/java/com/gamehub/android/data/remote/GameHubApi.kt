package com.gamehub.android.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The backend, as the app sees it.
 *
 * Every method returns `Response<T>` rather than a bare `T`. Retrofit throws
 * on a non-2xx when the return type is bare, which would turn a 409 cloud-save
 * conflict into an exception even though it is a documented, expected outcome
 * carrying data the client needs.
 */
interface GameHubApi {

    // --- Auth, public ------------------------------------------------------

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<AuthResponse>

    // --- Catalogue, public -------------------------------------------------

    @GET("api/games")
    suspend fun games(
        @Query("q") query: String? = null,
        @Query("genre") genre: String? = null,
        @Query("multiplayer") multiplayer: Boolean? = null,
        @Query("maxSessionMinutes") maxSessionMinutes: Int? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponse<GameSummary>>

    @GET("api/games/{id}")
    suspend fun game(@Path("id") id: String): Response<GameDetail>

    // --- Leaderboards, public ----------------------------------------------

    @GET("api/games/{gameId}/leaderboard")
    suspend fun gameLeaderboard(
        @Path("gameId") gameId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<LeaderboardResponse>

    @GET("api/leaderboards/global")
    suspend fun globalLeaderboard(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<LeaderboardResponse>

    @GET("api/leaderboards/regional/{region}")
    suspend fun regionalLeaderboard(
        @Path("region") region: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<LeaderboardResponse>

    // --- Player ------------------------------------------------------------

    @GET("api/users/me")
    suspend fun me(): Response<UserProfile>

    @GET("api/users/me/achievements")
    suspend fun achievements(@Query("gameId") gameId: String? = null): Response<List<Achievement>>

    @GET("api/users/me/recommendations")
    suspend fun recommendations(@Query("limit") limit: Int = 10): Response<List<Recommendation>>

    // --- Matchmaking -------------------------------------------------------

    @POST("api/matchmaking/join")
    suspend fun joinQueue(@Body request: JoinQueueRequest): Response<MatchmakingTicket>

    @DELETE("api/matchmaking/leave")
    suspend fun leaveQueue(): Response<Unit>

    @GET("api/matchmaking/tickets/{id}")
    suspend fun ticket(@Path("id") id: String): Response<MatchmakingTicket>

    // --- Sessions ----------------------------------------------------------

    @POST("api/sessions")
    suspend fun startSession(@Body request: StartSessionRequest): Response<SessionResponse>

    @POST("api/sessions/{id}/complete")
    suspend fun completeSession(
        @Path("id") id: String,
        @Body request: CompleteSessionRequest,
    ): Response<SessionResponse>

    // --- Cloud saves -------------------------------------------------------

    @GET("api/cloud-saves")
    suspend fun cloudSaves(): Response<List<CloudSave>>

    @GET("api/cloud-saves/{gameId}")
    suspend fun cloudSave(
        @Path("gameId") gameId: String,
        @Query("slot") slot: Int = 0,
    ): Response<CloudSave>

    @POST("api/cloud-saves")
    suspend fun uploadCloudSave(@Body request: CloudSaveUploadRequest): Response<CloudSave>

    // --- AI ----------------------------------------------------------------

    @POST("api/ai/search")
    suspend fun aiSearch(@Body request: AiSearchRequest): Response<AiSearchResponse>

    @POST("api/ai/assistant")
    suspend fun aiAssistant(@Body request: AiAssistantRequest): Response<AiAssistantResponse>
}
