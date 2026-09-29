package com.lyrenne.desktop.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Calls the real shell COM objects, because a wrong vtable slot does not throw: it returns an
 * HRESULT nobody checks, or reads garbage. The ownership check that stops one copy of Lyrenne
 * deleting another copy's shortcut depends on reading the target back exactly.
 */
class WindowsStartMenuShortcutSmokeTest {

    @Test
    fun `a written shortcut reads back the executable it launches`() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        val dir = Files.createTempDirectory("lyrenne shortcut").toFile()
        try {
            val exe = File(File(dir, "Fake App ${'$'}(x)"), "Lyrenne.exe").apply { parentFile.mkdirs(); writeText("") }
            val link = File(dir, "Lyrenne.lnk")
            assertTrue(WindowsStartMenuShortcut.write(link, exe))
            assertTrue(link.isFile)
            assertEquals(exe.absoluteFile, File(WindowsStartMenuShortcut.readTargetPath(link)!!).absoluteFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
