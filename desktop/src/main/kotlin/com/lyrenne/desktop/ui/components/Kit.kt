package com.lyrenne.desktop.ui.components

import com.lyrenne.desktop.ui.theme.LyrenneTokens
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// One style per role, so screens stop drifting apart: Play All was a filled button on Album and
// a tonal one on Library, and screen titles ranged from 22 to 30 sp.

/** The main action of a screen or header (Play). Filled. */
@Composable
fun PrimaryAction(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

/** A secondary action beside [PrimaryAction] (Shuffle, Download). Outlined. */
@Composable
fun SecondaryAction(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

/** A top-level screen's title row, with optional actions at the end. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** A tool screen's title, with Back only when it was reached from somewhere deeper. */
@Composable
fun ToolHeader(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Tip("Back (Esc)") {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
            Spacer(Modifier.width(8.dp))
        }
        ScreenTitle(title, Modifier.weight(1f), actions)
    }
}

/** Swallows clicks and drags (not the wheel) for content shown but not usable right now. */
fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) event.changes.forEach { it.consume() }
        }
    }
}

/** A tab as a pill: filled when selected, outlined otherwise, with an optional count. */
@Composable
fun PillTab(label: String, selected: Boolean, onClick: () -> Unit, count: Int = 0) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(colors.primary)
                else Modifier.border(1.dp, colors.outline.copy(alpha = 0.5f), shape)
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (selected) colors.onPrimary else colors.onSurface
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onPrimary else colors.onSurfaceVariant
            )
        }
    }
}

/** Loading placeholder for a shelf of tiles: a title bar and a row of squares. */
@Composable
fun SkeletonShelf() {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Box(
            Modifier.size(width = 160.dp, height = 18.dp).clip(RoundedCornerShape(4.dp)).background(fill)
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(160.dp).clip(RoundedCornerShape(LyrenneTokens.artworkRadius)).background(fill)
                )
            }
        }
    }
}

/** Loading placeholder for a list of TrackRows. */
@Composable
fun SkeletonRows(count: Int = 8) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    androidx.compose.foundation.layout.Column {
        repeat(count) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(fill))
                Spacer(Modifier.width(12.dp))
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Box(Modifier.size(width = 220.dp, height = 12.dp).clip(RoundedCornerShape(4.dp)).background(fill))
                    Spacer(Modifier.size(6.dp))
                    androidx.compose.foundation.layout.Box(Modifier.size(width = 140.dp, height = 10.dp).clip(RoundedCornerShape(4.dp)).background(fill))
                }
            }
        }
    }
}
