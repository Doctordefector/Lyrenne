package com.lyrenne.desktop.ui.link

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.link.LinkSettings
import com.lyrenne.desktop.link.LyrenneLink
import com.lyrenne.desktop.link.QrCode
import com.lyrenne.desktop.ui.components.Tip
import kotlinx.coroutines.delay

/**
 * Lyrenne Link's window-level pieces: the pairing dialog (opened from Settings or the Command
 * Palette) and a short notice line. App.kt hosts this once.
 */
@Composable
fun LinkHost(modifier: Modifier = Modifier) {
    val pairing by LyrenneLink.pairingRequested.collectAsState()
    if (pairing) PairingDialog(onDismiss = { LyrenneLink.pairingRequested.value = false })

    val notice by LyrenneLink.notice.collectAsState()
    LaunchedEffect(notice) {
        if (notice != null) { delay(3_500); LyrenneLink.notice.value = null }
    }
    Box(modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(notice != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 6.dp
            ) {
                Text(notice.orEmpty(), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun PairingDialog(onDismiss: () -> Unit) {
    val prefs by LinkSettings.prefs.collectAsState()
    val status by LyrenneLink.status.collectAsState()
    var address by remember(status.lanAddresses) { mutableStateOf(status.lanAddresses.firstOrNull()) }
    val url = if (prefs.remoteEnabled) LyrenneLink.remoteUrl(address) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.QrCode2, null) },
        title = { Text("Phone remote") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !prefs.remoteEnabled -> Text(
                        "Control Lyrenne from your phone's browser, no app needed. Turning this on opens a port " +
                            "on your local network. Windows may ask to allow Lyrenne: choose Private networks."
                    )
                    status.error != null -> Text(status.error!!, color = MaterialTheme.colorScheme.error)
                    url == null && status.running -> Text(
                        "This PC doesn't seem to be on a network. Connect it to the same Wi-Fi or router as your phone."
                    )
                    url == null -> Text("Starting...")
                    else -> {
                        Text("Scan this with your phone's camera. Your phone needs to be on the same Wi-Fi as this PC.")
                        QrImage(url)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f, fill = false)) {
                                Text(
                                    url.substringBefore("#"),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                            Tip("Copy the link, with its pairing code") {
                                IconButton(onClick = {
                                    LyrenneLink.copyToClipboard(url)
                                    LyrenneLink.notice.value = "Link copied. It includes the pairing code, so only share it with your own devices."
                                }) { Icon(Icons.Default.ContentCopy, "Copy link") }
                            }
                        }
                        if (status.lanAddresses.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                status.lanAddresses.forEach { a ->
                                    FilterChip(
                                        selected = a == address, onClick = { address = a }, label = { Text(a) },
                                        colors = com.lyrenne.desktop.ui.theme.selectedChipColors()
                                    )
                                }
                            }
                        }
                        status.portNote?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "Phone can't connect? Check it's on the same Wi-Fi, and that Windows allows Lyrenne on " +
                                "private networks (Windows Security, Firewall, Allow an app).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!prefs.remoteEnabled) Button(onClick = { LyrenneLink.setRemoteEnabled(true) }) { Text("Turn on") }
            else Button(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            if (prefs.remoteEnabled) TextButton(onClick = { LyrenneLink.setRemoteEnabled(false); onDismiss() }) { Text("Turn off remote") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** The QR code, dark on white whatever the theme: phone cameras read it most reliably that way. */
@Composable
private fun QrImage(text: String) {
    val qr = remember(text) { runCatching { QrCode.encode(text) }.getOrNull() } ?: return
    Box(
        Modifier.size(232.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(20.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / qr.size
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                if (qr[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
