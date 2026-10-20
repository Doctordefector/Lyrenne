package com.lyrenne.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.PlaybackState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Collects one slice of the player state, recomposing only when that slice changes.
 *
 * `player.state.collectAsState()` recomposes the caller on every position tick, five times a
 * second while playing. Screens that only mark the playing row rebuilt their whole list at that
 * rate for a value that changes once a track. Collect the full state only where the position is
 * actually drawn, and keep that in the smallest composable that draws it.
 */
@Composable
fun <T> DesktopPlayer.select(selector: (PlaybackState) -> T): State<T> =
    remember(this) { state.map(selector).distinctUntilChanged() }.collectAsState(selector(state.value))
