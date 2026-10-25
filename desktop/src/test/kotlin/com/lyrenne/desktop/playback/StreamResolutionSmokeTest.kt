package com.lyrenne.desktop.playback

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.auth.AuthCredentials
import com.lyrenne.desktop.download.DownloadManager
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * Live-network smoke test for the stream path: resolves through DownloadManager's client chain
 * (the same one DesktopPlayer walks) and fetches the first bytes of the stream.
 * Fails if YouTube rejects every client or 403s the stream URL (issues #3 and #17).
 */
class StreamResolutionSmokeTest {

    @Test
    fun resolvesPlayableAudioStream(): Unit = runBlocking {
        // Use the real dev session when present; expired sessions degrade to anonymous.
        if (AppPaths.credentialsFile.exists()) {
            val c = Json { ignoreUnknownKeys = true }
                .decodeFromString<AuthCredentials>(AppPaths.credentialsFile.readText())
            YouTube.cookie = c.cookie
            YouTube.visitorData = c.visitorData.takeIf { it.isNotBlank() }
            YouTube.authUser = c.accountIndex.toString()
            YouTube.dataSyncId = c.dataSyncId.takeIf { it.isNotBlank() }?.let { raw ->
                raw.takeIf { !it.contains("||") }
                    ?: raw.takeIf { it.endsWith("||") }?.substringBefore("||")
                    ?: raw.substringAfter("||")
            }
        }

        val videoId = "dQw4w9WgXcQ"
        // The real resolver, which checks each client's URL before using it (issue #17)
        val streamUrl = DownloadManager.getStreamInfo(videoId)?.baseUrl
        assertTrue("No client produced a stream URL (per-client reasons are in the log)", streamUrl != null)

        // Fetch the first bytes to prove the URL is not rejected the way VLC saw it.
        val code = withContext(Dispatchers.IO) {
            val conn = URI(streamUrl).toURL().openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=0-1023")
            conn.responseCode
        }
        println("stream fetch: HTTP $code")
        assertTrue("Stream URL rejected: HTTP $code", code == 200 || code == 206)

        // A URL YouTube refuses must fail the check, or the resolver never moves to the next client.
        // Changing expire breaks the URL's signature, which YouTube answers with 403.
        val refused = Regex("""([?&]expire=)(\d+)""").replace(streamUrl!!) { it.groupValues[1] + "1" }
        val refusedCode = withContext(Dispatchers.IO) { DownloadManager.streamStatus(refused) }
        println("tampered URL check: HTTP $refusedCode")
        assertTrue("Check passed a refused URL: HTTP $refusedCode", refusedCode !in 200..299)
    }
}
