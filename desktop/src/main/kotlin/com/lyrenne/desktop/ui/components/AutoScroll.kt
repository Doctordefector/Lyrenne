package com.lyrenne.desktop.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.Window
import kotlin.math.abs
import kotlin.math.sign

/**
 * Browser-style middle-click autoscroll.
 *
 * Middle-click drops an anchor; moving the pointer away from it scrolls continuously, faster
 * the further you go. Any click, Escape, or the window losing focus stops it.
 *
 * Rather than driving each screen's scroll state — every list would have to hoist and register
 * one — this posts real wheel events with [Robot], so whatever is under the pointer scrolls,
 * exactly as if the user spun the wheel. That means it works on every screen with no per-screen
 * wiring, and nested scrollables behave correctly for free.
 */
object AutoScroll {

    /** Anchor position in screen coordinates, or null when inactive. */
    @Volatile
    private var anchor: Pair<Int, Int>? = null

    val isActive: Boolean get() = anchor != null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private val robot: Robot? by lazy {
        try {
            Robot()
        } catch (e: Exception) {
            // Headless or a locked-down environment — feature simply stays unavailable.
            Timber.w("Autoscroll unavailable: ${e.message}")
            null
        }
    }

    /** Pointer must sit this far from the anchor before scrolling starts — a dead zone. */
    private const val DEAD_ZONE_PX = 12

    /** Distance beyond the dead zone that maps to maximum speed. */
    private const val MAX_DISTANCE_PX = 260

    private const val TICK_MS = 20L

    /**
     * Wheel notches per tick, at the dead-zone edge and at full travel.
     *
     * Speed is accumulated as a fraction and a notch is only posted once a whole one is due,
     * because [Robot.mouseWheel] takes an integer. Rounding per tick instead put the floor at
     * one notch every tick — about 40 notches a second — so the slowest possible autoscroll
     * was already fast, and there was no gentle end of the range at all.
     */
    private const val MIN_NOTCHES_PER_TICK = 0.05f
    private const val MAX_NOTCHES_PER_TICK = 2.4f

    /**
     * The autoscroll cursor: a ring with a centre dot and up/down arrows.
     *
     * Java has no predefined constant for it — `MOVE_CURSOR` maps to the four-arrow SIZEALL,
     * which reads as "drag this", not "scrolling". Drawn rather than bundled as a bitmap so it
     * matches whatever cursor size the OS asks for.
     */
    private val cursor: java.awt.Cursor by lazy {
        try {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            val best = toolkit.getBestCursorSize(32, 32)
            val size = if (best.width > 0) best.width else 32
            val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            val c = size / 2f
            val radius = size * 0.26f
            g.color = java.awt.Color(255, 255, 255, 235)
            g.fill(java.awt.geom.Ellipse2D.Float(c - radius, c - radius, radius * 2, radius * 2))
            g.color = java.awt.Color(30, 30, 30, 235)
            g.stroke = java.awt.BasicStroke(size * 0.055f)
            g.draw(java.awt.geom.Ellipse2D.Float(c - radius, c - radius, radius * 2, radius * 2))
            val dot = size * 0.055f
            g.fill(java.awt.geom.Ellipse2D.Float(c - dot, c - dot, dot * 2, dot * 2))
            val w = size * 0.13f
            val h = size * 0.13f
            val gap = radius + size * 0.06f
            for (dir in intArrayOf(-1, 1)) {
                val tip = c + dir * (gap + h)
                val base = c + dir * gap
                g.fill(java.awt.Polygon(
                    intArrayOf((c - w).toInt(), (c + w).toInt(), c.toInt()),
                    intArrayOf(base.toInt(), base.toInt(), tip.toInt()),
                    3
                ))
            }
            g.dispose()
            toolkit.createCustomCursor(image, java.awt.Point(size / 2, size / 2), "autoscroll")
        } catch (e: Exception) {
            Timber.w("Could not build autoscroll cursor: ${e.message}")
            java.awt.Cursor.getDefaultCursor()
        }
    }

    /**
     * Window whose cursor we changed. Held so EVERY exit path can restore it — stop() is
     * reached from focus loss, Escape, dispose and the next click, and previously only the
     * mouse listener reset the cursor, which left it stranded when you alt-tabbed away.
     */
    private var cursorWindow: Window? = null

    private fun setCursor(window: Window?, custom: Boolean) {
        val target = window ?: return
        javax.swing.SwingUtilities.invokeLater {
            target.cursor = if (custom) cursor else java.awt.Cursor.getDefaultCursor()
        }
    }

    fun toggle(screenX: Int, screenY: Int, window: Window?) {
        if (isActive) stop() else start(screenX, screenY, window)
    }

    private fun start(screenX: Int, screenY: Int, window: Window?) {
        val bot = robot ?: return
        anchor = screenX to screenY
        cursorWindow = window
        setCursor(window, custom = true)

        job?.cancel()
        job = scope.launch {
            var pending = 0f
            while (isActive) {
                val (ax, ay) = anchor ?: break

                // Only scroll while our own window is focused and the pointer is inside it,
                // otherwise Robot would spin the wheel over whatever app is underneath.
                if (window == null || !window.isFocused) { stop(); break }
                val pointer = MouseInfo.getPointerInfo()?.location
                if (pointer == null) { stop(); break }
                val inWindow = pointer.x >= window.x && pointer.x <= window.x + window.width &&
                    pointer.y >= window.y && pointer.y <= window.y + window.height
                if (!inWindow) { delay(TICK_MS); continue }

                // Horizontal travel is ignored, so drifting sideways across a row of cards
                // doesn't disturb the scroll.
                val dy = pointer.y - ay
                if (abs(dy) > DEAD_ZONE_PX) {
                    val travel = (abs(dy) - DEAD_ZONE_PX).coerceAtMost(MAX_DISTANCE_PX)
                    val ratio = travel.toFloat() / MAX_DISTANCE_PX
                    // Linear response — a quadratic curve made the usable range collapse into
                    // the last inch of travel and felt like it only had one speed: fast.
                    val speed = MIN_NOTCHES_PER_TICK +
                        (MAX_NOTCHES_PER_TICK - MIN_NOTCHES_PER_TICK) * ratio
                    pending += speed
                    val notches = pending.toInt()
                    if (notches > 0) {
                        pending -= notches
                        // Wheel down (positive) scrolls content down, matching pointer direction.
                        bot.mouseWheel(notches * dy.sign)
                    }
                } else {
                    pending = 0f
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        anchor = null
        setCursor(cursorWindow, custom = false)
        cursorWindow = null
    }
}
