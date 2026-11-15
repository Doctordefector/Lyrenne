package com.lyrenne.desktop.gamemode

/**
 * The model behind Game Mode's global hotkeys: which keys a hotkey can use, how a combo is written
 * down, what each action is bound to, and which bindings cannot work. Pure Kotlin, no native calls,
 * so all of it is unit tested (HotkeysTest). The OS side is in GlobalHotkeys.kt.
 */

/**
 * One key a global hotkey can use, with its code on each platform.
 *
 * [vk] is the Windows virtual-key code RegisterHotKey wants, [awt] the AWT key code the capture
 * field sees, and [keysym] the X11 keysym XGrabKey resolves to a keycode. They agree for letters,
 * digits, F1 to F12 and the arrows and differ for nearly everything else, which is why the table
 * spells all three out instead of deriving one from another.
 */
data class HotkeyKey(val name: String, val vk: Int, val awt: Int, val keysym: Long) {
    override fun toString() = name
}

object HotkeyKeys {
    val all: List<HotkeyKey> = buildList {
        for (c in 'A'..'Z') add(HotkeyKey(c.toString(), c.code, c.code, c.lowercaseChar().code.toLong()))
        for (c in '0'..'9') add(HotkeyKey(c.toString(), c.code, c.code, c.code.toLong()))
        for (i in 1..24) add(
            HotkeyKey(
                "F$i",
                vk = 0x6F + i,
                // AWT numbers F13 and up from 0xF000, not after F12.
                awt = if (i <= 12) 0x6F + i else 0xF000 + (i - 13),
                keysym = 0xFFBDL + i
            )
        )
        add(HotkeyKey("Up", 0x26, 38, 0xFF52))
        add(HotkeyKey("Down", 0x28, 40, 0xFF54))
        add(HotkeyKey("Left", 0x25, 37, 0xFF51))
        add(HotkeyKey("Right", 0x27, 39, 0xFF53))
        add(HotkeyKey("Home", 0x24, 36, 0xFF50))
        add(HotkeyKey("End", 0x23, 35, 0xFF57))
        add(HotkeyKey("Page Up", 0x21, 33, 0xFF55))
        add(HotkeyKey("Page Down", 0x22, 34, 0xFF56))
        add(HotkeyKey("Insert", 0x2D, 155, 0xFF63))
        add(HotkeyKey("Delete", 0x2E, 127, 0xFFFF))
        add(HotkeyKey("Space", 0x20, 32, 0x20))
        add(HotkeyKey("Pause", 0x13, 19, 0xFF13))
        for (i in 0..9) add(HotkeyKey("Num $i", 0x60 + i, 0x60 + i, 0xFFB0L + i))
        add(HotkeyKey("Num +", 0x6B, 0x6B, 0xFFAB))
        add(HotkeyKey("Num -", 0x6D, 0x6D, 0xFFAD))
        add(HotkeyKey("Num *", 0x6A, 0x6A, 0xFFAA))
        add(HotkeyKey("Num /", 0x6F, 0x6F, 0xFFAF))
        // Punctuation by US position. Other layouts put other characters on these keys, but the
        // key is the same physical key, so a combo recorded on any layout still fires.
        add(HotkeyKey(",", 0xBC, 44, 0x2C))
        add(HotkeyKey(".", 0xBE, 46, 0x2E))
        add(HotkeyKey("-", 0xBD, 45, 0x2D))
        add(HotkeyKey("=", 0xBB, 61, 0x3D))
        add(HotkeyKey("[", 0xDB, 91, 0x5B))
        add(HotkeyKey("]", 0xDD, 93, 0x5D))
        add(HotkeyKey(";", 0xBA, 59, 0x3B))
        add(HotkeyKey("'", 0xDE, 222, 0x27))
        add(HotkeyKey("/", 0xBF, 47, 0x2F))
        add(HotkeyKey("\\", 0xDC, 92, 0x5C))
        add(HotkeyKey("`", 0xC0, 192, 0x60))
    }

    private val byName = all.associateBy { it.name.lowercase() }
    private val byAwt = all.associateBy { it.awt }
    private val aliases = mapOf(
        "pgup" to "page up", "pageup" to "page up", "pgdn" to "page down", "pagedown" to "page down",
        "ins" to "insert", "del" to "delete", "arrowup" to "up", "arrowdown" to "down",
        "arrowleft" to "left", "arrowright" to "right",
    )

    fun named(name: String): HotkeyKey? {
        val n = name.trim().lowercase()
        return byName[n] ?: aliases[n.replace(" ", "")]?.let { byName[it] }
    }

    fun fromAwt(code: Int): HotkeyKey? = byAwt[code]

    /** F13 to F24: no keyboard types with them, so they are safe without a modifier. */
    fun isSafeAlone(key: HotkeyKey): Boolean = key.name.startsWith("F") && (key.name.drop(1).toIntOrNull() ?: 0) >= 13
}

/** Modifier keys, in the order a combo is written. [win] and [x11] are the native mask bits. */
enum class HotkeyMod(val label: String, val win: Int, val x11: Int) {
    CTRL("Ctrl", 0x0002, 0x04),
    ALT("Alt", 0x0001, 0x08),
    SHIFT("Shift", 0x0004, 0x01),
    WIN("Win", 0x0008, 0x40),
}

data class KeyCombo(val mods: Set<HotkeyMod>, val key: HotkeyKey) {
    /** Written as people say it, "Ctrl+Alt+Page Down". Also the stored form. */
    fun format(): String = (HotkeyMod.entries.filter { it in mods }.map { it.label } + key.name).joinToString("+")

    override fun toString() = format()

    val windowsModifiers: Int get() = mods.sumOf { it.win }
    val x11Modifiers: Int get() = mods.sumOf { it.x11 }

    companion object {
        fun of(key: String, vararg mods: HotkeyMod): KeyCombo =
            KeyCombo(mods.toSet(), requireNotNull(HotkeyKeys.named(key)) { "unknown key $key" })

        /**
         * Reads "Ctrl+Alt+Home". Modifiers are taken off the front one at a time rather than by
         * splitting on '+', because "Num +" is a key name that contains one.
         */
        fun parse(text: String): KeyCombo? {
            var rest = text.trim()
            val mods = mutableSetOf<HotkeyMod>()
            while (true) {
                val plus = rest.indexOf('+')
                if (plus <= 0) break
                val mod = modNamed(rest.substring(0, plus)) ?: break
                mods += mod
                rest = rest.substring(plus + 1).trim()
            }
            val key = HotkeyKeys.named(rest) ?: return null
            return KeyCombo(mods, key)
        }

        private fun modNamed(s: String): HotkeyMod? = when (s.trim().lowercase()) {
            "ctrl", "control" -> HotkeyMod.CTRL
            "alt", "option" -> HotkeyMod.ALT
            "shift" -> HotkeyMod.SHIFT
            "win", "super", "meta", "cmd" -> HotkeyMod.WIN
            else -> null
        }

        /**
         * A combo from an AWT key press, or null while only modifiers are down (the user has not
         * finished) or for a key the table does not have. AltGr counts as Ctrl+Alt, which is what
         * Windows reports it as anyway.
         */
        fun fromAwt(keyCode: Int, modifiersEx: Int): KeyCombo? {
            val key = HotkeyKeys.fromAwt(keyCode) ?: return null
            val mods = mutableSetOf<HotkeyMod>()
            if (modifiersEx and java.awt.event.InputEvent.CTRL_DOWN_MASK != 0) mods += HotkeyMod.CTRL
            if (modifiersEx and java.awt.event.InputEvent.ALT_DOWN_MASK != 0) mods += HotkeyMod.ALT
            if (modifiersEx and java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK != 0) { mods += HotkeyMod.CTRL; mods += HotkeyMod.ALT }
            if (modifiersEx and java.awt.event.InputEvent.SHIFT_DOWN_MASK != 0) mods += HotkeyMod.SHIFT
            if (modifiersEx and java.awt.event.InputEvent.META_DOWN_MASK != 0) mods += HotkeyMod.WIN
            return KeyCombo(mods, key)
        }

        /**
         * Why [combo] cannot be a global hotkey, or null when it can.
         *
         * A hotkey takes its keys away from every other app, so a combo without Ctrl, Alt or Win
         * would stop that key (or Shift plus it) typing anywhere. F13 to F24 are the exception:
         * no keyboard types with them, and macro keys are often mapped there.
         */
        fun problem(combo: KeyCombo): String? {
            val strong = combo.mods.any { it != HotkeyMod.SHIFT }
            if (!strong && !HotkeyKeys.isSafeAlone(combo.key)) {
                return "Add Ctrl, Alt or Win, so it does not stop that key typing"
            }
            val secureAttention = combo.key.name == "Delete" && HotkeyMod.CTRL in combo.mods && HotkeyMod.ALT in combo.mods
            if (combo in RESERVED || secureAttention) return "Windows keeps this one for itself"
            return null
        }

        /** Combos Windows never hands to an app, whatever RegisterHotKey says. */
        private val RESERVED = setOf(
            of("L", HotkeyMod.WIN),
            of("F4", HotkeyMod.ALT),
            of("Space", HotkeyMod.ALT),
        )
    }
}

/**
 * What a hotkey can do. [id] is the stored name, so the enum names may change but these may not.
 *
 * Default combos are Ctrl+Alt plus a navigation key: games rarely bind Ctrl+Alt, and the
 * navigation cluster types nothing, so AltGr (which Windows reports as Ctrl+Alt) cannot collide
 * with a character on a European layout the way Ctrl+Alt+E (the euro sign) would. Only the four
 * transport keys are on out of the box; the rest are suggestions a switch away. See CLAUDE.md.
 */
enum class HotkeyAction(val id: String, val label: String, val defaultCombo: KeyCombo, val defaultOn: Boolean) {
    PLAY_PAUSE("playPause", "Play or pause", KeyCombo.of("Home", HotkeyMod.CTRL, HotkeyMod.ALT), true),
    NEXT("next", "Next song", KeyCombo.of("Page Down", HotkeyMod.CTRL, HotkeyMod.ALT), true),
    PREVIOUS("previous", "Previous song", KeyCombo.of("Page Up", HotkeyMod.CTRL, HotkeyMod.ALT), true),
    LIKE("like", "Like or unlike the song", KeyCombo.of("Insert", HotkeyMod.CTRL, HotkeyMod.ALT), false),
    VOLUME_UP("volumeUp", "Volume up", KeyCombo.of("Up", HotkeyMod.CTRL, HotkeyMod.ALT), false),
    VOLUME_DOWN("volumeDown", "Volume down", KeyCombo.of("Down", HotkeyMod.CTRL, HotkeyMod.ALT), false),
    SEEK_FORWARD("seekForward", "Skip ahead 10 seconds", KeyCombo.of("Right", HotkeyMod.CTRL, HotkeyMod.ALT), false),
    SEEK_BACK("seekBack", "Go back 10 seconds", KeyCombo.of("Left", HotkeyMod.CTRL, HotkeyMod.ALT), false),
    PEEK("peek", "Peek at the song and lyrics", KeyCombo.of("End", HotkeyMod.CTRL, HotkeyMod.ALT), false),
    TOGGLE_OVERLAY("toggleOverlay", "Show or hide the overlay", KeyCombo.of("End", HotkeyMod.CTRL, HotkeyMod.SHIFT, HotkeyMod.ALT), false);

    /** The id handed to RegisterHotKey. Any value in 0..0xBFFF is the app's to use. */
    val nativeId: Int get() = ordinal + 1

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byNativeId(id: Int) = entries.getOrNull(id - 1)
    }
}

data class HotkeyBinding(val combo: KeyCombo, val enabled: Boolean) {
    /** Stored as "on:Ctrl+Alt+Home", so the combo survives being switched off. */
    fun encode(): String = (if (enabled) "on:" else "off:") + combo.format()

    companion object {
        fun decode(text: String?, fallback: HotkeyBinding): HotkeyBinding {
            if (text.isNullOrBlank()) return fallback
            val enabled = when {
                text.startsWith("on:") -> true
                text.startsWith("off:") -> false
                else -> return fallback
            }
            val combo = KeyCombo.parse(text.substringAfter(':')) ?: return fallback
            return HotkeyBinding(combo, enabled)
        }
    }
}

object HotkeyDefaults {
    val bindings: Map<HotkeyAction, HotkeyBinding> =
        HotkeyAction.entries.associateWith { HotkeyBinding(it.defaultCombo, it.defaultOn) }
}

/** Where one action's hotkey stands, for the row that shows it. */
sealed class HotkeyStatus {
    /** Switched off, or global hotkeys are off as a whole. */
    data object Off : HotkeyStatus()
    data object Active : HotkeyStatus()
    /** Registration has not been tried yet, or is suspended while a new combo is recorded. */
    data object Pending : HotkeyStatus()
    /** The OS refused it: another app (or Windows) already owns the combo. */
    data object Taken : HotkeyStatus()
    /** Another action has the same combo and got it first. */
    data class Duplicate(val other: HotkeyAction) : HotkeyStatus()
    data class Invalid(val reason: String) : HotkeyStatus()

    val message: String? get() = when (this) {
        Off, Active, Pending -> null
        Taken -> "Another app already uses this combo. Pick a different one."
        is Duplicate -> "Also set for \"${other.label}\". Pick a different one."
        is Invalid -> reason
    }
}

object HotkeyPlan {
    /**
     * Splits the bindings into the ones to register and the ones that cannot be.
     *
     * When two actions share a combo the one listed first keeps it, so the transport keys win over
     * the extras. Returns the combos to register by action plus a status for every action that is
     * not going to be registered at all.
     */
    fun plan(bindings: Map<HotkeyAction, HotkeyBinding>): Pair<Map<HotkeyAction, KeyCombo>, Map<HotkeyAction, HotkeyStatus>> {
        val toRegister = linkedMapOf<HotkeyAction, KeyCombo>()
        val statuses = mutableMapOf<HotkeyAction, HotkeyStatus>()
        val owner = mutableMapOf<KeyCombo, HotkeyAction>()
        for (action in HotkeyAction.entries) {
            val binding = bindings[action] ?: HotkeyDefaults.bindings.getValue(action)
            if (!binding.enabled) { statuses[action] = HotkeyStatus.Off; continue }
            val problem = KeyCombo.problem(binding.combo)
            if (problem != null) { statuses[action] = HotkeyStatus.Invalid(problem); continue }
            val first = owner[binding.combo]
            if (first != null) { statuses[action] = HotkeyStatus.Duplicate(first); continue }
            owner[binding.combo] = action
            toRegister[action] = binding.combo
        }
        return toRegister to statuses
    }

    /** The action already holding [combo], other than [except], among the enabled bindings. */
    fun holderOf(combo: KeyCombo, bindings: Map<HotkeyAction, HotkeyBinding>, except: HotkeyAction): HotkeyAction? =
        HotkeyAction.entries.firstOrNull { it != except && bindings[it]?.let { b -> b.enabled && b.combo == combo } == true }
}
