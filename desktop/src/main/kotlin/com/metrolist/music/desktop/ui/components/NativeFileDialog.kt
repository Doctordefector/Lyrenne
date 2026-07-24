package com.metrolist.music.desktop.ui.components

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native OS file dialogs.
 *
 * `JFileChooser` is a Swing-drawn dialog — even with the system look and feel it only
 * imitates the platform. `java.awt.FileDialog` is the real thing (IFileDialog on Windows,
 * NSOpenPanel on macOS), so picking an actual file gets the dialog users expect, with their
 * quick-access places, search and recent locations.
 *
 * The catch: `FileDialog` cannot select directories on Windows, so folder pickers still
 * have to use JFileChooser.
 */
object NativeFileDialog {

    /** Save dialog. Returns null if cancelled. */
    fun save(title: String, defaultName: String, directory: File? = null): File? =
        show(title, FileDialog.SAVE, defaultName, directory, null)

    /** Open dialog, optionally limited to one extension (e.g. "zip"). Null if cancelled. */
    fun open(title: String, extension: String? = null, directory: File? = null): File? =
        show(title, FileDialog.LOAD, null, directory, extension)

    private fun show(
        title: String,
        mode: Int,
        defaultName: String?,
        directory: File?,
        extension: String?
    ): File? {
        val dialog = FileDialog(null as Frame?, title, mode)
        directory?.let { dialog.directory = it.absolutePath }
        defaultName?.let { dialog.file = it }
        if (extension != null) {
            // Honoured natively on Windows; on other platforms it's an additional filter.
            dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
            dialog.file = "*.$extension"
        }
        dialog.isVisible = true

        val chosen = dialog.file ?: return null
        val dir = dialog.directory ?: ""
        return File(dir, chosen)
    }
}
