package com.lyrenne.desktop.update

import com.lyrenne.desktop.InstallLocation.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The updater only rewrites folders it owns; everything else is told what to run. */
class UpdateRouteTest {

    private fun win(kind: Kind) = UpdateRoute.decide(isWindows = true, layout = kind, linuxPackage = null, env = emptyMap())
    private fun linux(pkg: String? = null, env: Map<String, String> = emptyMap()) =
        UpdateRoute.decide(isWindows = false, layout = Kind.XDG, linuxPackage = pkg, env = env)

    @Test fun `portable windows copy updates itself`() {
        assertEquals(UpdateRoute(true, ""), win(Kind.PORTABLE))
    }

    @Test fun `winget copy defers to winget`() {
        assertEquals(UpdateRoute(false, "Update with: winget upgrade Lyrenne"), win(Kind.WINGET))
    }

    @Test fun `read-only windows copies point at the release page`() {
        assertEquals(UpdateRoute(false, UpdateRoute.READ_ONLY_HINT), win(Kind.PROGRAM_FILES))
        assertEquals(UpdateRoute(false, UpdateRoute.READ_ONLY_HINT), win(Kind.READ_ONLY))
    }

    @Test fun `deb and rpm installs keep updating through pkexec`() {
        assertEquals(UpdateRoute(true, ""), linux("deb"))
        assertEquals(UpdateRoute(true, ""), linux("rpm"))
    }

    @Test fun `flatpak and appimage defer`() {
        assertEquals(UpdateRoute(false, UpdateRoute.FLATPAK_HINT), linux(env = mapOf("FLATPAK_ID" to "io.github.doctordefector.Lyrenne")))
        assertEquals(UpdateRoute(false, UpdateRoute.APPIMAGE_HINT), linux(env = mapOf("APPIMAGE" to "/home/ana/Lyrenne.AppImage")))
    }

    @Test fun `anything else on linux points at the package manager`() {
        assertEquals(UpdateRoute(false, UpdateRoute.PACKAGE_HINT), linux())
        assertEquals(UpdateRoute(false, UpdateRoute.PACKAGE_HINT), linux(env = mapOf("APPIMAGE" to "")))
    }
}
