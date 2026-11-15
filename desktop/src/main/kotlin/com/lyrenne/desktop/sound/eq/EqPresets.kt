package com.lyrenne.desktop.sound.eq

import com.lyrenne.desktop.AppPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * A named equalizer setting. [preampDb] is VLC's own number (12.04 is unity, see [VlcEqModel]);
 * the bands keep their frequencies so a preset still applies on a libvlc with another band table.
 */
@Serializable
data class EqPreset(
    val name: String,
    val preampDb: Float,
    val bandsHz: List<Float>,
    val gainsDb: List<Float>,
) {
    /** Gains for VLC's [bandsHz]: the same numbers when the tables match, refitted when they do not. */
    fun gainsFor(bands: List<Float>): Pair<Float, List<Float>> {
        if (sameBands(bandsHz, bands)) return preampDb to gainsDb
        val fit = EqFitter.fit(EqCurve(bandsHz.zip(gainsDb).map { (f, g) -> f.toDouble() to g.toDouble() }), bands.map { it.toDouble() })
        return fit.preampDb to fit.bandsDb
    }
}

internal fun sameBands(a: List<Float>, b: List<Float>) =
    a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) <= y * 0.02f }

/**
 * Presets as plain text, to paste into a chat or a forum and back.
 *
 * ```
 * Lyrenne equalizer preset
 * Name: Sennheiser HD 600
 * Preamp: -4.1 dB
 * 60 Hz: 4.2 dB
 * ...
 * ```
 * The preamp is written relative to unchanged level (VLC's 12.04 dB is 0 here), the way every
 * other equalizer writes it. Importing also takes AutoEq and Equalizer APO text as it is: a
 * `GraphicEQ:` line or `Filter N: ON PK ...` lines, fitted to VLC's bands.
 */
object EqPresetText {
    private const val HEADER = "Lyrenne equalizer preset"
    private val BAND = Regex("""^\s*([\d.]+)\s*(k?)Hz\s*[:=]\s*(-?[\d.]+)\s*dB\s*$""", RegexOption.IGNORE_CASE)
    private val PREAMP = Regex("""^\s*Preamp\s*[:=]\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val NAME = Regex("""^\s*Name\s*[:=]\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun export(preset: EqPreset): String = buildString {
        appendLine(HEADER)
        appendLine("Name: ${preset.name}")
        appendLine("Preamp: ${fmt(preset.preampDb - VlcEqModel.UNITY_PREAMP_DB.toFloat())} dB")
        preset.bandsHz.zip(preset.gainsDb).forEach { (f, g) -> appendLine("${hz(f)}: ${fmt(g)} dB") }
    }.trimEnd()

    /** A preset for VLC's [bands] from any supported [text], or null when it is none of them. */
    fun import(text: String, bands: List<Float>, fallbackName: String = "Imported"): EqPreset? {
        val lines = text.lines()
        val name = lines.firstNotNullOfOrNull { NAME.find(it)?.groupValues?.get(1)?.trim() }?.take(60) ?: fallbackName
        val points = lines.mapNotNull { line ->
            BAND.find(line)?.let { m ->
                val f = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
                val g = m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null
                (if (m.groupValues[2].isNotEmpty()) f * 1000 else f) to g
            }
        }
        if (points.isNotEmpty()) {
            val preamp = lines.firstNotNullOfOrNull { PREAMP.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
            val own = EqPreset(name, (preamp ?: 0f) + VlcEqModel.UNITY_PREAMP_DB.toFloat(), points.map { it.first }, points.map { it.second.coerceIn(-20f, 20f) })
            if (sameBands(own.bandsHz, bands)) return own.copy(bandsHz = bands, preampDb = own.preampDb.coerceIn(-20f, 20f))
            val (p, g) = own.gainsFor(bands)
            return EqPreset(name, p, bands, g)
        }
        val curve = EqCurve.parseGraphicEq(text) ?: EqCurve.parseParametric(text) ?: return null
        val fit = EqFitter.fit(curve, bands.map { it.toDouble() })
        return EqPreset(name, fit.preampDb, bands, fit.bandsDb)
    }

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.1f", v).replace("-0.0", "0.0")
    private fun hz(f: Float) = if (f >= 1000 && f % 1000 == 0f) "${(f / 1000).toInt()} kHz" else "${f.toInt()} Hz"
}

/** The user's own presets, AutoEQ ones included, in `data/eq-presets.json`. */
object EqPresets {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(EqPreset.serializer())
    private val file: File get() = File(AppPaths.dataDir, "eq-presets.json")

    private val _presets = MutableStateFlow<List<EqPreset>?>(null)
    val presets: StateFlow<List<EqPreset>?> = _presets.asStateFlow()

    fun load(): List<EqPreset> {
        _presets.value?.let { return it }
        val list = try {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
        } catch (e: Exception) {
            Timber.w("Equalizer presets unreadable: ${e.message}")
            emptyList()
        }
        _presets.value = list
        return list
    }

    /** Saves [preset], replacing one of the same name. */
    @Synchronized
    fun save(preset: EqPreset) {
        val list = load().filterNot { it.name.equals(preset.name, ignoreCase = true) } + preset
        write(list.sortedBy { it.name.lowercase() })
    }

    @Synchronized
    fun delete(name: String) = write(load().filterNot { it.name == name })

    private fun write(list: List<EqPreset>) {
        _presets.value = list
        try {
            AppPaths.writeAtomic(file, json.encodeToString(serializer, list))
        } catch (e: Exception) {
            Timber.e("Equalizer presets not saved: ${e.message}")
        }
    }

    /** The saved preset these settings are, if any, so the picker can name it. */
    fun matching(preamp: Float, bands: List<Float>): EqPreset? = load().firstOrNull { p ->
        abs(p.preampDb - preamp) < 0.05f && p.gainsDb.size == bands.size && p.gainsDb.zip(bands).all { (a, b) -> abs(a - b) < 0.05f }
    }
}
