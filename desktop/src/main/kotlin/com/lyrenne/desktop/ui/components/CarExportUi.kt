package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.download.CarExport
import com.lyrenne.desktop.download.DownloadManager
import java.io.File
import javax.swing.JFileChooser

/**
 * Ask the user where to drop the exported MP3s. Defaults to a subfolder of the
 * downloads directory named after the playlist/album, which is what usually ends
 * up being copied straight onto a USB stick.
 */
fun chooseExportFolder(name: String): File? {
    val suggested = File(
        com.lyrenne.desktop.settings.PreferencesManager.getDownloadDirectory(),
        DownloadManager.sanitizeFilename(name)
    )
    val chooser = JFileChooser(suggested.parentFile).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Export to Folder (for USB / CD)"
        selectedFile = suggested
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.also { it.mkdirs() }
}

/** Progress/result line for a running or finished car export. Renders nothing when idle. */
@Composable
fun CarExportStatus() {
    val state by CarExport.state.collectAsState()
    when (val s = state) {
        is CarExport.ExportState.Running -> Column(Modifier.padding(top = 8.dp)) {
            Text(
                "Exporting ${s.done + 1}/${s.total} — ${s.current}",
                style = MaterialTheme.typography.bodySmall
            )
            LinearProgressIndicator(
                progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        is CarExport.ExportState.Finished -> Text(
            "Exported ${s.ok} track(s) to ${s.outDir.absolutePath}" +
                if (s.failed > 0) " — ${s.failed} failed" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )

        is CarExport.ExportState.Failed -> Text(
            s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp)
        )

        else -> Unit
    }
}
