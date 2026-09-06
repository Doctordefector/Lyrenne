package com.lyrenne.desktop.auth

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.innertube.utils.sha1
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class AccountInfo(
    val name: String,
    val email: String,
    val channelHandle: String,
    val avatarUrl: String? = null
)

@Serializable
data class AuthCredentials(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val accountIndex: Int = 0,
    val accountInfo: AccountInfo? = null
)

/** Everything one fetch of the YouTube Music page tells us about the session. */
private data class YtCfg(
    val values: Map<String, String>,
    val setCookies: List<String>,
    /** null means the page never answered, which is not the same as being signed out. */
    val loggedIn: Boolean?
)

data class AuthState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val accountInfo: AccountInfo? = null,
    val error: String? = null
)

object AuthManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val credentialsFile: File get() = com.lyrenne.desktop.AppPaths.credentialsFile

    /** Google rotates the session cookies faster than this; the point is only not to drift. */
    private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

    fun initialize() {
        loadCredentials()
        if (!_authState.value.isLoggedIn) return
        scope.launch {
            refreshSession()
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                if (_authState.value.isLoggedIn) refreshSession()
            }
        }
    }

    /**
     * Confirm the stored cookies are still good, and pull forward the ones Google rotated.
     *
     * An expired YouTube session does not fail loudly: the API answers HTTP 200 with an
     * anonymous response, so browses return zero items and writes 401. Having credentials.json
     * on disk therefore says nothing about being signed in, and the app would present a dead
     * session as a live one, with an empty library and silently discarded edits.
     *
     * Two things went wrong in the first version of this check. It called `YouTube.accountInfo()`
     * inside a `runCatching`, so any failure at all (no network yet after a restart, a timeout,
     * a 5xx) read as "expired" and signed a perfectly good session out. That is what made an
     * update look like it dropped the login: the app relaunches, the very first request loses a
     * race with the network coming back, and the user is asked to sign in again. And nothing
     * ever wrote rotated cookies back, so the stored snapshot aged in place until Google stopped
     * honouring it, which is the session quietly dying after a few weeks.
     *
     * The ytcfg page answers both questions at once: LOGGED_IN is authoritative about the
     * session, and the response carries the refreshed cookies. Anything short of an actual
     * answer leaves the stored login alone.
     */
    private suspend fun refreshSession() {
        val credentials = readCredentials() ?: return

        var cfg: YtCfg? = null
        repeat(3) { attempt ->
            if (cfg == null) {
                if (attempt > 0) delay(5_000)
                cfg = fetchYtCfg(credentials.cookie).takeIf { it.loggedIn != null }
            }
        }
        val answer = cfg
        if (answer == null) {
            Timber.w("Could not reach YouTube to check the session, keeping the stored login")
            return
        }
        if (answer.loggedIn == false) {
            Timber.w("Stored YouTube session is no longer valid, marking signed out")
            _authState.value = AuthState(
                isLoggedIn = false,
                error = "Your YouTube session expired. Sign in again to sync your library."
            )
            return
        }

        val refreshed = credentials.copy(
            cookie = mergeCookies(credentials.cookie, answer.setCookies),
            visitorData = answer.values["VISITOR_DATA"] ?: credentials.visitorData,
            dataSyncId = answer.values["DATASYNC_ID"] ?: credentials.dataSyncId,
            accountIndex = answer.values["SESSION_INDEX"]?.toIntOrNull() ?: credentials.accountIndex
        )
        if (refreshed == credentials) return
        runCatching {
            credentialsFile.writeText(json.encodeToString(refreshed))
            applyCredentials(refreshed)
            Timber.i("Refreshed the stored YouTube session")
        }.onFailure { Timber.e("Could not save the refreshed session: ${it.message}") }
    }

    /**
     * Fold a response's Set-Cookie headers into the stored cookie string, the way a browser would.
     *
     * Only cookies already held are updated. A name arriving mid-session is not something this
     * app knows how to need, and refusing them keeps a logged-out response, which arrives as a
     * pile of blanked cookies, from doing damage even if the LOGGED_IN guard above were ever
     * wrong. Blank values are skipped for the same reason: that is a deletion.
     */
    internal fun mergeCookies(current: String, setCookies: List<String>): String {
        val jar = LinkedHashMap<String, String>()
        for (part in current.split(";")) {
            val eq = part.indexOf('=')
            if (eq > 0) jar[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        for (header in setCookies) {
            val pair = header.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name !in jar || value.isEmpty() || value == "\"\"") continue
            jar[name] = value
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun readCredentials(): AuthCredentials? = runCatching {
        json.decodeFromString<AuthCredentials>(credentialsFile.readText())
    }.getOrNull()

    private fun loadCredentials() {
        try {
            if (credentialsFile.exists()) {
                val credentials = json.decodeFromString<AuthCredentials>(credentialsFile.readText())
                applyCredentials(credentials)
                _authState.value = AuthState(
                    isLoggedIn = true,
                    accountInfo = credentials.accountInfo
                )
            }
        } catch (e: Exception) {
            Timber.e("Failed to load credentials: ${e.message}")
            _authState.value = AuthState(isLoggedIn = false)
        }
    }

    private fun applyCredentials(credentials: AuthCredentials) {
        YouTube.cookie = credentials.cookie
        YouTube.visitorData = credentials.visitorData.takeIf { it.isNotBlank() }
        YouTube.authUser = credentials.accountIndex.toString()
        // Process dataSyncId: strip "||" suffix
        YouTube.dataSyncId = credentials.dataSyncId.takeIf { it.isNotBlank() }?.let { raw ->
            raw.takeIf { !it.contains("||") }
                ?: raw.takeIf { it.endsWith("||") }?.substringBefore("||")
                ?: raw.substringAfter("||")
        }
        YouTube.useLoginForBrowse = true
    }

    suspend fun saveCredentials(
        cookie: String,
        visitorData: String,
        dataSyncId: String
    ): Result<AccountInfo> {
        _authState.value = _authState.value.copy(isLoading = true, error = null)

        return try {
            YouTube.cookie = cookie
            YouTube.useLoginForBrowse = true

            // Fetch ytcfg from YouTube Music page to get DATASYNC_ID, SESSION_INDEX, and visitorData
            val ytcfg = fetchYtCfg(cookie).values
            val actualDataSyncId = ytcfg["DATASYNC_ID"] ?: dataSyncId
            val sessionIndex = ytcfg["SESSION_INDEX"]?.toIntOrNull() ?: 0
            val pageVisitorData = ytcfg["VISITOR_DATA"]

            // Apply session index for multi-account support
            YouTube.authUser = sessionIndex.toString()

            // Use provided visitorData, or page visitorData, or fetch from API
            val actualVisitorData = visitorData.takeIf { it.isNotBlank() }
                ?: pageVisitorData
                ?: try { YouTube.visitorData().getOrNull() } catch (_: Exception) { null }
            YouTube.visitorData = actualVisitorData

            // Process dataSyncId
            YouTube.dataSyncId = actualDataSyncId.takeIf { it.isNotBlank() }?.let { raw ->
                raw.takeIf { !it.contains("||") }
                    ?: raw.takeIf { it.endsWith("||") }?.substringBefore("||")
                    ?: raw.substringAfter("||")
            }

            // Fetch account info
            val accountInfo = try {
                val ytAccountInfo = YouTube.accountInfo().getOrThrow()
                AccountInfo(
                    name = ytAccountInfo.name,
                    email = ytAccountInfo.email ?: "",
                    channelHandle = ytAccountInfo.channelHandle ?: "",
                    avatarUrl = ytAccountInfo.thumbnailUrl
                )
            } catch (e: Exception) {
                // Fallback: try extracting from ytcfg page
                val pageName = ytcfg["ACCOUNT_NAME"]
                if (!pageName.isNullOrBlank()) {
                    AccountInfo(name = pageName, email = "", channelHandle = "")
                } else {
                    AccountInfo(name = "YouTube Music User", email = "", channelHandle = "")
                }
            }

            val credentials = AuthCredentials(
                cookie = cookie,
                visitorData = actualVisitorData ?: "",
                dataSyncId = actualDataSyncId,
                accountIndex = sessionIndex,
                accountInfo = accountInfo
            )

            credentialsFile.writeText(json.encodeToString(credentials))

            _authState.value = AuthState(
                isLoggedIn = true,
                accountInfo = accountInfo
            )

            Result.success(accountInfo)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(
                isLoading = false,
                error = "Failed to save credentials: ${e.message}"
            )
            Result.failure(e)
        }
    }

    fun logout() {
        try {
            if (credentialsFile.exists()) {
                credentialsFile.delete()
            }
            // Deleting credentials.json alone was not a sign-out: the browser login profile is a
            // second copy of the same session, and it outlived the thing it duplicated.
            BrowserLoginHelper.clearLoginProfile()
            YouTube.cookie = null
            YouTube.visitorData = null
            YouTube.dataSyncId = null
            YouTube.authUser = "0"
            YouTube.useLoginForBrowse = false

            _authState.value = AuthState(isLoggedIn = false)
        } catch (e: Exception) {
            Timber.e("Failed to logout: ${e.message}")
        }
    }

    fun clearError() {
        _authState.value = _authState.value.copy(error = null)
    }

    /**
     * Fetch ytcfg values from YouTube Music page HTML.
     * Extracts DATASYNC_ID, SESSION_INDEX, VISITOR_DATA, LOGGED_IN.
     * Requires proper SAPISIDHASH for authenticated access.
     */
    private suspend fun fetchYtCfg(cookie: String): YtCfg {
        val result = mutableMapOf<String, String>()
        var setCookies = emptyList<String>()
        val client = HttpClient()
        try {
            // Build SAPISIDHASH from cookies
            val cookieMap = parseCookieString(cookie)
            val sapisid = cookieMap["SAPISID"] ?: return YtCfg(result, setCookies, null)
            val origin = "https://music.youtube.com"
            val currentTime = System.currentTimeMillis() / 1000
            val sapisidHash = sha1("$currentTime $sapisid $origin")

            val response = client.get(origin) {
                header("cookie", cookie)
                header("Authorization", "SAPISIDHASH ${currentTime}_${sapisidHash}")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
            }
            setCookies = response.headers.getAll("Set-Cookie").orEmpty()
            val html = response.bodyAsText()

            // Extract key-value pairs from ytcfg.set calls
            val setPattern = """ytcfg\.set\(\{(.*?)\}\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
            for (match in setPattern.findAll(html)) {
                val block = match.groupValues[1]
                // Extract DATASYNC_ID
                """"DATASYNC_ID"\s*:\s*"([^"]+)"""".toRegex().find(block)?.let {
                    result["DATASYNC_ID"] = it.groupValues[1]
                }
                // Extract SESSION_INDEX
                """"SESSION_INDEX"\s*:\s*"?(\d+)"?""".toRegex().find(block)?.let {
                    result["SESSION_INDEX"] = it.groupValues[1]
                }
                // Extract LOGGED_IN
                """"LOGGED_IN"\s*:\s*(true|false)""".toRegex().find(block)?.let {
                    result["LOGGED_IN"] = it.groupValues[1]
                }
            }

            // Extract visitorData from INNERTUBE_CONTEXT
            """"visitorData"\s*:\s*"([^"]+)"""".toRegex().find(html)?.let {
                result["VISITOR_DATA"] = it.groupValues[1]
            }

            // Extract account name if available
            """"accountName"\s*:\s*\{[^}]*"simpleText"\s*:\s*"([^"]+)"""".toRegex().find(html)?.let {
                result["ACCOUNT_NAME"] = it.groupValues[1]
            }
        } catch (e: Exception) {
            Timber.e("fetchYtCfg error: ${e.message}")
        } finally {
            client.close()
        }
        return YtCfg(result, setCookies, result["LOGGED_IN"]?.toBooleanStrictOrNull())
    }
}
