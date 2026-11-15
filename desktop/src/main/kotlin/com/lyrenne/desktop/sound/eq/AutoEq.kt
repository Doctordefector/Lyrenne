package com.lyrenne.desktop.sound.eq

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.sound.HttpTransport
import com.lyrenne.desktop.sound.OkHttpTransport
import timber.log.Timber
import java.io.File
import java.io.IOException

/** One measured headphone in the AutoEq project's results. */
data class AutoEqEntry(
    val name: String,
    /** URL-encoded path under results/, e.g. `oratory1990/over-ear/Sennheiser%20HD%20600`. */
    val path: String,
    /** Who measured it (oratory1990, crinacle, Rtings...). */
    val source: String,
    /** The measurement rig, when the source used more than one. */
    val rig: String? = null,
) {
    val label: String get() = if (rig != null) "$source on $rig" else source
}

/**
 * Headphone corrections from the AutoEq project (github.com/jaakkopasanen/AutoEq), read straight
 * from its repository: `results/INDEX.md` lists every model, and each model's folder has a
 * `<name> GraphicEQ.txt` with the correction curve. No API, no key. The index (about 850 KB) is
 * cached for two weeks and used stale when GitHub cannot be reached; profiles are cached for good.
 */
class AutoEq(
    private val transport: HttpTransport = OkHttpTransport,
    private val cacheDir: File = File(AppPaths.cacheDir, "autoeq"),
    private val baseUrl: String = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results",
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var entries: List<AutoEqEntry>? = null

    /** The index, from memory, the cache or GitHub. Throws IOException when none of them has it. */
    fun index(): List<AutoEqEntry> {
        entries?.let { return it }
        val file = File(cacheDir, "INDEX.md")
        val fresh = file.exists() && now() - file.lastModified() < INDEX_MAX_AGE_MS
        val text = if (fresh) file.readText() else try {
            val r = transport.get("$baseUrl/INDEX.md")
            if (r.code != 200) throw IOException("GitHub answered HTTP ${r.code}")
            cacheDir.mkdirs()
            AppPaths.writeAtomic(file, r.body)
            r.body
        } catch (e: IOException) {
            if (!file.exists()) throw e
            Timber.i("AutoEq index refresh failed, using the cached one: ${e.message}")
            file.readText()
        }
        return parseIndex(text).also { entries = it }
    }

    /** The correction curve for [entry]. */
    fun curve(entry: AutoEqEntry): EqCurve {
        val folder = File(cacheDir, "profiles").apply { mkdirs() }
        val cached = File(folder, entry.path.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt")
        val text = if (cached.exists()) cached.readText() else {
            val r = transport.get(profileUrl(entry))
            if (r.code != 200) throw IOException("No correction for ${entry.name} (HTTP ${r.code})")
            AppPaths.writeAtomic(cached, r.body)
            r.body
        }
        return EqCurve.parseGraphicEq(text) ?: throw IOException("The correction for ${entry.name} could not be read")
    }

    fun profileUrl(entry: AutoEqEntry): String =
        "$baseUrl/${entry.path}/${entry.path.substringAfterLast('/')}%20GraphicEQ.txt"

    companion object {
        const val INDEX_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

        private val LINE = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?$""")

        /** `- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990` */
        fun parseIndex(markdown: String): List<AutoEqEntry> = markdown.lineSequence().mapNotNull { line ->
            val m = LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val (name, path, source, rig) = m.destructured
            AutoEqEntry(name, path, source, rig.ifBlank { null })
        }.toList()

        /** Measurement sources in the order their corrections are offered first. */
        private val PREFERRED = listOf("oratory1990", "crinacle", "Rtings", "Innerfidelity", "Headphone.com Legacy")

        /**
         * Entries whose name holds every word of [query], best first: names that start with the
         * query, then the better-known measurement sources, then shorter names.
         */
        fun search(entries: List<AutoEqEntry>, query: String, limit: Int = 60): List<AutoEqEntry> {
            val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyList()
            val q = query.trim().lowercase()
            return entries.asSequence()
                .filter { e -> val n = e.name.lowercase(); words.all { it in n } }
                .sortedWith(compareBy<AutoEqEntry>(
                    { if (it.name.lowercase().startsWith(q)) 0 else 1 },
                    { it.name.length },
                    { it.name.lowercase() },
                    { PREFERRED.indexOf(it.source).let { i -> if (i < 0) PREFERRED.size else i } },
                ))
                .take(limit)
                .toList()
        }
    }
}
