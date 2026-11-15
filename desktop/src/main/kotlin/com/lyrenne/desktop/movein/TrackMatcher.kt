package com.lyrenne.desktop.movein

import java.text.Normalizer
import kotlin.math.abs

/** A YouTube Music result, reduced to what scoring needs. */
data class Candidate(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationSec: Int? = null,
    val thumbnailUrl: String? = null,
    /** A song (audio track) rather than a music video or an upload. */
    val isSong: Boolean = true,
    /** A plain upload, not an official music video. Worse than either. */
    val isUserVideo: Boolean = false,
    val explicit: Boolean = false,
)

data class ScoredCandidate(val candidate: Candidate, val score: Double)

enum class MatchQuality { GOOD, UNSURE, NONE }

data class TrackMatch(
    val source: SourceTrack,
    /** Best first. May be non-empty even when [quality] is NONE, to pick from by hand. */
    val options: List<ScoredCandidate>,
    /** The one that will be saved; the user can change it. Null means skip. */
    val chosen: Candidate?,
    val quality: MatchQuality,
)

/**
 * Finds each source song on YouTube Music and says how sure it is.
 *
 * The score is 0 to 1: half title, three tenths artist, a fifth duration, minus penalties for a
 * different version (live, cover, karaoke, sped up...) that the source did not ask for, and a
 * little for a video where a song exists. Above [GOOD] is saved without a second look; between
 * [UNSURE] and [GOOD] is highlighted with its alternatives; below is listed as not found.
 * `TrackMatcherTest` holds the table of awkward cases this was tuned on.
 */
object TrackMatcher {
    const val GOOD = 0.72
    const val UNSURE = 0.45

    private val LIVE_ALBUM = Regex("""\blive (at|in|from|on)\b|\(live\)|\bunplugged\b|\blive\s*$""")

    // ------------------------------------------------------------------ normalising

    /**
     * Version words that change what a recording is. Remaster is not one: same performance.
     * The first group is safe to look for anywhere in a title. The second only counts inside
     * brackets or after a " - ", or "Live Forever" would be a live version.
     */
    private val anywhereTags = linkedMapOf(
        "karaoke" to Regex("""\bkaraoke\b"""),
        "cover" to Regex("""\bcover(ed)? by\b|\(cover\)|\[cover\]|\bcover version\b|\btribute\b|- cover\b"""),
        "sped up" to Regex("""\bsped ?up\b|\bspeed ?up\b|\bnightcore\b"""),
        "slowed" to Regex("""\bslowed\b"""),
        "8d" to Regex("""\b8d audio\b|\(8d\)"""),
    )
    private val bracketTags = linkedMapOf(
        "cover" to Regex("""\bcover\b"""),
        "instrumental" to Regex("""\binstrumental\b|\boff ?vocal\b"""),
        "live" to Regex("""\blive\b|\bunplugged\b|\bin concert\b"""),
        "remix" to Regex("""\bremix(ed)?\b|\brmx\b|\bbootleg\b"""),
        "acoustic" to Regex("""\bacoustic\b"""),
        "lyrics" to Regex("""\blyrics?\b|\blyric video\b"""),
    )
    private val versionWords = anywhereTags.values + bracketTags.values

    /** Words a source can carry in brackets that say nothing about which recording it is. */
    private val noiseInBrackets = Regex(
        """\b(remaster(ed)?|\d{4} remaster(ed)?|mono|stereo|deluxe|edition|version|radio edit|single|album|original|explicit|clean|bonus track|from .*|official( music)? (video|audio)|audio|video|hd|hq|4k|mv|m/v)\b"""
    )

    private val featRe = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
    private val featBareRe = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** Lowercase, accents gone (for Latin letters only), punctuation to spaces. Scripts without spaces survive whole. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        val sb = StringBuilder()
        for (ch in decomposed) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {
                    // Drop combining accents after Latin letters; keep them elsewhere (Indic vowels)
                    val prev = sb.lastOrNull()
                    if (prev == null || prev.code >= 0x250) sb.append(ch)
                }
                ch == '&' -> sb.append(" and ")
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
    }

    data class TitleParts(val core: String, val tags: Set<String>, val featured: List<String>)

    /** "Song (feat. X) - 2011 Remaster [Live]" into the song's name, its version tags and who is featured. */
    fun splitTitle(title: String): TitleParts {
        var t = title
        val featured = ArrayList<String>()
        featRe.findAll(t).forEach { featured += PlaylistSources.splitArtists(it.groupValues[1]) }
        t = t.replace(featRe, " ")
        featBareRe.find(t)?.let { featured += PlaylistSources.splitArtists(it.groupValues[1]); t = t.substring(0, it.range.first) }

        val lower = t.lowercase()
        val versionParts = (Regex("""[(\[]([^)\]]*)[)\]]""").findAll(lower).map { it.groupValues[1] } +
            listOfNotNull(Regex("""\s[-\u2013\u2014]\s(.*)$""").find(lower)?.groupValues?.get(1))).joinToString(" | ")
        val tags = anywhereTags.filter { (_, re) -> re.containsMatchIn(lower) }.keys.toMutableSet()
        tags += bracketTags.filter { (_, re) -> re.containsMatchIn(versionParts) }.keys

        // Drop bracketed parts and " - suffix" parts that are only version or noise words
        var core = t.replace(Regex("""[(\[]([^)\]]*)[)\]]""")) { m ->
            val inner = m.groupValues[1].lowercase()
            if (versionWords.any { it.containsMatchIn(inner) } || noiseInBrackets.containsMatchIn(inner)) " " else m.value
        }
        val dash = Regex("""\s[-\u2013\u2014]\s(.*)$""").find(core)
        if (dash != null) {
            val tail = dash.groupValues[1].lowercase()
            if (versionWords.any { it.containsMatchIn(tail) } || noiseInBrackets.containsMatchIn(tail)) {
                core = core.substring(0, dash.range.first)
            }
        }
        return TitleParts(normalize(core), tags, featured)
    }

    // ------------------------------------------------------------------ similarity

    private fun tokens(s: String) = s.split(' ').filter { it.isNotEmpty() }

    /** Dice over character bigrams: works for scripts without spaces (Japanese, Chinese, Thai). */
    private fun bigramDice(a: String, b: String): Double {
        val x = a.replace(" ", "")
        val y = b.replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.length < 2 || y.length < 2) return if (x == y) 1.0 else 0.0
        val bx = x.windowed(2).groupingBy { it }.eachCount()
        val by = y.windowed(2).groupingBy { it }.eachCount()
        val common = bx.entries.sumOf { (k, v) -> minOf(v, by[k] ?: 0) }
        return 2.0 * common / (x.length - 1 + y.length - 1)
    }

    private fun tokenDice(a: String, b: String): Double {
        val x = tokens(a).toSet()
        val y = tokens(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }

    fun titleSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val sim = maxOf(tokenDice(a, b), bigramDice(a, b))
        // One title wholly inside the other ("Song" vs "Song Part 2") is close but not equal
        val contained = if (a.contains(b) || b.contains(a)) 0.8 else 0.0
        return maxOf(sim, contained)
    }

    private fun sameArtist(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val x = a.removePrefix("the ")
        val y = b.removePrefix("the ")
        if (x == y) return true
        // "Beyoncé" vs "Beyonce" is handled by normalize; this is for "JAY Z" vs "JAY-Z" and short forms
        if (x.replace(" ", "") == y.replace(" ", "")) return true
        return (x.length >= 4 && y.contains(x)) || (y.length >= 4 && x.contains(y)) || bigramDice(x, y) >= 0.85
    }

    // ------------------------------------------------------------------ scoring

    fun score(source: SourceTrack, c: Candidate): Double {
        val s = splitTitle(source.title)
        val k = splitTitle(c.title)

        // A video titled "Artist - Song" puts the artist in the title
        var candCore = k.core
        val candArtists = (c.artists + k.featured).map(::normalize).filter { it.isNotEmpty() }
        if (!c.isSong) {
            for (a in candArtists) {
                if (candCore.startsWith("$a ")) { candCore = candCore.removePrefix("$a ").trim(); break }
            }
        }
        val title = titleSimilarity(s.core, candCore)

        val sourceArtists = (source.artists + s.featured).map(::normalize).filter { it.isNotEmpty() }.distinct()
        val artist = when {
            sourceArtists.isEmpty() -> 0.5
            candArtists.isEmpty() -> 0.3
            else -> {
                val hits = sourceArtists.count { sa -> candArtists.any { sameArtist(sa, it) } ||
                    // Credits YouTube folds into the title or the uploader name
                    normalize(c.title).contains(sa) }
                val primary = candArtists.any { sameArtist(sourceArtists.first(), it) }
                // The lead artist matters most; everyone else adds the rest
                (if (primary) 0.7 else 0.0) + 0.3 * hits / sourceArtists.size
            }
        }

        val duration = run {
            val src = source.durationMs?.let { it / 1000.0 }
            val cand = c.durationSec?.toDouble()
            if (src == null || cand == null || src <= 0 || cand <= 0) 0.6
            else when (abs(src - cand)) {
                in 0.0..3.0 -> 1.0
                in 3.0..8.0 -> 0.85
                in 8.0..20.0 -> 0.55
                in 20.0..45.0 -> 0.25
                else -> 0.0
            }
        }

        // A song off a live album is a live recording whatever its title says
        val candTags = k.tags.toMutableSet()
        if (c.album != null && LIVE_ALBUM.containsMatchIn(c.album.lowercase()) &&
            (source.album == null || !LIVE_ALBUM.containsMatchIn(source.album.lowercase()))) candTags += "live"

        var penalty = 0.0
        for (tag in candTags - s.tags) {
            penalty += when (tag) {
                "karaoke", "cover", "sped up", "slowed", "8d" -> 0.4
                "instrumental", "live", "remix" -> 0.3
                "acoustic" -> 0.2
                "lyrics" -> 0.03   // a lyric video is the right song, just not the best upload
                else -> 0.1
            }
        }
        // Asked for a version, got the plain one: wrong, but less wrong than the reverse
        for (tag in s.tags - candTags) if (tag != "lyrics") penalty += 0.15
        if (!c.isSong) penalty += if (c.isUserVideo) 0.1 else 0.04

        return (0.5 * title + 0.3 * artist + 0.2 * duration - penalty).coerceIn(0.0, 1.0)
    }

    fun rank(source: SourceTrack, candidates: List<Candidate>): List<ScoredCandidate> =
        candidates.distinctBy { it.videoId }
            .map { ScoredCandidate(it, score(source, it)) }
            // Ties go to songs over videos, then to the order YouTube gave
            .sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenByDescending { it.candidate.isSong })

    fun quality(score: Double) = when {
        score >= GOOD -> MatchQuality.GOOD
        score >= UNSURE -> MatchQuality.UNSURE
        else -> MatchQuality.NONE
    }

    /** The query sent to search: lead artist plus the song's name, without version noise. */
    fun query(source: SourceTrack): String {
        val parts = splitTitle(source.title)
        // Keep the words of tags the source asked for, so "Song (Live)" searches for live
        val core = source.title.replace(featRe, " ").let { t ->
            if (parts.tags.isEmpty()) t.replace(Regex("""[(\[][^)\]]*[)\]]"""), " ") else t
        }.replace(Regex("""\s[-\u2013\u2014]\s.*(remaster|version|edit|mono|stereo).*$""", RegexOption.IGNORE_CASE), " ")
        val lead = source.artists.firstOrNull().orEmpty()
        return "$lead $core".replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Matches one song. [search] runs a YouTube Music search: songs first, and only if no song
     * scores well, videos too, because many tracks (and most SoundCloud uploads) exist only as videos.
     */
    suspend fun match(source: SourceTrack, search: suspend (query: String, videos: Boolean) -> List<Candidate>): TrackMatch {
        val q = query(source)
        var ranked = rank(source, search(q, false).take(8))
        if ((ranked.firstOrNull()?.score ?: 0.0) < GOOD) {
            ranked = rank(source, ranked.map { it.candidate } + search(q, true).take(6))
        }
        val best = ranked.firstOrNull()
        val quality = quality(best?.score ?: 0.0)
        return TrackMatch(
            source = source,
            options = ranked.take(5),
            chosen = best?.candidate?.takeIf { quality != MatchQuality.NONE },
            quality = quality
        )
    }
}
