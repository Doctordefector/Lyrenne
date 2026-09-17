package com.lyrenne.desktop.integration

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import timber.log.Timber

/**
 * Gives the process an explicit Application User Model ID.
 *
 * Windows identifies a running program by its AUMID, and a plain Win32 process that never sets
 * one is given an implicit ID derived from its executable path. Anything that has to *name* the
 * process then has to resolve that ID back to an application, and for a portable build there is
 * nothing to resolve it against: no package manifest, no installer entry. The Now Playing flyout
 * is the visible consequence, where Lyrenne's track and artwork appear under the heading
 * "Unknown app".
 *
 * Setting the ID explicitly is a prerequisite, not the whole fix, and it has to happen before
 * anything that consumes it runs: before the first window and before the media session is
 * registered. Windows confirms the session now carries this ID rather than a path-derived one.
 * Turning the ID into a *name* is a second step, because the shell resolves it through the app
 * resolver, which only knows about packaged apps and Start Menu shortcuts. A portable build has
 * neither, so the flyout can still fall back to "Unknown app". Writing that shortcut is planned
 * as an opt-in setting rather than something a portable app does behind the user's back, since it
 * is the only file Lyrenne would put outside its own folder.
 *
 * The call is worth making on its own regardless: it stops the taskbar splitting Lyrenne's windows
 * into separate groups, which is what it was designed for.
 *
 * This is best-effort. The app works without it, so a missing shell32 export or a non-Windows
 * host is logged and ignored rather than allowed to stop startup.
 */
internal object WindowsAppIdentity {

    /**
     * Any shortcut written later must carry this exact string, or the shell is left with an ID it
     * still cannot put a name to. Reverse-DNS form, as the shell documentation requires, and well
     * inside the 128 character limit.
     */
    const val APP_USER_MODEL_ID = "com.lyrenne.desktop.Lyrenne"

    private interface Shell32 : StdCallLibrary {
        fun SetCurrentProcessExplicitAppUserModelID(appID: WString): Int
    }

    fun apply() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        try {
            val shell32 = Native.load("shell32", Shell32::class.java)
            val result = shell32.SetCurrentProcessExplicitAppUserModelID(WString(APP_USER_MODEL_ID))
            if (result != 0) {
                Timber.w("Could not set the app user model id: HRESULT 0x${result.toString(16)}")
            }
        } catch (e: Throwable) {
            Timber.w("Could not set the app user model id: ${e.message}")
        }
    }
}
