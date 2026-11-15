package com.lyrenne.desktop.sound.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A frequency response in dB, as frequency and gain points sorted by frequency. */
class EqCurve(points: List<Pair<Double, Double>>) {
    val points: List<Pair<Double, Double>> = points.filter { it.first > 0 }.sortedBy { it.first }

    init {
        require(this.points.isNotEmpty()) { "a curve needs at least one point" }
    }

    /** Gain at [hz], linear between points on a log frequency axis, flat past either end. */
    fun at(hz: Double): Double {
        val p = points
        if (hz <= p.first().first) return p.first().second
        if (hz >= p.last().first) return p.last().second
        var lo = 0
        var hi = p.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (p[mid].first <= hz) lo = mid else hi = mid
        }
        val (f0, g0) = p[lo]
        val (f1, g1) = p[hi]
        val t = ln(hz / f0) / ln(f1 / f0)
        return g0 + (g1 - g0) * t
    }

    /** The same shape moved so it reads 0 dB at [hz] (1 kHz unless said otherwise). */
    fun normalizedAt(hz: Double = 1000.0): EqCurve {
        val ref = at(hz)
        return EqCurve(points.map { it.first to it.second - ref })
    }

    companion object {
        /** AutoEq's and Equalizer APO's `GraphicEQ: 20 -0.2; 21 -0.2; ...`. Null when [text] has none. */
        fun parseGraphicEq(text: String): EqCurve? {
            val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("GraphicEQ:", ignoreCase = true) } ?: return null
            val pts = line.substringAfter(':').split(';').mapNotNull { pair ->
                val parts = pair.trim().split(Regex("\\s+"))
                if (parts.size < 2) null else {
                    val f = parts[0].toDoubleOrNull()
                    val g = parts[1].toDoubleOrNull()
                    if (f == null || g == null || f <= 0) null else f to g
                }
            }
            return if (pts.size >= 2) EqCurve(pts) else null
        }

        /**
         * Equalizer APO's parametric format, which AutoEq's ParametricEQ.txt uses:
         * `Filter 1: ON PK Fc 105 Hz Gain 6.5 dB Q 0.70`. Peaking, low shelf and high shelf, as
         * RBJ biquads at 48 kHz. The file's own preamp is left out: Lyrenne works out its own.
         */
        fun parseParametric(text: String): EqCurve? {
            val filters = text.lineSequence().mapNotNull { Biquad.parse(it) }.toList()
            if (filters.isEmpty()) return null
            return EqCurve(logGrid(20.0, 20_000.0, 256).map { f -> f to filters.sumOf { it.dbAt(f) } })
        }
    }
}

/** [n] frequencies from [from] to [to], evenly spaced on a log axis. */
fun logGrid(from: Double, to: Double, n: Int): List<Double> =
    (0 until n).map { i -> from * (to / from).pow(i.toDouble() / (n - 1)) }

/** One parametric filter (RBJ Audio EQ Cookbook). */
internal class Biquad(private val b: DoubleArray, private val a: DoubleArray) {
    fun dbAt(hz: Double, rate: Double = RATE): Double {
        val w = 2 * PI * hz / rate
        val (nr, ni) = poly(b, w)
        val (dr, di) = poly(a, w)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    private fun poly(c: DoubleArray, w: Double): Pair<Double, Double> =
        (c[0] + c[1] * cos(-w) + c[2] * cos(-2 * w)) to (c[1] * sin(-w) + c[2] * sin(-2 * w))

    companion object {
        const val RATE = 48_000.0
        private val LINE = Regex(
            """Filter\s*\d*\s*:\s*ON\s+(PK|PEQ|LSC|LS|HSC|HS)\b.*?Fc\s+([\d.]+)\s*Hz(?:.*?Gain\s+(-?[\d.]+)\s*dB)?(?:.*?Q\s+([\d.]+))?""",
            RegexOption.IGNORE_CASE
        )

        fun parse(line: String): Biquad? {
            val m = LINE.find(line) ?: return null
            val type = m.groupValues[1].uppercase()
            val fc = m.groupValues[2].toDoubleOrNull() ?: return null
            val gain = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val q = m.groupValues[4].toDoubleOrNull() ?: 0.707
            return when (type) {
                "PK", "PEQ" -> peaking(fc, gain, q)
                "LSC", "LS" -> shelf(fc, gain, q, low = true)
                else -> shelf(fc, gain, q, low = false)
            }
        }

        fun peaking(fc: Double, gainDb: Double, q: Double, rate: Double = RATE): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * fc / rate
            val alpha = sin(w0) / (2 * q)
            return Biquad(
                doubleArrayOf(1 + alpha * a, -2 * cos(w0), 1 - alpha * a),
                doubleArrayOf(1 + alpha / a, -2 * cos(w0), 1 - alpha / a)
            )
        }

        fun shelf(fc: Double, gainDb: Double, q: Double, low: Boolean, rate: Double = RATE): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * fc / rate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val s = 2 * sqrt(a) * alpha
            return if (low) Biquad(
                doubleArrayOf(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s)),
                doubleArrayOf((a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
            ) else Biquad(
                doubleArrayOf(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s)),
                doubleArrayOf((a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
            )
        }
    }
}

/**
 * VLC's 10-band equalizer, modelled from its source (modules/audio_filter/equalizer.c):
 * `out = preamp * (0.25 * x + sum(amp_j * bandpass_j(x)))` with `amp_j = 0.25 * (10^(dB_j/20) - 1)`
 * and one-octave bandpass filters.
 *
 * Two things follow that matter here. A band's dB is the boost at its centre only when its
 * neighbours sit at 0: VLC's bands overlap (12, 14 and 16 kHz are a third of an octave apart),
 * so setting each band to the target at its centre overshoots where they meet. And the 0.25 means
 * the preamp is unity at 20*log10(4) = 12.04 dB, which is why VLC's Flat preset says 12.
 */
class VlcEqModel(val bandsHz: List<Double>, private val rate: Double = 48_000.0) {
    private class Band(val alpha: Double, val beta: Double, val gamma: Double)

    private val bands = bandsHz.map { f ->
        if (f > rate / 2) Band(0.0, 0.0, 0.0) else {
            val octave = 2.0.pow(0.5)
            val o1 = 0.5 * (octave + 1)
            val o2 = 0.5 * (octave - 1)
            val t1 = 2 * PI * f / rate
            val t2 = t1 / octave
            val sinT = sin(t2)
            val prd = sin(t2 * o1) * sin(t2 * o2)
            val half = sinT * 0.5
            val den = half + prd
            Band(prd / den, (half - prd) / den, sinT * cos(t1) / den)
        }
    }

    /** The response, in dB, at [hz] with these band gains and VLC preamp. */
    fun responseDb(hz: Double, gainsDb: List<Double>, preampDb: Double = UNITY_PREAMP_DB): Double {
        val w = 2 * PI * hz / rate
        var re = IN_FACTOR
        var im = 0.0
        bands.forEachIndexed { j, b ->
            val amp = IN_FACTOR * (10.0.pow(gainsDb[j] / 20) - 1)
            // H(z) = alpha (1 - z^-2) / (1 - gamma z^-1 + beta z^-2), at z = e^(i w)
            val nr = b.alpha * (1 - cos(2 * w))
            val ni = b.alpha * sin(2 * w)
            val dr = 1 - b.gamma * cos(w) + b.beta * cos(2 * w)
            val di = b.gamma * sin(w) - b.beta * sin(2 * w)
            val d2 = dr * dr + di * di
            if (d2 > 0) {
                re += amp * (nr * dr + ni * di) / d2
                im += amp * (ni * dr - nr * di) / d2
            }
        }
        val g = 10.0.pow(preampDb / 20)
        return 20 * log10(g * sqrt(re * re + im * im)).coerceAtLeast(-200.0)
    }

    companion object {
        const val IN_FACTOR = 0.25
        /** The VLC preamp that leaves the level alone: 20*log10(1 / 0.25). */
        val UNITY_PREAMP_DB = 20 * log10(1 / IN_FACTOR)
    }
}

/** Band gains and preamp for VLC, ready to apply, with how closely they follow the target. */
data class EqFit(val preampDb: Float, val bandsDb: List<Float>, val rmsErrorDb: Double)

object EqFitter {
    /** VLC's own band limit is +-20 dB; a correction past 12 dB is a measurement artefact, not a fix. */
    const val MAX_GAIN_DB = 12.0

    /**
     * Fits VLC's bands, centred at [bandsHz] (read from vlcj, so another libvlc's table works), to
     * [target], a correction curve in dB.
     *
     * Not just the target read off at each centre: that sets a broad band from whatever narrow
     * peak or notch the curve has at that exact frequency, and ignores that VLC's bands overlap.
     * Instead the bands are solved together, by damped least squares over 20 Hz to 20 kHz against
     * [VlcEqModel], so the response VLC will really produce follows the curve as well as ten
     * one-octave bands can. Regularisation ([ridge]) keeps the crowded 12/14/16 kHz trio from trading
     * huge opposite gains.
     *
     * The curve is moved to read 0 dB at 1 kHz first, so the sliders read as cuts and boosts. The
     * preamp then takes away the largest boost the model finds anywhere, so nothing clips.
     */
    fun fit(target: EqCurve, bandsHz: List<Double>, ridge: Double = 1.0): EqFit {
        require(bandsHz.isNotEmpty())
        val model = VlcEqModel(bandsHz)
        val curve = target.normalizedAt(1000.0)
        val grid = logGrid(20.0, 20_000.0, 160)
        val wanted = grid.map { curve.at(it) }
        // Above 16 kHz little is audible and AutoEq's own targets roll off: weigh it lightly
        val weight = grid.map { if (it > 16_000) 0.3 else 1.0 }
        val n = bandsHz.size
        val g = DoubleArray(n) { curve.at(bandsHz[it]).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) }
        var lambda = 1.0
        fun residuals(gains: DoubleArray) = grid.indices.map { i ->
            (model.responseDb(grid[i], gains.toList()) - wanted[i]) * weight[i]
        }
        fun cost(gains: DoubleArray) = residuals(gains).sumOf { it * it } + ridge * gains.sumOf { it * it }
        var current = cost(g)
        repeat(40) {
            val r = residuals(g)
            // Jacobian by finite differences: 10 bands x 160 points, cheap
            val jac = Array(n) { j ->
                val h = 0.05
                val gh = g.copyOf().also { it[j] += h }
                val rh = residuals(gh)
                DoubleArray(grid.size) { i -> (rh[i] - r[i]) / h }
            }
            // (J^T J + (lambda + ridge) I) delta = -(J^T r + ridge g)
            val a = Array(n) { p -> DoubleArray(n) { q -> (0 until grid.size).sumOf { i -> jac[p][i] * jac[q][i] } } }
            val b = DoubleArray(n) { p -> -((0 until grid.size).sumOf { i -> jac[p][i] * r[i] } + ridge * g[p]) }
            for (p in 0 until n) a[p][p] += lambda * (1 + a[p][p]) + ridge
            val delta = solve(a, b) ?: return@repeat
            val trial = DoubleArray(n) { (g[it] + delta[it]).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) }
            val trialCost = cost(trial)
            if (trialCost < current) {
                trial.copyInto(g); current = trialCost; lambda = max(lambda / 3, 1e-4)
            } else {
                lambda *= 4
            }
        }
        val gains = g.map { round1(it) }
        val peak = grid.maxOf { model.responseDb(it, gains, VlcEqModel.UNITY_PREAMP_DB) }
        // Rounded down, so rounding can never be what clips
        val preamp = floor((VlcEqModel.UNITY_PREAMP_DB - max(peak, 0.0)) * 10) / 10
        return EqFit(preamp.coerceIn(-20.0, 20.0).toFloat(), gains.map { it.toFloat() }, rmsError(target, bandsHz, gains))
    }

    /**
     * How far VLC's response with [gainsDb] strays from [target] (normalized at 1 kHz), as a weighted
     * RMS in dB over 20 Hz to 20 kHz.
     */
    fun rmsError(target: EqCurve, bandsHz: List<Double>, gainsDb: List<Double>): Double {
        val model = VlcEqModel(bandsHz)
        val curve = target.normalizedAt(1000.0)
        val grid = logGrid(20.0, 20_000.0, 160)
        val weight = grid.map { if (it > 16_000) 0.3 else 1.0 }
        return sqrt(grid.indices.sumOf { i ->
            val e = model.responseDb(grid[i], gainsDb) - curve.at(grid[i])
            e * e * weight[i]
        } / weight.sum())
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    /** Gaussian elimination with partial pivoting; null for a singular system. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (c in 0 until n) {
            val p = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[p][c]) < 1e-12) return null
            m[c] = m[p].also { m[p] = m[c] }
            v[c] = v[p].also { v[p] = v[c] }
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c until n) m[r][k] -= f * m[c][k]
                v[r] -= f * v[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            x[r] = (v[r] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
        }
        return x
    }
}
