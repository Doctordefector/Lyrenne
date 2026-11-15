package com.lyrenne.desktop

import java.io.File

/**
 * Where this copy keeps its data, decided once from where it was installed.
 *
 * The portable ZIP keeps everything next to `Lyrenne.exe`, and that must not change: people carry
 * it on USB sticks and Scoop persists `data` and `Downloads` out of that very folder. But a folder a
 * package manager owns is the wrong home for a library. winget deletes the whole package folder on
 * `winget upgrade` and `winget uninstall`, and has no `persist` to save it, so data written there
 * is a sign-in and a library waiting to vanish. A folder the user cannot write (Program Files) is
 * no home either. Those copies keep their data in `%LOCALAPPDATA%\Lyrenne` instead.
 *
 * Pure on purpose: every input is a parameter, so the whole table is unit tested
 * (`InstallLocationTest`) without touching the real disk or registry.
 */
object InstallLocation {

    enum class Kind {
        /** Data next to the app (the portable ZIP, Scoop, a Linux tarball with a `portable` marker). */
        PORTABLE,
        /** A winget portable install: winget owns the folder and replaces it on upgrade. */
        WINGET,
        /** Under Program Files: installed by something with admin rights, read-only to the user. */
        PROGRAM_FILES,
        /** Any other folder the user cannot write. */
        READ_ONLY,
        /** Linux and macOS packages: XDG data dir, the app itself sits read-only in /opt, /app or an AppImage. */
        XDG,
    }

    data class Layout(val kind: Kind, val dataDir: File, val downloadsDir: File) {
        /** True when data lives inside the app folder. */
        val portable: Boolean get() = kind == Kind.PORTABLE
        /** True when something other than the user owns the app folder, so the app must not rewrite it. */
        val managed: Boolean get() = kind == Kind.WINGET || kind == Kind.PROGRAM_FILES || kind == Kind.READ_ONLY
    }

    /** What the decision needs to know about the machine. */
    data class Inputs(
        val isWindows: Boolean,
        val appDir: File,
        val env: Map<String, String>,
        val userHome: File,
        /** A `portable` marker file sits next to the app (off Windows only). */
        val hasPortableMarker: Boolean,
        /** InstallLocation of every winget ARP entry for this package (HKCU and HKLM). */
        val wingetInstallLocations: List<String> = emptyList(),
        /** Asked only when nothing else decided: a real write probe is the only honest answer on Windows. */
        val appDirWritable: () -> Boolean,
        /** Off Windows: the XDG data and music dirs, resolved by the caller. */
        val xdgDataHome: () -> File = { File(userHome, ".local/share") },
        val xdgMusicDir: () -> File = { File(userHome, "Music") },
    )

    fun decide(i: Inputs): Layout {
        if (!i.isWindows) {
            return if (i.hasPortableMarker) portable(i)
            else Layout(Kind.XDG, File(i.xdgDataHome(), "lyrenne"), File(i.xdgMusicDir(), "Lyrenne"))
        }
        val kind = when {
            isWinget(i) -> Kind.WINGET
            isUnderProgramFiles(i) -> Kind.PROGRAM_FILES
            i.appDirWritable() -> Kind.PORTABLE
            else -> Kind.READ_ONLY
        }
        if (kind == Kind.PORTABLE) return portable(i)
        val localAppData = i.env.get("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(i.userHome, "AppData/Local")
        return Layout(kind, File(localAppData, "Lyrenne"), File(i.userHome, "Music/Lyrenne"))
    }

    private fun portable(i: Inputs) = Layout(Kind.PORTABLE, File(i.appDir, "data"), File(i.appDir, "Downloads"))

    /**
     * Both of winget's own install roots (`%LOCALAPPDATA%\Microsoft\WinGet\Packages` per user,
     * `%ProgramFiles%\WinGet\Packages` machine-wide), plus wherever `--location` put it, which only
     * the package's ARP entry knows.
     */
    private fun isWinget(i: Inputs): Boolean {
        val dir = normalize(i.appDir.path)
        if ("\\winget\\packages\\" in dir) return true
        return i.wingetInstallLocations.any { loc ->
            val root = normalize(loc).trimEnd('\\')
            root.isNotEmpty() && (dir == root || dir.startsWith("$root\\"))
        }
    }

    private fun isUnderProgramFiles(i: Inputs): Boolean {
        val dir = normalize(i.appDir.path)
        return listOf("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")
            .mapNotNull { i.env[it]?.takeIf(String::isNotBlank) }
            .ifEmpty { listOf("C:\\Program Files", "C:\\Program Files (x86)") }
            .map { normalize(it).trimEnd('\\') }
            .any { root -> dir == root || dir.startsWith("$root\\") }
    }

    /** Windows paths compare case-insensitively and either slash works. */
    private fun normalize(path: String): String = path.replace('/', '\\').lowercase().trimEnd('\\') + "\\"
}
