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

/** Releases carry packages for two architectures; the updater must take this machine's. */
class LinuxAssetPickTest {
    private val assets = listOf(
        "Lyrenne-2.21.0-portable.zip", "lyrenne_2.21.0_amd64.deb", "lyrenne_2.21.0_amd64.deb.sig",
        "lyrenne_2.21.0_arm64.deb", "lyrenne-2.21.0-1.x86_64.rpm", "lyrenne-arm64-2.21.0-1.aarch64.rpm",
        "Lyrenne-2.21.0-x86_64.AppImage",
    )

    @Test fun `x86_64 gets amd64 packages`() {
        assertEquals("lyrenne_2.21.0_amd64.deb", AutoUpdater.linuxAssetName(assets, "deb", "amd64"))
        assertEquals("lyrenne-2.21.0-1.x86_64.rpm", AutoUpdater.linuxAssetName(assets, "rpm", "amd64"))
    }

    @Test fun `arm64 gets arm64 packages`() {
        assertEquals("lyrenne_2.21.0_arm64.deb", AutoUpdater.linuxAssetName(assets, "deb", "aarch64"))
        assertEquals("lyrenne-arm64-2.21.0-1.aarch64.rpm", AutoUpdater.linuxAssetName(assets, "rpm", "aarch64"))
    }

    @Test fun `updaters up to 2_20_5 take the first package by name, which must be x86_64`() {
        // GitHub lists assets sorted by name, and old builds pick the first one ending in .deb/.rpm.
        val listed = assets.sorted()
        assertEquals("lyrenne_2.21.0_amd64.deb", listed.first { it.endsWith(".deb") })
        assertEquals("lyrenne-2.21.0-1.x86_64.rpm", listed.first { it.endsWith(".rpm") })
    }

    @Test fun `a release without this arch offers nothing rather than the wrong one`() {
        assertEquals(null, AutoUpdater.linuxAssetName(listOf("lyrenne_2.20.5_amd64.deb"), "deb", "aarch64"))
    }
}
