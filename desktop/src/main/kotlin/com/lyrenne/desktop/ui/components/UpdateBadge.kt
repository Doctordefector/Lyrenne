package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.update.AutoUpdater

/**
 * Floating "an update is waiting" pill, shown over the top-right of whatever screen is open.
 *
 * The launch check already announced updates through a tray notification, but a toast ten seconds
 * after startup is trivially missed: it fires while the user is still getting oriented, dismisses
 * itself, and leaves nothing behind. Anyone who looked away simply never found out. This stays put
 * until the update is actually installed.
 *
 * Renders nothing unless an update is genuinely pending, so it costs nothing the rest of the time.
 * It also stays hidden while Settings is open, since that is where it points and a pill floating
 * over its own destination is just clutter.
 */
@Composable
fun UpdateBadge(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by AutoUpdater.updateState.collectAsState()

    // Downloading is deliberately included. Starting a download and navigating away used to make
    // the indicator vanish, which reads as the update having failed.
    val label = when (val s = state) {
        is AutoUpdater.UpdateState.UpdateAvailable -> "Update to ${s.version}"
        is AutoUpdater.UpdateState.Downloading -> "Downloading ${(s.progress * 100).toInt()}%"
        is AutoUpdater.UpdateState.ReadyToInstall -> "Restart to finish update"
        else -> null
    } ?: return

    if (!visible) return

    val busy = state is AutoUpdater.UpdateState.Downloading

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        // A brand colour is a fill, never a foreground: the container/onContainer pairing is the
        // one that has been contrast-checked in both schemes. See Theming in CLAUDE.md.
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
