package com.lyrenne.desktop.sound.listenbrainz

import com.lyrenne.desktop.sound.HttpTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * One listen as it is kept on disk until ListenBrainz has it. Everything the payload needs, so a
 * listen queued on Monday can still be sent on Friday after the song left the queue.
 */
@Serializable
data class PendingListen(
    /** Unix seconds when the listen started. ListenBrainz dedupes on this plus the track. */
    val listenedAt: Long,
    val artist: String,
    val track: String,
    val release: String? = null,
    val durationMs: Long? = null,
    val videoId: String,
)

/** What the player can be submitted as. `import` is several listens in one request. */
enum class ListenType(val wire: String) { PLAYING_NOW("playing_now"), SINGLE("single"), IMPORT("import") }

/** The JSON body for `/1/submit-listens`. Pure, so the tests can pin it. */
object ListenPayload {
    const val CLIENT = "Lyrenne"

    fun build(type: ListenType, listens: List<PendingListen>, clientVersion: String): String {
        require(listens.isNotEmpty()) { "nothing to submit" }
        require(type == ListenType.IMPORT || listens.size == 1) { "${type.wire} takes exactly one listen" }
        val body = buildJsonObject {
            put("listen_type", type.wire)
            put("payload", buildJsonArray {
                listens.forEach { listen ->
                    add(buildJsonObject {
                        // playing_now must not carry a timestamp, the other two must
                        if (type != ListenType.PLAYING_NOW) put("listened_at", listen.listenedAt)
                        put("track_metadata", trackMetadata(listen, clientVersion))
                    })
                }
            })
        }
        return body.toString()
    }

    private fun trackMetadata(listen: PendingListen, clientVersion: String) = buildJsonObject {
        put("artist_name", listen.artist)
        put("track_name", listen.track)
        listen.release?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
        put("additional_info", buildJsonObject {
            listen.durationMs?.takeIf { it > 0 }?.let { put("duration_ms", it) }
            put("origin_url", "https://music.youtube.com/watch?v=${listen.videoId}")
            put("music_service", "music.youtube.com")
            put("music_service_name", "YouTube Music")
            put("submission_client", CLIENT)
            put("submission_client_version", clientVersion)
            put("media_player", CLIENT)
            put("media_player_version", clientVersion)
        })
    }
}

/** How a submission went, which decides what happens to the listens in it. */
sealed class SubmitOutcome {
    data object Accepted : SubmitOutcome()
    /** The listens themselves are wrong (HTTP 400). Sending them again will not help. */
    data class Rejected(val message: String) : SubmitOutcome()
    /** The token is no longer good. Listens wait until a new one is pasted. */
    data object Unauthorized : SubmitOutcome()
    /** Offline, a timeout, a 5xx or a rate limit: try again later, after [retryAfterMs] if given. */
    data class TryLater(val reason: String, val retryAfterMs: Long? = null) : SubmitOutcome()
}

sealed class TokenCheck {
    data class Valid(val userName: String) : TokenCheck()
    data object Invalid : TokenCheck()
    data class Unreachable(val reason: String) : TokenCheck()
}

class ListenBrainzApi(
    private val transport: HttpTransport,
    private val baseUrl: String = "https://api.listenbrainz.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun auth(token: String) = mapOf("Authorization" to "Token ${token.trim()}")

    fun validateToken(token: String): TokenCheck = try {
        val r = transport.get("$baseUrl/1/validate-token", auth(token))
        when {
            r.code == 200 -> {
                val o = json.parseToJsonElement(r.body).jsonObject
                val valid = o["valid"]?.jsonPrimitive?.boolean == true
                val name = o["user_name"]?.jsonPrimitive?.contentOrNull
                if (valid && name != null) TokenCheck.Valid(name) else TokenCheck.Invalid
            }
            r.code == 400 || r.code == 401 -> TokenCheck.Invalid
            else -> TokenCheck.Unreachable("HTTP ${r.code}")
        }
    } catch (e: IOException) {
        TokenCheck.Unreachable(e.message ?: "offline")
    } catch (e: Exception) {
        TokenCheck.Unreachable(e.message ?: e.javaClass.simpleName)
    }

    fun submit(token: String, type: ListenType, listens: List<PendingListen>, clientVersion: String): SubmitOutcome {
        val body = ListenPayload.build(type, listens, clientVersion)
        return try {
            val r = transport.send("POST", "$baseUrl/1/submit-listens", auth(token), body)
            when (r.code) {
                200 -> SubmitOutcome.Accepted
                400 -> SubmitOutcome.Rejected(errorText(r.body))
                401, 403 -> SubmitOutcome.Unauthorized
                429 -> SubmitOutcome.TryLater(
                    "rate limited",
                    r.headers["x-ratelimit-reset-in"]?.toLongOrNull()?.let { (it + 1) * 1000 }
                )
                else -> SubmitOutcome.TryLater("HTTP ${r.code}")
            }
        } catch (e: IOException) {
            SubmitOutcome.TryLater(e.message ?: "offline")
        } catch (e: Exception) {
            SubmitOutcome.TryLater(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun errorText(body: String): String = runCatching {
        (json.parseToJsonElement(body) as JsonObject)["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(200)
}
