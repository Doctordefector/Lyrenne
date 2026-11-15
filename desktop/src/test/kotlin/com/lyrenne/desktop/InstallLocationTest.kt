package com.lyrenne.desktop

import com.lyrenne.desktop.InstallLocation.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The location decision table. A wrong answer here signs people out and hides their library (data
 * looked for in the wrong place) or loses it on the next `winget upgrade` (data kept in a folder
 * winget deletes), so every row is pinned.
 */
class InstallLocationTest {

    private val home = File("C:\\Users\\ana")
    private val localAppData = "C:\\Users\\ana\\AppData\\Local"
    private val winEnv = mapOf(
        "LOCALAPPDATA" to localAppData,
        "ProgramFiles" to "C:\\Program Files",
        "ProgramFiles(x86)" to "C:\\Program Files (x86)",
        "ProgramW6432" to "C:\\Program Files",
    )

    private fun windows(
        appDir: String,
        writable: Boolean = true,
        env: Map<String, String> = winEnv,
        winget: List<String> = emptyList(),
    ) = InstallLocation.decide(
        InstallLocation.Inputs(
            isWindows = true,
            appDir = File(appDir),
            env = env,
            userHome = home,
            hasPortableMarker = false,
            wingetInstallLocations = winget,
            appDirWritable = { writable },
        )
    )

    private val managedData = File(localAppData, "Lyrenne")
    private val managedDownloads = File(home, "Music/Lyrenne")

    private fun assertPortable(layout: InstallLocation.Layout, appDir: String) {
        assertEquals(Kind.PORTABLE, layout.kind)
        assertTrue(layout.portable)
        assertFalse(layout.managed)
        assertEquals(File(appDir, "data"), layout.dataDir)
        assertEquals(File(appDir, "Downloads"), layout.downloadsDir)
    }

    private fun assertManaged(layout: InstallLocation.Layout, kind: Kind) {
        assertEquals(kind, layout.kind)
        assertFalse(layout.portable)
        assertTrue(layout.managed)
        assertEquals(managedData, layout.dataDir)
        assertEquals(managedDownloads, layout.downloadsDir)
    }

    @Test fun `unzipped portable copy keeps data next to the exe`() {
        val dir = "D:\\Apps\\Lyrenne"
        assertPortable(windows(dir), dir)
    }

    @Test fun `portable copy on a USB stick or in Documents stays portable`() {
        assertPortable(windows("E:\\Lyrenne"), "E:\\Lyrenne")
        assertPortable(windows("C:\\Users\\ana\\Documents\\Lyrenne"), "C:\\Users\\ana\\Documents\\Lyrenne")
    }

    @Test fun `a portable copy the user put under LOCALAPPDATA Programs stays portable`() {
        val dir = "$localAppData\\Programs\\Lyrenne"
        assertPortable(windows(dir), dir)
    }

    @Test fun `scoop is unaffected, persist keeps data and Downloads`() {
        val dir = "C:\\Users\\ana\\scoop\\apps\\lyrenne\\current\\Lyrenne"
        assertPortable(windows(dir), dir)
        val versioned = "C:\\Users\\ana\\scoop\\apps\\lyrenne\\2.20.5"
        assertPortable(windows(versioned), versioned)
    }

    @Test fun `winget user scope package folder is managed`() {
        val dir = "$localAppData\\Microsoft\\WinGet\\Packages\\Doctordefector.Lyrenne_Microsoft.Winget.Source_8wekyb3d8bbwe\\Lyrenne"
        // Writable, but winget deletes it on upgrade: writability must not matter.
        assertManaged(windows(dir, writable = true), Kind.WINGET)
    }

    @Test fun `winget path match ignores case and slash direction`() {
        val dir = "c:/users/ana/appdata/local/microsoft/winget/packages/Doctordefector.Lyrenne_x/Lyrenne"
        assertManaged(windows(dir), Kind.WINGET)
    }

    @Test fun `winget machine scope under Program Files is winget, not just Program Files`() {
        val dir = "C:\\Program Files\\WinGet\\Packages\\Doctordefector.Lyrenne_Microsoft.Winget.Source_8wekyb3d8bbwe\\Lyrenne"
        assertManaged(windows(dir, writable = false), Kind.WINGET)
    }

    @Test fun `winget --location install is recognised from its ARP entry`() {
        val dir = "D:\\Tools\\Lyrenne\\Lyrenne"
        assertManaged(windows(dir, winget = listOf("D:\\Tools\\Lyrenne")), Kind.WINGET)
        // The ARP location being exactly the app dir counts too.
        assertManaged(windows(dir, winget = listOf("d:/tools/lyrenne/lyrenne/")), Kind.WINGET)
    }

    @Test fun `an ARP entry for a sibling folder with a shared prefix does not count`() {
        val dir = "D:\\Tools\\LyrenneOld\\Lyrenne"
        assertPortable(windows(dir, winget = listOf("D:\\Tools\\Lyrenne", "")), dir)
    }

    @Test fun `Program Files is managed even when an elevated run could write it`() {
        assertManaged(windows("C:\\Program Files\\Lyrenne", writable = true), Kind.PROGRAM_FILES)
        assertManaged(windows("C:\\Program Files (x86)\\Lyrenne", writable = false), Kind.PROGRAM_FILES)
    }

    @Test fun `Program Files on another drive comes from the environment`() {
        val env = winEnv + ("ProgramFiles" to "D:\\Programme") + ("ProgramW6432" to "D:\\Programme")
        assertManaged(windows("D:\\Programme\\Lyrenne", env = env), Kind.PROGRAM_FILES)
    }

    @Test fun `a folder merely named like Program Files is not Program Files`() {
        val dir = "C:\\Program Files Stuff\\Lyrenne"
        assertPortable(windows(dir), dir)
    }

    @Test fun `any other folder the user cannot write is managed`() {
        assertManaged(windows("\\\\server\\share\\Lyrenne", writable = false), Kind.READ_ONLY)
    }

    @Test fun `writability is only probed when the path did not decide`() {
        var probed = 0
        val inputs = { dir: String ->
            InstallLocation.Inputs(true, File(dir), winEnv, home, false, emptyList(), appDirWritable = { probed++; true })
        }
        InstallLocation.decide(inputs("$localAppData\\Microsoft\\WinGet\\Packages\\x\\Lyrenne"))
        InstallLocation.decide(inputs("C:\\Program Files\\Lyrenne"))
        assertEquals(0, probed)
        InstallLocation.decide(inputs("D:\\Apps\\Lyrenne"))
        assertEquals(1, probed)
    }

    @Test fun `missing LOCALAPPDATA falls back to the profile`() {
        val layout = windows("C:\\Program Files\\Lyrenne", env = winEnv - "LOCALAPPDATA")
        assertEquals(File(home, "AppData/Local/Lyrenne"), layout.dataDir)
    }

    @Test fun `missing Program Files variables still recognise the default folders`() {
        val layout = windows("C:\\Program Files\\Lyrenne", env = mapOf("LOCALAPPDATA" to localAppData))
        assertEquals(Kind.PROGRAM_FILES, layout.kind)
    }

    private fun linux(appDir: String, marker: Boolean) = InstallLocation.decide(
        InstallLocation.Inputs(
            isWindows = false,
            appDir = File(appDir),
            env = emptyMap(),
            userHome = File("/home/ana"),
            hasPortableMarker = marker,
            appDirWritable = { error("never probed off Windows") },
            xdgDataHome = { File("/home/ana/.local/share") },
            xdgMusicDir = { File("/home/ana/Musik") },
        )
    )

    @Test fun `linux packages, AppImage and Flatpak use XDG`() {
        for (dir in listOf("/opt/lyrenne", "/tmp/.mount_LyrennXYZ/Lyrenne", "/app/lib/lyrenne")) {
            val layout = linux(dir, marker = false)
            assertEquals(Kind.XDG, layout.kind)
            assertFalse(layout.managed)
            assertEquals(File("/home/ana/.local/share/lyrenne"), layout.dataDir)
            assertEquals(File("/home/ana/Musik/Lyrenne"), layout.downloadsDir)
        }
    }

    @Test fun `linux tarball with a portable marker keeps data next to the app`() {
        assertPortable(linux("/home/ana/Lyrenne", marker = true), "/home/ana/Lyrenne")
    }
}
