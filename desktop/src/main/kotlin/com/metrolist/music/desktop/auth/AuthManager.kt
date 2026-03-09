package com.metrolist.music.desktop.auth

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.innertube.utils.sha1
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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

data class AuthState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val accountInfo: AccountInfo? = null,
    val error: String? = null
)

object AuthManager {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val credentialsFile: File by lazy {
        val appDir = getAppDataDir()
        appDir.mkdirs()
        File(appDir, "credentials.json")
    }

    private fun getAppDataDir(): File {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(appData, "Metrolist")
            }
            os.contains("mac") -> {
                File(System.getProperty("user.home"), "Library/Application Support/Metrolist")
            }
            else -> {
                File(System.getProperty("user.home"), ".config/metrolist")
            }
        }
    }

    fun initialize() {
        loadCredentials()
    }

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
        YouTube.accountIndex = credentials.accountIndex
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
            val ytcfg = fetchYtCfg(cookie)
            val actualDataSyncId = ytcfg["DATASYNC_ID"] ?: dataSyncId
            val sessionIndex = ytcfg["SESSION_INDEX"]?.toIntOrNull() ?: 0
            val pageVisitorData = ytcfg["VISITOR_DATA"]

            // Apply session index for multi-account support
            YouTube.accountIndex = sessionIndex

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
            YouTube.cookie = null
            YouTube.visitorData = null
            YouTube.dataSyncId = null
            YouTube.accountIndex = 0
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
    private suspend fun fetchYtCfg(cookie: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val client = HttpClient()
        try {
            // Build SAPISIDHASH from cookies
            val cookieMap = parseCookieString(cookie)
            val sapisid = cookieMap["SAPISID"] ?: return result
            val origin = "https://music.youtube.com"
            val currentTime = System.currentTimeMillis() / 1000
            val sapisidHash = sha1("$currentTime $sapisid $origin")

            val html = client.get(origin) {
                header("cookie", cookie)
                header("Authorization", "SAPISIDHASH ${currentTime}_${sapisidHash}")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
            }.bodyAsText()

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
        return result
    }
}
