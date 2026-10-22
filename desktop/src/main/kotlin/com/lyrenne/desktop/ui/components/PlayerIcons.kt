package com.lyrenne.desktop.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The player's own lyrics, queue and shuffle glyphs. Material's three mixed an outlined document,
 * a filled list and an angular shuffle; these share one rounded 2 px stroke so they read as a set.
 * Drawn in black and tinted by `Icon` like any Material icon.
 */
internal object PlayerIcons {
    /** A speech bubble holding a closing quote mark: words being sung. */
    val Lyrics: ImageVector by lazy {
        icon("Lyrics") {
            stroke {
                moveTo(6f, 3.5f); horizontalLineTo(18f)
                arcTo(3f, 3f, 0f, false, true, 21f, 6.5f); verticalLineTo(13.5f)
                arcTo(3f, 3f, 0f, false, true, 18f, 16.5f); horizontalLineTo(11f)
                lineTo(7f, 20f); verticalLineTo(16.5f); horizontalLineTo(6f)
                arcTo(3f, 3f, 0f, false, true, 3f, 13.5f); verticalLineTo(6.5f)
                arcTo(3f, 3f, 0f, false, true, 6f, 3.5f); close()
            }
            for (cx in listOf(9.6f, 14.9f)) {
                val cy = 9.2f
                fill {
                    moveTo(cx - 1.6f, cy)
                    arcTo(1.6f, 1.6f, 0f, true, true, cx + 1.6f, cy)
                    arcTo(1.6f, 1.6f, 0f, true, true, cx - 1.6f, cy); close()
                }
                stroke(width = 1.6f) { moveTo(cx + 1.5f, cy); quadTo(cx + 1.5f, cy + 3f, cx - 0.9f, cy + 4.1f) }
            }
        }
    }

    /** A card for what is playing, with the songs after it lined up underneath. */
    val Queue: ImageVector by lazy {
        icon("Queue") {
            stroke {
                moveTo(5.5f, 3f); horizontalLineTo(18.5f)
                arcTo(2f, 2f, 0f, false, true, 20.5f, 5f); verticalLineTo(8f)
                arcTo(2f, 2f, 0f, false, true, 18.5f, 10f); horizontalLineTo(5.5f)
                arcTo(2f, 2f, 0f, false, true, 3.5f, 8f); verticalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 5.5f, 3f); close()
                moveTo(3.5f, 14.5f); horizontalLineTo(20.5f)
                moveTo(3.5f, 20f); horizontalLineTo(20.5f)
            }
        }
    }

    /**
     * Two smooth strands crossing, one broken where it passes under the other. The break points
     * split the curve at t = 0.38 and 0.62 (de Casteljau); the curve is point-symmetric about the
     * crossing at (11.5, 12), so each half mirrors the other.
     */
    val Shuffle: ImageVector by lazy {
        icon("Shuffle") {
            stroke {
                // Over: bottom left to top right
                moveTo(3f, 17f); horizontalLineTo(6f)
                curveTo(10f, 17f, 13f, 7f, 17f, 7f); horizontalLineTo(21f)
                moveTo(18f, 4f); lineTo(21f, 7f); lineTo(18f, 10f)
                // Under: top left to bottom right, broken at the crossing
                moveTo(3f, 7f); horizontalLineTo(6f)
                curveTo(7.52f, 7f, 8.9f, 8.44f, 10.24f, 10.24f)
                moveTo(12.76f, 13.76f)
                curveTo(14.1f, 15.56f, 15.48f, 17f, 17f, 17f); horizontalLineTo(21f)
                moveTo(18f, 14f); lineTo(21f, 17f); lineTo(18f, 20f)
            }
        }
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(
        width: Float = 2f,
        filled: Boolean = false,
        path: PathBuilder.() -> Unit
    ) = path(
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = path
    )

    private fun ImageVector.Builder.fill(path: PathBuilder.() -> Unit) =
        path(fill = SolidColor(Color.Black), pathBuilder = path)
}
