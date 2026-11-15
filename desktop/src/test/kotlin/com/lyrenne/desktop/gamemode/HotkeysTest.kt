package com.lyrenne.desktop.gamemode

import com.lyrenne.desktop.gamemode.HotkeyMod.ALT
import com.lyrenne.desktop.gamemode.HotkeyMod.CTRL
import com.lyrenne.desktop.gamemode.HotkeyMod.SHIFT
import com.lyrenne.desktop.gamemode.HotkeyMod.WIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.Properties

class HotkeysTest {

    @Test
    fun `formats modifiers in a fixed order whatever order they were pressed`() {
        val combo = KeyCombo(setOf(WIN, SHIFT, ALT, CTRL), HotkeyKeys.named("P")!!)
        assertEquals("Ctrl+Alt+Shift+Win+P", combo.format())
    }

    @Test
    fun `every key name survives a format and parse round trip`() {
        for (key in HotkeyKeys.all) {
            val combo = KeyCombo(setOf(CTRL, ALT), key)
            assertEquals(key.name, combo, KeyCombo.parse(combo.format()))
        }
    }

    @Test
    fun `a key name containing a plus sign still parses`() {
        val combo = KeyCombo.parse("Ctrl+Alt+Num +")
        assertEquals(KeyCombo.of("Num +", CTRL, ALT), combo)
    }

    @Test
    fun `parsing is forgiving about case, spacing and common aliases`() {
        assertEquals(KeyCombo.of("Page Down", CTRL, ALT), KeyCombo.parse(" control + alt + pgdn "))
        assertEquals(KeyCombo.of("Delete", CTRL, SHIFT), KeyCombo.parse("ctrl+shift+del"))
        assertEquals(KeyCombo.of("K", WIN), KeyCombo.parse("Super+k"))
    }

    @Test
    fun `junk does not parse`() {
        assertNull(KeyCombo.parse(""))
        assertNull(KeyCombo.parse("Ctrl+Alt+"))
        assertNull(KeyCombo.parse("Ctrl+Alt+Banana"))
        assertNull(KeyCombo.parse("Hyper+P"))
    }

    @Test
    fun `key codes agree with the Windows and X11 tables where they should`() {
        assertEquals(0x22, HotkeyKeys.named("Page Down")!!.vk)
        assertEquals(KeyEvent.VK_PAGE_DOWN, HotkeyKeys.named("Page Down")!!.awt)
        assertEquals(0x2D, HotkeyKeys.named("Insert")!!.vk)
        assertEquals(KeyEvent.VK_INSERT, HotkeyKeys.named("Insert")!!.awt)
        assertEquals(0x7B, HotkeyKeys.named("F12")!!.vk)
        assertEquals(0x87, HotkeyKeys.named("F24")!!.vk)
        assertEquals(KeyEvent.VK_F13, HotkeyKeys.named("F13")!!.awt)
        assertEquals(KeyEvent.VK_F24, HotkeyKeys.named("F24")!!.awt)
        assertEquals(0xFFC9L, HotkeyKeys.named("F12")!!.keysym) // XK_F12
        assertEquals(0x70L, HotkeyKeys.named("P")!!.keysym) // XK_p, lower case
        assertEquals(KeyEvent.VK_OPEN_BRACKET, HotkeyKeys.named("[")!!.awt)
        assertEquals(KeyEvent.VK_BACK_QUOTE, HotkeyKeys.named("`")!!.awt)
    }

    @Test
    fun `no two keys share a code on any platform`() {
        val all = HotkeyKeys.all
        assertEquals(all.size, all.map { it.vk }.toSet().size)
        assertEquals(all.size, all.map { it.awt }.toSet().size)
        assertEquals(all.size, all.map { it.keysym }.toSet().size)
    }

    @Test
    fun `native modifier masks`() {
        val combo = KeyCombo.of("Home", CTRL, ALT)
        assertEquals(0x0003, combo.windowsModifiers) // MOD_ALT | MOD_CONTROL
        assertEquals(0x0C, combo.x11Modifiers) // ControlMask | Mod1Mask
        assertEquals(0x000F, KeyCombo.of("A", CTRL, ALT, SHIFT, WIN).windowsModifiers)
    }

    @Test
    fun `combo from an AWT key press`() {
        val mods = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK
        assertEquals(KeyCombo.of("Home", CTRL, ALT), KeyCombo.fromAwt(KeyEvent.VK_HOME, mods))
        // AltGr is Ctrl+Alt to Windows, and to RegisterHotKey.
        assertEquals(KeyCombo.of("Q", CTRL, ALT), KeyCombo.fromAwt(KeyEvent.VK_Q, InputEvent.ALT_GRAPH_DOWN_MASK))
        assertEquals(KeyCombo.of("F13"), KeyCombo.fromAwt(KeyEvent.VK_F13, 0))
        // A key the table does not have (Escape is the recorder's cancel key).
        assertNull(KeyCombo.fromAwt(KeyEvent.VK_ESCAPE, mods))
    }

    @Test
    fun `combos that would break typing or that Windows owns are refused`() {
        assertNotNull(KeyCombo.problem(KeyCombo.of("P")))
        assertNotNull(KeyCombo.problem(KeyCombo.of("P", SHIFT)))
        assertNotNull(KeyCombo.problem(KeyCombo.of("F5", SHIFT)))
        assertNotNull(KeyCombo.problem(KeyCombo.of("Delete", CTRL, ALT)))
        assertNotNull(KeyCombo.problem(KeyCombo.of("Delete", CTRL, ALT, SHIFT)))
        assertNotNull(KeyCombo.problem(KeyCombo.of("L", WIN)))
        assertNotNull(KeyCombo.problem(KeyCombo.of("F4", ALT)))
        assertNull(KeyCombo.problem(KeyCombo.of("P", CTRL, ALT)))
        assertNull(KeyCombo.problem(KeyCombo.of("F13")))
        assertNull(KeyCombo.problem(KeyCombo.of("F24", SHIFT)))
        assertNull(KeyCombo.problem(KeyCombo.of("M", WIN, SHIFT)))
    }

    @Test
    fun `defaults are all valid, all different, and transport, volume and peek are on`() {
        val combos = HotkeyAction.entries.map { it.defaultCombo }
        assertEquals(combos.size, combos.toSet().size)
        combos.forEach { assertNull(it.format(), KeyCombo.problem(it)) }
        assertEquals(
            setOf(
                HotkeyAction.PLAY_PAUSE, HotkeyAction.NEXT, HotkeyAction.PREVIOUS,
                HotkeyAction.VOLUME_UP, HotkeyAction.VOLUME_DOWN, HotkeyAction.PEEK
            ),
            HotkeyAction.entries.filter { it.defaultOn }.toSet()
        )
        // Defaults type nothing, so AltGr on a European layout cannot collide with a character.
        val typing = HotkeyKeys.all.filter { it.name.length == 1 }.toSet()
        combos.forEach { assertTrue(it.format(), it.key !in typing) }
    }

    @Test
    fun `native ids round trip and stay in the app range`() {
        for (action in HotkeyAction.entries) {
            assertEquals(action, HotkeyAction.byNativeId(action.nativeId))
            assertTrue(action.nativeId in 1..0xBFFF)
        }
        assertNull(HotkeyAction.byNativeId(0))
        assertNull(HotkeyAction.byNativeId(999))
    }

    @Test
    fun `binding encoding keeps the combo when switched off`() {
        val b = HotkeyBinding(KeyCombo.of("Insert", CTRL, ALT), enabled = false)
        assertEquals("off:Ctrl+Alt+Insert", b.encode())
        assertEquals(b, HotkeyBinding.decode(b.encode(), HotkeyDefaults.bindings.getValue(HotkeyAction.LIKE)))
    }

    @Test
    fun `a damaged binding falls back instead of disappearing`() {
        val fallback = HotkeyDefaults.bindings.getValue(HotkeyAction.NEXT)
        assertEquals(fallback, HotkeyBinding.decode("on:Ctrl+Nope", fallback))
        assertEquals(fallback, HotkeyBinding.decode("Ctrl+Alt+P", fallback))
        assertEquals(fallback, HotkeyBinding.decode(null, fallback))
    }

    @Test
    fun `plan registers enabled valid bindings and explains the rest`() {
        val same = KeyCombo.of("P", CTRL, ALT)
        val bindings = HotkeyDefaults.bindings + mapOf(
            HotkeyAction.PLAY_PAUSE to HotkeyBinding(same, true),
            HotkeyAction.LIKE to HotkeyBinding(same, true),
            HotkeyAction.VOLUME_UP to HotkeyBinding(KeyCombo.of("U"), true),
        )
        val (register, status) = HotkeyPlan.plan(bindings)
        assertEquals(
            setOf(HotkeyAction.PLAY_PAUSE, HotkeyAction.NEXT, HotkeyAction.PREVIOUS, HotkeyAction.VOLUME_DOWN, HotkeyAction.PEEK),
            register.keys
        )
        assertEquals(same, register[HotkeyAction.PLAY_PAUSE])
        // The action listed first keeps a shared combo.
        assertEquals(HotkeyStatus.Duplicate(HotkeyAction.PLAY_PAUSE), status[HotkeyAction.LIKE])
        assertTrue(status[HotkeyAction.VOLUME_UP] is HotkeyStatus.Invalid)
        assertEquals(HotkeyStatus.Off, status[HotkeyAction.SEEK_FORWARD])
        // Every action ends up with exactly one of the two.
        assertEquals(HotkeyAction.entries.toSet(), register.keys + status.keys)
        assertTrue((register.keys intersect status.keys).isEmpty())
    }

    @Test
    fun `a switched off binding does not hold its combo`() {
        val combo = HotkeyAction.NEXT.defaultCombo
        val bindings = HotkeyDefaults.bindings + (HotkeyAction.NEXT to HotkeyBinding(combo, false))
        assertNull(HotkeyPlan.holderOf(combo, bindings, except = HotkeyAction.LIKE))
        assertEquals(HotkeyAction.PLAY_PAUSE, HotkeyPlan.holderOf(HotkeyAction.PLAY_PAUSE.defaultCombo, bindings, except = HotkeyAction.LIKE))
        assertNull(HotkeyPlan.holderOf(HotkeyAction.PLAY_PAUSE.defaultCombo, bindings, except = HotkeyAction.PLAY_PAUSE))
    }

    @Test
    fun `status messages exist exactly for the states that need explaining`() {
        assertNull(HotkeyStatus.Active.message)
        assertNull(HotkeyStatus.Off.message)
        assertNull(HotkeyStatus.Pending.message)
        assertNotNull(HotkeyStatus.Taken.message)
        assertTrue(HotkeyStatus.Duplicate(HotkeyAction.NEXT).message!!.contains("Next song"))
    }

    @Test
    fun `game mode preferences round trip through properties`() {
        val prefs = GameModePrefs(
            hotkeysEnabled = false,
            hotkeys = HotkeyDefaults.bindings + (HotkeyAction.LIKE to HotkeyBinding(KeyCombo.of("F13"), true)),
            overlayMode = OverlayMode.LYRICS,
            overlayCorner = OverlayCorner.BOTTOM_CENTER,
            overlaySize = OverlaySize.LARGE,
            overlayOpacity = 0.6f,
            overlayMonitor = "\\Display1",
        )
        val props = Properties()
        prefs.store(props)
        assertEquals(prefs, GameModePrefs.load(props))
    }

    @Test
    fun `missing or bad preferences load as defaults`() {
        assertEquals(GameModePrefs(), GameModePrefs.load(Properties()))
        val props = Properties().apply {
            setProperty("gameMode.overlayMode", "SIDEWAYS")
            setProperty("gameMode.overlayOpacity", "0.01")
        }
        val loaded = GameModePrefs.load(props)
        assertEquals(OverlayMode.OFF, loaded.overlayMode)
        assertEquals(GameModePrefs.MIN_OPACITY, loaded.overlayOpacity)
    }

    @Test
    fun `hotkeys are explained away on Wayland, headless X and other systems`() {
        val wayland = mapOf("XDG_SESSION_TYPE" to "wayland", "DISPLAY" to ":0")
        assertNotNull(GlobalHotkeys.detectUnavailable(false, true, wayland::get))
        val waylandDisplay = mapOf("WAYLAND_DISPLAY" to "wayland-0", "DISPLAY" to ":0")
        assertNotNull(GlobalHotkeys.detectUnavailable(false, true, waylandDisplay::get))
        assertNotNull(GlobalHotkeys.detectUnavailable(false, true, emptyMap<String, String>()::get))
        val x11 = mapOf("XDG_SESSION_TYPE" to "x11", "DISPLAY" to ":0")
        assertNull(GlobalHotkeys.detectUnavailable(false, true, x11::get))
        assertNull(GlobalHotkeys.detectUnavailable(true, false, wayland::get))
        assertNotNull(GlobalHotkeys.detectUnavailable(false, false, x11::get))
    }

    @Test
    fun `X11 grabs cover every lock state`() {
        val variants = X11HotkeyBackend.LOCK_VARIANTS.toSet()
        assertEquals(setOf(0, 0x02, 0x10, 0x12), variants)
        // A lock bit is never mistaken for part of a combo.
        variants.forEach { assertEquals(0, it and X11HotkeyBackend.RELEVANT_MODS) }
        HotkeyMod.entries.forEach { assertEquals(it.x11, it.x11 and X11HotkeyBackend.RELEVANT_MODS) }
    }
}
