package com.lyrenne.desktop.gamemode

import com.lyrenne.desktop.Platform
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import timber.log.Timber
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.roundToInt

/** The overlay window's size in dp: one layout, scaled. */
fun OverlaySize.windowWidthDp(): Int = (OVERLAY_BASE_WIDTH_DP * scale).roundToInt()
fun OverlaySize.windowHeightDp(): Int = (OVERLAY_BASE_HEIGHT_DP * scale).roundToInt()

/** The overlay is laid out for this size and scaled up or down by [OverlaySize.scale]. */
const val OVERLAY_BASE_WIDTH_DP = 420
const val OVERLAY_BASE_HEIGHT_DP = 270

/** Gap between the overlay and the screen edge, in dp. Clears most games' own corner HUD. */
const val OVERLAY_MARGIN_DP = 28

/**
 * Where the overlay window goes on a screen. [screen] and the result are in AWT user space, which
 * is what Compose's dp are for window geometry, so a 150% scaled monitor needs nothing extra here.
 */
fun overlayLocation(screen: Rectangle, corner: OverlayCorner, width: Int, height: Int, margin: Int = OVERLAY_MARGIN_DP): Point {
    val x = when (corner.horizontal) {
        -1 -> screen.x + margin
        0 -> screen.x + (screen.width - width) / 2
        else -> screen.x + screen.width - width - margin
    }
    val y = if (corner.top) screen.y + margin else screen.y + screen.height - height - margin
    return Point(x, y)
}

/** A screen the user can pin the overlay to. [id] is AWT's id string, stable while plugged in. */
data class OverlayScreen(val id: String, val label: String)

object OverlayScreens {
    fun list(): List<OverlayScreen> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.mapIndexed { i, d ->
            val b = physicalBounds(d.defaultConfiguration)
            OverlayScreen(d.iDstring, "Display ${i + 1} (${b.width} x ${b.height})")
        }
    }.getOrDefault(emptyList())

    /**
     * The screen to show the overlay on, in user space: the chosen one if it is still connected,
     * otherwise the one the focused window (the game) is on, otherwise the primary.
     */
    fun target(choice: String): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val devices = env.screenDevices
        if (choice != GameModePrefs.MONITOR_FOLLOW) {
            devices.firstOrNull { it.iDstring == choice }?.let { return it.defaultConfiguration.bounds }
        }
        val fg = foregroundMonitor()
        if (fg != null) {
            devices.maxByOrNull { overlap(physicalBounds(it.defaultConfiguration), fg) }
                ?.takeIf { overlap(physicalBounds(it.defaultConfiguration), fg) > 0 }
                ?.let { return it.defaultConfiguration.bounds }
        }
        return env.defaultScreenDevice.defaultConfiguration.bounds
    }

    /**
     * A screen's bounds in device pixels. AWT reports each screen's origin and size divided by
     * that screen's own scale, which is what Win32 needs undoing to compare against.
     */
    internal fun physicalBounds(cfg: java.awt.GraphicsConfiguration): Rectangle {
        val b = cfg.bounds
        val t = cfg.defaultTransform
        return Rectangle(
            (b.x * t.scaleX).roundToInt(), (b.y * t.scaleY).roundToInt(),
            (b.width * t.scaleX).roundToInt(), (b.height * t.scaleY).roundToInt()
        )
    }

    internal fun overlap(a: Rectangle, b: Rectangle): Long {
        val i = a.intersection(b)
        return if (i.isEmpty) 0 else i.width.toLong() * i.height
    }

    /** The monitor the foreground window is on, in device pixels, or null off Windows. */
    private fun foregroundMonitor(): Rectangle? {
        if (!Platform.isWindows) return null
        return try {
            val u = User32.INSTANCE
            val hwnd = u.GetForegroundWindow() ?: return null
            val monitor = u.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST) ?: return null
            val info = WinUser.MONITORINFO()
            if (!u.GetMonitorInfo(monitor, info).booleanValue()) return null
            info.rcMonitor.toRectangle()
        } catch (e: Throwable) {
            null
        }
    }
}

/**
 * The Win32 side of the overlay window.
 *
 * - WS_EX_LAYERED + WS_EX_TRANSPARENT: clicks go straight through to the game underneath.
 * - WS_EX_NOACTIVATE: clicking it or showing it never takes focus from the game.
 * - WS_EX_TOOLWINDOW (and no WS_EX_APPWINDOW): no taskbar button and no Alt+Tab entry.
 */
object OverlayWin32 {
    const val GWL_EXSTYLE = -20
    const val WS_EX_TRANSPARENT = 0x00000020
    const val WS_EX_TOOLWINDOW = 0x00000080
    const val WS_EX_APPWINDOW = 0x00040000
    const val WS_EX_LAYERED = 0x00080000
    const val WS_EX_NOACTIVATE = 0x08000000
    const val WS_EX_TOPMOST = 0x00000008

    const val OVERLAY_EX_STYLE = WS_EX_LAYERED or WS_EX_TRANSPARENT or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW

    /** The extended style the overlay needs, from whatever AWT gave the window. */
    fun overlayStyle(current: Int): Int = (current or OVERLAY_EX_STYLE) and WS_EX_APPWINDOW.inv()

    /** Applies [overlayStyle] if anything is missing. Returns the style now set, or null on failure. */
    fun apply(window: java.awt.Window): Int? {
        if (!Platform.isWindows || !window.isDisplayable) return null
        return try {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window) ?: return null)
            val u = User32.INSTANCE
            val current = u.GetWindowLong(hwnd, GWL_EXSTYLE)
            val wanted = overlayStyle(current)
            if (current != wanted) u.SetWindowLong(hwnd, GWL_EXSTYLE, wanted)
            u.GetWindowLong(hwnd, GWL_EXSTYLE)
        } catch (e: Throwable) {
            Timber.w("Could not make the overlay click-through: ${e.message}")
            null
        }
    }
}
