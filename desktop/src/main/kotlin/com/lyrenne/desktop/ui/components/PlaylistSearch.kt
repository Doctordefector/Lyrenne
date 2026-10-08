package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lyrenne.desktop.media.suppressMediaKeys

/** Filters the songs of one playlist (issue #14). Library search covers the whole library. */
@Composable
fun PlaylistSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().suppressMediaKeys(),
        placeholder = { Text("Search this playlist...") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, "Clear")
                }
            }
        },
        singleLine = true
    )
}

/** Whether any of a song's [fields] (title, artist, album) contains [query], ignoring case. */
fun matchesQuery(query: String, vararg fields: String?): Boolean {
    val q = query.trim()
    return q.isEmpty() || fields.any { it?.contains(q, ignoreCase = true) == true }
}
