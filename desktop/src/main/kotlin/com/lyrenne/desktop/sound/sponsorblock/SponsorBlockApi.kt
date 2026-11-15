package com.lyrenne.desktop.sound.sponsorblock

import com.lyrenne.desktop.sound.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The SponsorBlock categories Lyrenne offers, in the order Settings lists them. The three on by
 * default are the ones that are never music: talking and skits in a music video, paid reads and
 * plugs. Intros and outros are off because on a song "intro" can mean the song's own opening.
 */
enum class SkipCategory(val api: String, val label: String, val hint: String, val defaultOn: Boolean) {
    MUSIC_OFFTOPIC("music_offtopic", "Non-music parts", "Talking, skits and silence in music videos", true),
    SPONSOR("sponsor", "Sponsors", "Paid promotions in podcasts and videos", true),
    SELFPROMO("selfpromo", "Self-promotion", "Merch, shout-outs and plugs", true),
    INTERACTION("interaction", "Interaction reminders", "\"Like and subscribe\"", false),
    INTRO("intro", "Intros", "Title cards and intro animations", false),
    OUTRO("outro", "Outros", "End cards and credits", false),
    PREVIEW("preview", "Previews and recaps", "What's coming up, or what happened before", false),
    FILLER("filler", "Tangents", "Jokes and asides off the topic", false);

    companion object {
        fun of(api: String) = entries.firstOrNull { it.api == api }
        val defaults: Set<String> = entries.filter { it.defaultOn }.map { it.api }.toSet()
    }
}

/** One skippable stretch of a video, in milliseconds. */
data class Segment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
    /** The video's length when the segment was submitted, 0 when the submitter's client did not say. */
    val videoDurationMs: Long = 0,
    val votes: Int = 0,
    val locked: Boolean = false,
) {
    val lengthMs: Long get() = endMs - startMs
    operator fun contains(positionMs: Long) = positionMs in startMs until endMs
}

object SponsorBlockApi {
    const val BASE_URL = "https://sponsor.ajay.app"

    /**
     * The first four hex digits of the video id's SHA-256. The server answers with every video
     * sharing that prefix and Lyrenne picks its own out locally, so SponsorBlock never learns
     * which video is playing.
     */
    fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
            .take(2).joinToString("") { "%02x".format(it) }

    fun url(videoId: String, categories: Collection<String> = SkipCategory.entries.map { it.api }, base: String = BASE_URL): String {
        val cats = categories.joinToString(",", "[", "]") { "\"$it\"" }
        return "$base/api/skipSegments/${hashPrefix(videoId)}" +
            "?categories=${URLEncoder.encode(cats, Charsets.UTF_8)}" +
            "&actionTypes=${URLEncoder.encode("[\"skip\"]", Charsets.UTF_8)}"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [videoId]'s skip segments out of a hash-prefix response, which lists other videos too. Only
     * `skip` segments: `mute`, `full` (a label for the whole video) and `poi` are not skips.
     */
    fun parse(body: String, videoId: String): List<Segment> {
        val videos = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        val mine = videos.mapNotNull { it as? JsonObject }
            .firstOrNull { it["videoID"]?.jsonPrimitive?.contentOrNull == videoId } ?: return emptyList()
        return mine["segments"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if ((o["actionType"]?.jsonPrimitive?.contentOrNull ?: "skip") != "skip") return@mapNotNull null
            val range = o["segment"]?.jsonArray ?: return@mapNotNull null
            if (range.size < 2) return@mapNotNull null
            val start = (range[0].jsonPrimitive.double * 1000).toLong()
            val end = (range[1].jsonPrimitive.double * 1000).toLong()
            if (end <= start) return@mapNotNull null
            Segment(
                uuid = o["UUID"]?.jsonPrimitive?.contentOrNull ?: "$start-$end",
                category = o["category"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                startMs = start.coerceAtLeast(0),
                endMs = end,
                videoDurationMs = ((o["videoDuration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
                votes = o["votes"]?.jsonPrimitive?.intOrNull ?: 0,
                locked = (o["locked"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
            )
        }.sortedBy { it.startMs }
    }

    /** Every category's segments for [videoId]; empty when it has none (the server's 404). */
    fun fetch(transport: HttpTransport, videoId: String): List<Segment> {
        val r = transport.get(url(videoId))
        return when (r.code) {
            200 -> parse(r.body, videoId)
            404 -> emptyList()
            else -> throw java.io.IOException("SponsorBlock answered HTTP ${r.code}")
        }
    }
}
