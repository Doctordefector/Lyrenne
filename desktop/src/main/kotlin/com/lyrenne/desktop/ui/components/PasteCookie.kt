package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.auth.BrowserCookieExtractor
import com.lyrenne.desktop.auth.CookieExtractResult
import com.lyrenne.desktop.media.suppressMediaKeys

/**
 * "Advanced: paste a cookie". The sign-in of last resort: Flatpak-only systems, immutable
 * distros, a browser that drops our launch flags, or no supported browser at all. It feeds the
 * same [CookieExtractResult] a browser sign-in produces, so everything after it is shared.
 */
@Composable
fun PasteCookieSection(onResult: (CookieExtractResult) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Advanced: paste a cookie")
        }
        if (!expanded) return@Column

        Text(
            "Open music.youtube.com signed in, press F12, go to Network, click any request to " +
                "music.youtube.com, and copy the value of the \"cookie\" request header.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text("Cookie header") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().suppressMediaKeys()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    when (val result = BrowserCookieExtractor.cookiesFromHeader(text)) {
                        is CookieExtractResult.Error -> error = result.message
                        is CookieExtractResult.Success -> onResult(result)
                    }
                }
            ) { Text("Sign in with this cookie") }
        }
    }
}
