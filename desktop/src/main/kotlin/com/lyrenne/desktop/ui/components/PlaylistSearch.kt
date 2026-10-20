package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.media.suppressMediaKeys

/**
 * A search icon that opens into a filled pill field, and stays open while it holds a query.
 * Used in toolbars (Library, playlists) where a full-width field cost a whole row.
 */
@Composable
fun CollapsibleSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    var open by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (open || query.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.width(220.dp).focusRequester(focus).suppressMediaKeys()
                .onFocusChanged { if (!it.isFocused && query.isEmpty()) open = false },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, "Clear") }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            // A filled pill: the outline only appears on focus.
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedBorderColor = Color.Transparent
            )
        )
        LaunchedEffect(open) { if (open) runCatching { focus.requestFocus() } }
    } else {
        Tip(placeholder) {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.Search, placeholder) }
        }
    }
}

/** Whether any of a song's [fields] (title, artist, album) contains [query], ignoring case. */
fun matchesQuery(query: String, vararg fields: String?): Boolean {
    val q = query.trim()
    return q.isEmpty() || fields.any { it?.contains(q, ignoreCase = true) == true }
}
