package com.gamehub.android.di

import android.content.Context
import androidx.room.Room
import com.gamehub.android.BuildConfig
import com.gamehub.android.data.local.GameDao
import com.gamehub.android.data.local.GameHubDatabase
import com.gamehub.android.data.local.ProfileDao
import com.gamehub.android.data.remote.AuthInterceptor
import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.data.remote.TokenRefreshAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        // The server may add fields before the app is updated. Without this,
        // a purely additive, backward-compatible API change would crash every
        // installed client.
        ignoreUnknownKeys = true
        // Absent optional fields take their Kotlin defaults rather than
        // failing deserialisation.
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttpClient(
        authInterceptor: AuthInterceptor,
        authenticator: TokenRefreshAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        // An Authenticator, not an interceptor: OkHttp serialises these, so
        // ten concurrent 401s trigger one refresh rather than ten racing ones.
        .authenticator(authenticator)
        .apply {
            if (BuildConfig.DEBUG) {
                // BODY only in debug. In release this would log bearer tokens
                // and cloud-save payloads to logcat, readable by anything
                // with log access.
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    },
                )
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        // Read timeout exceeds the server AI budget of 8 seconds plus its
        // retries, so a slow AI response is not cut off by the client after
        // the server has already paid for it.
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun gameHubApi(retrofit: Retrofit): GameHubApi = retrofit.create(GameHubApi::class.java)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): GameHubDatabase =
        Room.databaseBuilder(context, GameHubDatabase::class.java, "gamehub.db")
            // The cache is disposable, so a schema change drops and rebuilds
            // it rather than requiring a migration for data that can simply
            // be re-fetched. This would be wrong for user-authored data.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun gameDao(database: GameHubDatabase): GameDao = database.gameDao()

    @Provides
    fun profileDao(database: GameHubDatabase): ProfileDao = database.profileDao()
}
