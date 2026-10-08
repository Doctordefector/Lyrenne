package com.lyrenne.desktop.auth

import com.lyrenne.desktop.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The browser half of sign-in against real browsers on Linux: discovery, the launch arguments,
 * the profile lock check, and reading + decrypting the cookies they write. Run signed out
 * (CI has no Google account), so it asserts on the cookies music.youtube.com gives any visitor;
 * whether those include a session is the one part only a real sign-in can show.
 * Linux CI only (LYRENNE_LINUX_E2E).
 */
class LinuxBrowserE2ETest {

    private fun assumeE2E() = assumeTrue(Platform.isLinux && System.getenv("LYRENNE_LINUX_E2E") != null)

    @Test
    fun `firefox is found first and its cookies are read`() {
        assumeE2E()
        val found = BrowserLoginHelper.findLoginBrowser()
        assertNotNull("no login browser found", found)
        assertEquals(BrowserKind.FIREFOX, found!!.kind)

        val browser = found.copy(profileDir = Files.createTempDirectory("ff-login").toFile())
        val cookies = visit(browser) { BrowserLoginHelper.isProfileInUse(browser) }
            .let { BrowserCookieExtractor.readFirefoxCookies(browser.profileDir, browser.name) }
        assertVisitorCookies(cookies)
    }

    @Test
    fun `chrome with the basic password store decrypts as v10`() {
        assumeE2E()
        val exe = listOf("google-chrome", "google-chrome-stable", "chromium")
            .flatMap { name -> System.getenv("PATH").split(':').map { File(it, name) } }
            .firstOrNull { it.canExecute() }
        assumeTrue("no Chrome on this runner", exe != null)

        val browser = LoginBrowser(BrowserKind.CHROMIUM, exe!!, "Chrome", Files.createTempDirectory("cr-login").toFile())
        assertTrue(BrowserLoginHelper.launchArgs(browser).contains("--password-store=basic"))
        visit(browser, extra = listOf("--headless=new", "--disable-gpu")) { BrowserLoginHelper.isProfileInUse(browser) }

        val dir = browser.profileDir
        val db = File(dir, "Default/Network/Cookies").takeIf { it.exists() } ?: File(dir, "Default/Cookies")
        val read = BrowserCookieExtractor.readChromiumCookies(db, File(dir, "Local State"), browser.name)
        assertTrue("read failed: $read", read is BrowserCookieExtractor.ChromiumRead)
        read as BrowserCookieExtractor.ChromiumRead
        assertFalse("cookies went to the keyring despite --password-store=basic", read.keyringBlocked)
        assertVisitorCookies(read.cookies)
    }

    /**
     * Launches [browser] headless with Lyrenne's own arguments, checks the lock reports it running,
     * then closes it the way a user would (SIGTERM, a clean shutdown that flushes cookies) and
     * checks the lock clears.
     */
    private fun visit(browser: LoginBrowser, extra: List<String> = listOf("--headless"), inUse: () -> Boolean) {
        val args = BrowserLoginHelper.launchArgs(browser).toMutableList().apply { addAll(1, extra) }
        val process = ProcessBuilder(args).redirectErrorStream(true)
            .redirectOutput(File(browser.profileDir.parentFile, "${browser.profileDir.name}.log")).start()
        try {
            Thread.sleep(20_000)
            assertTrue("${browser.name} exited early", process.isAlive)
            assertTrue("profile lock not seen while ${browser.name} runs", inUse())
        } finally {
            process.destroy()
            process.waitFor(30, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
        Thread.sleep(1_000)
        assertFalse("profile lock still present after ${browser.name} exited", inUse())
    }

    private fun assertVisitorCookies(result: Any) {
        assertTrue("expected cookies, got: $result", result is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val cookies = result as Map<String, String>
        println("cookies read: ${cookies.keys}")
        assertTrue("no YouTube/Google cookies were read", cookies.isNotEmpty())
        // Decryption garbage would show up as non-printable bytes; real values are printable ASCII.
        cookies.forEach { (name, value) ->
            assertTrue("$name looks undecrypted: $value", value.all { it.code in 0x20..0x7E })
        }
    }
}
