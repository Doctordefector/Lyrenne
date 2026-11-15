package com.lyrenne.desktop.sound

import com.lyrenne.desktop.update.AutoUpdater
import com.metrolist.innertube.YouTube
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** What came back from a request. [headers] keys are lower case. */
data class HttpResult(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/**
 * The few HTTP calls ListenBrainz, SponsorBlock and AutoEQ make. An interface so their tests can
 * answer with fixtures instead of the network. A thrown IOException means the request never got
 * an answer (offline, DNS, timeout), which callers treat differently from an HTTP error.
 */
fun interface HttpTransport {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult

    fun get(url: String, headers: Map<String, String> = emptyMap()) = send("GET", url, headers, null)
}

/** The real transport: OkHttp, through the proxy set in Settings when there is one. */
object OkHttpTransport : HttpTransport {
    private val base = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val userAgent by lazy { "Lyrenne/${AutoUpdater.CURRENT_VERSION} (+https://github.com/Doctordefector/Lyrenne)" }

    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        // Read per call, so changing the proxy in Settings applies without a restart.
        val client = YouTube.proxy?.let { proxy ->
            base.newBuilder().proxy(proxy).apply {
                YouTube.proxyAuth?.let { auth ->
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder().header("Proxy-Authorization", auth).build()
                    }
                }
            }.build()
        } ?: base
        val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            headers.forEach { (k, v) -> header(k, v) }
            method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        client.newCall(request).execute().use { response ->
            return HttpResult(
                code = response.code,
                body = response.body?.string().orEmpty(),
                headers = response.headers.associate { (k, v) -> k.lowercase() to v }
            )
        }
    }
}
