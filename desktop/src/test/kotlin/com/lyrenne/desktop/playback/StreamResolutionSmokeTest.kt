package com.lyrenne.desktop.playback

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.auth.AuthCredentials
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertube.strategy.ContentHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * Live-network smoke test for the playback stream path: walks the same client chain as
 * DesktopPlayer, deobfuscates the chosen format, and fetches the first bytes of the stream.
 * Fails if YouTube rejects every client or 403s the stream URL (the issue #3 failure mode).
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
        val report = StringBuilder()
        var streamUrl: String? = null
        var usedClient: String? = null

        for (client in ContentAwareFallbackStrategy().resolveClients(ContentHints())) {
            if (client.requirePoToken) continue
            if (client.loginRequired && YouTube.cookie == null) continue
            val signatureTimestamp = if (client.useSignatureTimestamp) {
                withContext(Dispatchers.IO) { NewPipeUtils.getSignatureTimestamp(videoId).getOrNull() }
            } else null
            val response = YouTube.player(videoId, client = client, signatureTimestamp = signatureTimestamp)
                .getOrNull()
            val status = response?.playabilityStatus?.status
            if (status != "OK") {
                report.appendLine("${client.clientName} ${client.clientVersion}: ${status ?: "request failed"} ${response?.playabilityStatus?.reason.orEmpty()}")
                continue
            }
            val format = response.streamingData?.adaptiveFormats
                ?.filter { it.isAudio }
                ?.maxByOrNull { it.bitrate }
            val url = format?.let {
                withContext(Dispatchers.IO) { NewPipeUtils.getStreamUrl(it, videoId).getOrNull() }
            }
            if (url.isNullOrEmpty()) {
                report.appendLine("${client.clientName}: OK but no usable audio URL")
                continue
            }
            streamUrl = url
            usedClient = client.clientName
            report.appendLine("${client.clientName}: OK, URL resolved")
            break
        }

        println(report)
        assertTrue("No client produced a stream URL:\n$report", streamUrl != null)

        // Fetch the first bytes to prove the URL is not rejected the way VLC saw it.
        val code = withContext(Dispatchers.IO) {
            val conn = URI(streamUrl).toURL().openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=0-1023")
            conn.responseCode
        }
        println("stream fetch via $usedClient: HTTP $code")
        assertTrue("Stream URL rejected: HTTP $code", code == 200 || code == 206)
    }
}
