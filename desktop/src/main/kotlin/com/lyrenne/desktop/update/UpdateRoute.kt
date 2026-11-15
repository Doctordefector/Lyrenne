package com.lyrenne.desktop.update

import com.lyrenne.desktop.InstallLocation

/**
 * How this copy gets its updates: by itself, or from whoever installed it.
 *
 * The updater swaps files in place, which is right for the portable ZIP and wrong anywhere a
 * package manager owns the folder. Rewriting a winget package folder leaves winget believing the
 * old version is installed (and Program Files cannot be written at all); a Flatpak lives in a
 * read-only /app; an AppImage is one file the user chose where to keep. Those copies say what to
 * run instead of offering a button that would fail or fight the package manager.
 */
internal data class UpdateRoute(val canSelfUpdate: Boolean, val hint: String) {
    companion object {
        const val WINGET_HINT = "Update with: winget upgrade Lyrenne"
        const val READ_ONLY_HINT = "Lyrenne's folder is read-only, so download the update from the release page"
        const val FLATPAK_HINT = "Update with: flatpak update, or your software center"
        const val APPIMAGE_HINT = "Download the new AppImage from the release page"
        const val PACKAGE_HINT = "Update it with your package manager"

        /**
         * @param linuxPackage "deb" or "rpm" when the lyrenne package owns this copy in /opt.
         * @param env FLATPAK_ID is set inside every Flatpak; APPIMAGE by the AppImage runtime.
         */
        fun decide(
            isWindows: Boolean,
            layout: InstallLocation.Kind,
            linuxPackage: String?,
            env: Map<String, String>,
        ): UpdateRoute = when {
            isWindows -> when (layout) {
                InstallLocation.Kind.WINGET -> UpdateRoute(false, WINGET_HINT)
                InstallLocation.Kind.PROGRAM_FILES, InstallLocation.Kind.READ_ONLY -> UpdateRoute(false, READ_ONLY_HINT)
                else -> UpdateRoute(true, "")
            }
            !env["FLATPAK_ID"].isNullOrBlank() -> UpdateRoute(false, FLATPAK_HINT)
            !env["APPIMAGE"].isNullOrBlank() -> UpdateRoute(false, APPIMAGE_HINT)
            linuxPackage != null -> UpdateRoute(true, "")
            else -> UpdateRoute(false, PACKAGE_HINT)
        }
    }
}
