package com.lyrenne.desktop.sound.eq

import com.lyrenne.desktop.sound.HttpResult
import com.lyrenne.desktop.sound.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import kotlin.math.abs

/**
 * The band table vlcj reports for libvlc 3's equalizer: the ISO octaves. VLC's own player uses
 * another (60 Hz to 16 kHz, see [VLC_FREQS]), which is why the app reads it rather than assuming.
 */
private val VLC_BANDS = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
private val VLC_FREQS = listOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)
private val VLC_BANDS_D = VLC_BANDS.map { it.toDouble() }

private fun fixture(name: String) = AutoEqTest::class.java.getResource("/sound/$name")!!.readText()

class AutoEqTest {

    @Test
    fun `index lines parse into name, path, source and rig`() {
        val entries = AutoEq.parseIndex(fixture("autoeq-index.md"))
        assertEquals(10, entries.size)
        val oratory = entries.first { it.source == "oratory1990" }
        assertEquals("Sennheiser HD 600", oratory.name)
        assertEquals("oratory1990/over-ear/Sennheiser%20HD%20600", oratory.path)
        assertNull(oratory.rig)
        val rtings = entries.first { it.source == "Rtings" }
        assertEquals("Bruel & Kjaer 5128", rtings.rig)
        val aero = entries.first { it.name.startsWith("1MORE Aero") }
        assertEquals("1MORE Aero (ANC Off)", aero.name)
        assertEquals("HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)", aero.path)
    }

    @Test
    fun `search matches every word and puts the best-known measurement first`() {
        val entries = AutoEq.parseIndex(fixture("autoeq-index.md"))
        val hits = AutoEq.search(entries, "hd 600")
        assertEquals(6, hits.size)
        assertEquals("oratory1990", hits.first().source)
        assertTrue(AutoEq.search(entries, "aero anc").single().name == "1MORE Aero (ANC Off)")
        assertTrue(AutoEq.search(entries, "  ").isEmpty())
    }

    @Test
    fun `profile URL points at the GraphicEQ file inside the model folder`() {
        val aero = AutoEq.parseIndex(fixture("autoeq-index.md")).first { it.name.startsWith("1MORE Aero") }
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/HypetheSonics/GRAS%20RA0045%20in-ear/" +
                "1MORE%20Aero%20(ANC%20Off)/1MORE%20Aero%20(ANC%20Off)%20GraphicEQ.txt",
            AutoEq().profileUrl(aero)
        )
    }

    @Test
    fun `index is cached, refreshed when old, and used stale when offline`() {
        val dir = Files.createTempDirectory("autoeq").toFile()
        var calls = 0
        var offline = false
        val transport = HttpTransport { _, url, _, _ ->
            calls++
            if (offline) throw IOException("offline")
            assertTrue(url.endsWith("/INDEX.md"))
            HttpResult(200, fixture("autoeq-index.md"))
        }
        var clock = System.currentTimeMillis()
        assertEquals(10, AutoEq(transport, dir, now = { clock }).index().size)
        assertEquals(1, calls)
        // A new session within two weeks reads the cache
        assertEquals(10, AutoEq(transport, dir, now = { clock }).index().size)
        assertEquals(1, calls)
        // Three weeks on it tries GitHub, and offline it still has the old index
        clock += 21L * 24 * 3600 * 1000
        offline = true
        assertEquals(10, AutoEq(transport, dir, now = { clock }).index().size)
        assertEquals(2, calls)
        dir.deleteRecursively()
    }

    @Test
    fun `GraphicEQ text parses into a curve`() {
        val curve = EqCurve.parseGraphicEq(fixture("hd600-graphiceq.txt"))!!
        assertTrue(curve.points.size > 100)
        assertEquals(20.0, curve.points.first().first, 0.0)
        assertEquals(-0.2, curve.at(20.0), 1e-9)
        // Between two points it interpolates on a log axis
        val a = curve.at(20.0); val b = curve.at(21.0)
        assertTrue(curve.at(20.5) in minOf(a, b)..maxOf(a, b))
        assertNull(EqCurve.parseGraphicEq("nothing here"))
    }

    @Test
    fun `VLC model is unity at preamp 12 with flat bands`() {
        val m = VlcEqModel(VLC_BANDS_D)
        for (f in logGrid(20.0, 20000.0, 50)) assertEquals(0.0, m.responseDb(f, List(10) { 0.0 }), 0.01)
        assertEquals(12.04, VlcEqModel.UNITY_PREAMP_DB, 0.01)
    }

    @Test
    fun `one VLC band boosts its centre and leaves far frequencies alone`() {
        val m = VlcEqModel(VLC_BANDS_D)
        val gains = List(10) { if (it == 5) 6.0 else 0.0 } // 1 kHz
        assertEquals(6.0, m.responseDb(1000.0, gains), 0.5)
        assertEquals(0.0, m.responseDb(60.0, gains), 0.5)
        assertEquals(0.0, m.responseDb(16000.0, gains), 0.5)
    }

    @Test
    fun `a flat target fits to flat bands at unity`() {
        val fit = EqFitter.fit(EqCurve(listOf(20.0 to 0.0, 20000.0 to 0.0)), VLC_BANDS_D)
        fit.bandsDb.forEach { assertEquals(0f, it, 0.1f) }
        assertEquals(12.0f, fit.preampDb, 0.05f)
    }

    @Test
    fun `a bass shelf lands on the low bands and the preamp leaves headroom`() {
        // +6 dB below 80 Hz, 0 above 200 Hz
        val target = EqCurve(listOf(20.0 to 6.0, 80.0 to 6.0, 200.0 to 0.0, 20000.0 to 0.0))
        val fit = EqFitter.fit(target, VLC_BANDS_D)
        assertTrue("31 Hz ${fit.bandsDb[0]}", fit.bandsDb[0] > 4f)
        assertEquals(0f, fit.bandsDb[4], 0.7f)
        assertEquals(0f, fit.bandsDb[9], 0.7f)
        assertNoClipping(fit)
    }

    /**
     * The known fixture: oratory1990's HD 600 correction. The pinned numbers are what this fit gives;
     * the checks that matter are that VLC's modelled response follows the correction closely and
     * never rises above 0 dB.
     */
    @Test
    fun `HD 600 correction fits VLC bands within a dB and never clips`() {
        val curve = EqCurve.parseGraphicEq(fixture("hd600-graphiceq.txt"))!!
        val fit = EqFitter.fit(curve, VLC_BANDS_D)
        println("HD 600 fit: preamp ${fit.preampDb}, bands ${fit.bandsDb}, rms ${"%.2f".format(fit.rmsErrorDb)} dB")
        // Better than the obvious approach, reading the curve off at each band's centre
        val norm0 = curve.normalizedAt()
        val naive = EqFitter.rmsError(curve, VLC_BANDS_D, VLC_BANDS_D.map { norm0.at(it) })
        println("naive centre reading: rms ${"%.2f".format(naive)} dB")
        assertTrue("fit ${fit.rmsErrorDb} vs naive $naive", fit.rmsErrorDb < naive)
        assertTrue("rms ${fit.rmsErrorDb}", fit.rmsErrorDb < 2.0)
        // oratory1990's HD 600 wants a bass shelf (+6.5 dB under 105 Hz) and little else
        assertTrue("31 Hz ${fit.bandsDb[0]}", fit.bandsDb[0] in 3f..9f)
        assertEquals(0f, fit.bandsDb[4], 1.5f)
        assertNoClipping(fit)
        // Pinned, so a change to the model or the fit shows up here first
        val pinned = listOf(7.0f, 2.5f, -1.4f, -1.1f, 0.9f, -0.3f, 0.3f, -0.8f, 3.7f, -2.4f)
        pinned.zip(fit.bandsDb).forEachIndexed { i, (want, got) -> assertEquals("band $i", want, got, 0.3f) }
        assertEquals(4.8f, fit.preampDb, 0.3f)
        // Read at the centres, the response follows the normalized curve within 2.5 dB
        val norm = curve.normalizedAt()
        val model = VlcEqModel(VLC_BANDS_D)
        VLC_BANDS_D.forEach { f ->
            if (f <= 8000) assertEquals("at $f Hz", norm.at(f), model.responseDb(f, fit.bandsDb.map { it.toDouble() }), 2.5)
        }
    }

    @Test
    fun `band count and positions come from the table passed in`() {
        val curve = EqCurve.parseGraphicEq(fixture("hd600-graphiceq.txt"))!!
        val isoFit = EqFitter.fit(curve, VLC_FREQS)
        val vlcFit = EqFitter.fit(curve, VLC_BANDS_D)
        assertEquals(10, isoFit.bandsDb.size)
        assertTrue(isoFit.bandsDb != vlcFit.bandsDb)
        assertEquals(5, EqFitter.fit(curve, listOf(60.0, 250.0, 1000.0, 4000.0, 12000.0)).bandsDb.size)
    }

    @Test
    fun `parametric text gives the same shape as the graphic one`() {
        val graphic = EqCurve.parseGraphicEq(fixture("hd600-graphiceq.txt"))!!.normalizedAt()
        val parametric = EqCurve.parseParametric(fixture("hd600-parametriceq.txt"))!!.normalizedAt()
        for (f in logGrid(40.0, 8000.0, 30)) assertEquals("at $f Hz", graphic.at(f), parametric.at(f), 1.5)
    }

    @Test
    fun `preset text round trips`() {
        val preset = EqPreset("My cans", 9.5f, VLC_BANDS, listOf(3f, 2f, 1f, 0f, -1f, -2f, 0.5f, 1.5f, 0f, -3f))
        val text = EqPresetText.export(preset)
        assertTrue(text, text.contains("Preamp: -2.5 dB"))
        assertTrue(text, text.contains("1 kHz: -2.0 dB"))
        assertTrue(text, text.contains("31 Hz: 3.0 dB"))
        val back = EqPresetText.import(text, VLC_BANDS)!!
        assertEquals("My cans", back.name)
        assertEquals(preset.preampDb, back.preampDb, 0.06f)
        assertEquals(preset.gainsDb, back.gainsDb)
    }

    @Test
    fun `importing AutoEq or Equalizer APO text fits it to the bands`() {
        val fromGraphic = EqPresetText.import(fixture("hd600-graphiceq.txt"), VLC_BANDS, "HD 600")!!
        assertEquals("HD 600", fromGraphic.name)
        assertEquals(10, fromGraphic.gainsDb.size)
        assertNotNull(EqPresetText.import(fixture("hd600-parametriceq.txt"), VLC_BANDS))
        assertNull(EqPresetText.import("hello", VLC_BANDS))
    }

    @Test
    fun `a preset from another band table is refitted, not misapplied`() {
        val other = VLC_FREQS.map { it.toFloat() }
        val text = EqPresetText.export(EqPreset("Bass", 12f, other, listOf(6f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
        val p = EqPresetText.import(text, VLC_BANDS)!!
        assertEquals(VLC_BANDS, p.bandsHz)
        assertTrue("31 Hz ${p.gainsDb[0]}", p.gainsDb[0] > 3f)
        assertTrue("62 Hz ${p.gainsDb[1]}", p.gainsDb[1] > 3f)
        assertEquals(0f, p.gainsDb[5], 1f)
    }

    private fun assertNoClipping(fit: EqFit) {
        val m = VlcEqModel(VLC_BANDS_D)
        val peak = logGrid(20.0, 20000.0, 400).maxOf { m.responseDb(it, fit.bandsDb.map { g -> g.toDouble() }, fit.preampDb.toDouble()) }
        assertTrue("peaks at $peak dB", peak <= 0.05)
        assertTrue(abs(fit.preampDb) <= 20f)
    }
}
