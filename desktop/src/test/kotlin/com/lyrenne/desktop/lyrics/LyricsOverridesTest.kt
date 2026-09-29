package com.lyrenne.desktop.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LyricsOverridesTest {

    @Test
    fun `a chosen result survives a reload and can be forgotten`() {
        val dir = Files.createTempDirectory("lyrics-overrides").toFile()
        try {
            val file = File(dir, "lyrics-overrides.json")
            LyricsOverrides.file = file
            LyricsOverrides.put("song1", LyricsCandidate("LrcLib", "[00:01.00]hi"))

            // Re-pointing the file drops the cache, so this really reads the disk.
            LyricsOverrides.file = file
            assertEquals(LyricsCandidate("LrcLib", "[00:01.00]hi"), LyricsOverrides.get("song1"))

            LyricsOverrides.remove("song1")
            LyricsOverrides.file = file
            assertNull(LyricsOverrides.get("song1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a corrupt file is ignored rather than breaking lookups`() {
        val dir = Files.createTempDirectory("lyrics-overrides").toFile()
        try {
            val file = File(dir, "lyrics-overrides.json").apply { writeText("{not json") }
            LyricsOverrides.file = file
            assertNull(LyricsOverrides.get("anything"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
