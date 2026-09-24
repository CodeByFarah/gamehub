package com.gamehub.android.data.repository

import android.util.Base64
import com.gamehub.android.data.remote.CloudSave
import com.gamehub.android.data.remote.CloudSaveUploadRequest
import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.domain.ApiResult
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud saves, including the conflict path.
 *
 * <h3>The 409 is not an error</h3>
 * It is a documented outcome that carries the data needed to recover. A client
 * that treats it as a generic failure loses player progress, which is exactly
 * the bug the server design exists to prevent.
 *
 * [SaveOutcome] therefore makes Conflict a first-class result rather than an
 * exception, so the compiler forces the caller to decide what to do about it.
 *
 * <h3>Nothing is cached</h3>
 * Caching a save would invite the client to upload from a version it no longer
 * holds, manufacturing the conflict this design guards against.
 */
@Singleton
class CloudSaveRepository @Inject constructor(
    private val api: GameHubApi,
    private val json: Json,
) {

    /** Mirrors the server ceiling. Checked here so an oversized save fails
     *  before a megabyte is uploaded and rejected. */
    private val maxPayloadBytes = 1_048_576

    sealed interface SaveOutcome {
        data class Saved(val save: CloudSave) : SaveOutcome

        /**
         * Another device wrote first.
         *
         * @param serverVersion present it on the retry after merging
         * @param serverChecksum lets the client tell whether the server copy
         *        is actually different, or whether this was a duplicate
         *        upload of identical bytes
         */
        data class Conflict(val serverVersion: Long, val serverChecksum: String) : SaveOutcome

        data class Failed(val failure: ApiResult.Failure) : SaveOutcome
    }

    suspend fun list(): ApiResult<List<CloudSave>> = apiCall(json) { api.cloudSaves() }

    suspend fun download(gameId: String, slot: Int = 0): ApiResult<CloudSave> =
        apiCall(json) { api.cloudSave(gameId, slot) }

    /**
     * Uploads a save under optimistic concurrency control.
     *
     * @param expectedVersion the version this device believes is current.
     *        Zero means create. There is no default: omitting it would mean
     *        silently accepting last-write-wins.
     */
    suspend fun upload(
        gameId: String,
        payload: ByteArray,
        expectedVersion: Long,
        slot: Int = 0,
        deviceId: String? = null,
    ): SaveOutcome {
        require(payload.isNotEmpty()) { "a cloud save payload must not be empty" }
        require(payload.size <= maxPayloadBytes) {
            "payload of ${payload.size} bytes exceeds the 1 MiB limit"
        }

        val request = CloudSaveUploadRequest(
            gameId = gameId,
            slot = slot,
            expectedVersion = expectedVersion,
            payloadBase64 = Base64.encodeToString(payload, Base64.NO_WRAP),
            // Computed over the raw bytes, before encoding, so it matches
            // what the server computes after decoding.
            checksum = sha256Hex(payload),
            deviceId = deviceId,
        )

        return when (val result = apiCall(json) { api.uploadCloudSave(request) }) {
            is ApiResult.Success -> SaveOutcome.Saved(result.data)

            is ApiResult.Failure ->
                if (result.code == CONFLICT_CODE) {
                    // The meta map is what makes this recoverable without
                    // another request. Falling back to the current version
                    // would be wrong, so the values are read defensively and
                    // a malformed envelope degrades to a plain failure.
                    val version = result.meta["serverVersion"]?.toString()?.toLongOrNull()
                    val checksum = result.meta["serverChecksum"]?.toString()

                    if (version != null && checksum != null) {
                        SaveOutcome.Conflict(version, checksum)
                    } else {
                        SaveOutcome.Failed(result)
                    }
                } else {
                    SaveOutcome.Failed(result)
                }
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** The stable code from the API envelope. Never the message text. */
        const val CONFLICT_CODE = "VERSION_CONFLICT"
    }
}
