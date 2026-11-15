package com.lyrenne.desktop.movein

/**
 * A playlist pasted as text, which covers every service: one "Artist - Title" per line, or a CSV
 * or tab-separated export with a header row (Exportify, TuneMyMusic, Soundiiz, a Music app
 * export). Columns are found by their header names, so their order does not matter.
 */
object TextPlaylist {

    fun parse(text: String, name: String = "Imported playlist"): SourcePlaylist {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val firstReal = lines.firstOrNull { it.isNotBlank() }.orEmpty()
        val tracks = tableHeader(firstReal)?.let { parseTable(lines.dropWhile { it.isBlank() }, it) }
            ?: lines.mapNotNull(::parseLine)
        return SourcePlaylist(SourceService.TEXT, name, tracks)
    }

    // ------------------------------------------------------------------ tables

    private class Header(val sep: Char, val title: Int, val artist: Int, val album: Int, val duration: Int, val durationUnit: Unit)
    private enum class Unit { MS, SECONDS, CLOCK }

    private fun tableHeader(line: String): Header? {
        val sep = when {
            '\t' in line -> '\t'
            ';' in line && ',' !in line -> ';'
            ',' in line -> ','
            else -> return null
        }
        val cells = splitRow(line, sep).map { it.trim().lowercase() }
        fun find(vararg names: String) = cells.indexOfFirst { c -> names.any { c == it } }
        val title = find("track name", "title", "name", "song", "song name", "track", "track title")
        val artist = find("artist name(s)", "artist name", "artist", "artists", "artist(s)", "album artist")
        if (title < 0 || artist < 0) return null
        val album = find("album name", "album", "album title")
        val ms = find("duration (ms)", "duration_ms", "duration ms")
        val secs = find("time", "duration (s)", "duration_s", "seconds")
        val clock = find("duration", "length")
        val (dur, unit) = when {
            ms >= 0 -> ms to Unit.MS
            secs >= 0 -> secs to Unit.SECONDS
            clock >= 0 -> clock to Unit.CLOCK
            else -> -1 to Unit.MS
        }
        return Header(sep, title, artist, album, dur, unit)
    }

    private fun parseTable(lines: List<String>, h: Header): List<SourceTrack> {
        // Quoted cells may hold newlines, so rows are rebuilt before splitting.
        val rows = ArrayList<String>()
        val pending = StringBuilder()
        for (line in lines.drop(1)) {
            if (pending.isNotEmpty()) pending.append('\n')
            pending.append(line)
            if (pending.count { it == '"' } % 2 == 0) {
                rows += pending.toString()
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) rows += pending.toString()
        return rows.filter { it.isNotBlank() }.mapNotNull { row ->
            val cells = splitRow(row, h.sep)
            val title = cells.getOrNull(h.title)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val duration = cells.getOrNull(h.duration)?.trim()?.let { v ->
                when (h.durationUnit) {
                    Unit.MS -> v.toDoubleOrNull()?.toLong()
                    Unit.SECONDS -> v.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    Unit.CLOCK -> if (':' in v) PlaylistSources.clockToMs(v) else v.toDoubleOrNull()?.let {
                        // A bare number: milliseconds if it is too large to be seconds
                        if (it > 20_000) it.toLong() else (it * 1000).toLong()
                    }
                }
            }
            SourceTrack(
                title = title,
                artists = PlaylistSources.splitArtists(cells.getOrNull(h.artist).orEmpty()),
                album = cells.getOrNull(h.album)?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = duration?.takeIf { it > 0 }
            )
        }
    }

    /** One CSV row, honouring quotes and doubled quotes. */
    internal fun splitRow(row: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                quoted && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    // ------------------------------------------------------------------ plain lines

    private val numbering = Regex("""^\s*(?:\d{1,4}[.)]\s+|\d{1,4}\s+-\s+|[-*\u2022]\s+)""")
    private val trailingTime = Regex("""\s*[(\[]?(\d{1,2}:\d{2}(?::\d{2})?)[)\]]?\s*$""")
    private val dashes = listOf(" - ", " \u2013 ", " \u2014 ", " ~ ")

    /** "Artist - Title", "Title by Artist", "Artist<TAB>Title", with optional "1." and "(3:45)". */
    internal fun parseLine(raw: String): SourceTrack? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return null
        line = line.replace(numbering, "")
        var duration: Long? = null
        trailingTime.find(line)?.let { m ->
            duration = PlaylistSources.clockToMs(m.groupValues[1])
            line = line.substring(0, m.range.first).trim()
        }
        if (line.isEmpty()) return null
        if ('\t' in line) {
            val (a, t) = line.split('\t', limit = 2)
            if (a.isNotBlank() && t.isNotBlank()) return SourceTrack(t.trim(), PlaylistSources.splitArtists(a), null, duration)
        }
        for (d in dashes) {
            val at = line.indexOf(d)
            if (at > 0) {
                val artist = line.substring(0, at)
                val title = line.substring(at + d.length)
                if (title.isNotBlank()) return SourceTrack(title.trim(), PlaylistSources.splitArtists(artist), null, duration)
            }
        }
        Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(line)?.let { m ->
            return SourceTrack(m.groupValues[1].trim(), PlaylistSources.splitArtists(m.groupValues[2]), null, duration)
        }
        // A bare title still searches fine, it just matches on less
        return SourceTrack(line, emptyList(), null, duration)
    }
}
