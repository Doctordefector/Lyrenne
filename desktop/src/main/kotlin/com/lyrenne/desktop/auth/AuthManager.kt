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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val accountInfo: AccountInfo? = null,
    /**
     * Stable identity for this account, used to tell saved accounts apart. Assigned once at
     * sign-in and carried through every refresh, because the values it is derived from can
     * change or arrive late, and a moving id would make one account look like two.
     */
    val accountId: String? = null,
    /** Set when YouTube said this saved account is signed out. Only ever true on a saved one. */
    val sessionExpired: Boolean = false,
)

/** An account signed in once and kept for switching back to with one click. */
data class SavedAccount(
    val id: String,
    val accountInfo: AccountInfo?,
    val sessionExpired: Boolean,
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
    val error: String? = null,
    /** Which account is signed in. Changes on a switch even though isLoggedIn does not. */
    val accountId: String? = null,
)

object AuthManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** Tests point this at a temporary folder; the app always uses data/. */
    internal var storageDirOverride: File? = null

    private val credentialsFile: File
        get() = storageDirOverride?.let { File(it, "credentials.json") } ?: com.lyrenne.desktop.AppPaths.credentialsFile

    /**
     * Accounts that are signed in but not active, one `<accountId>.json` each, in exactly the
     * format of credentials.json and just as plaintext. credentials.json stays the active account,
     * so nothing that reads it needed to change.
     */
    private val accountsDir: File
        get() = File(storageDirOverride ?: com.lyrenne.desktop.AppPaths.dataDir, "accounts")

    private val _savedAccounts = MutableStateFlow<List<SavedAccount>>(emptyList())
    val savedAccounts: StateFlow<List<SavedAccount>> = _savedAccounts.asStateFlow()

    /**
     * Serialises every read-modify-write of the credential files. A switch moves credentials.json
     * aside while a refresh may be about to write it; without this, a refresh that started for
     * the old account landed its cookies on top of the new one.
     */
    private val filesLock = Mutex()

    /** Google rotates the session cookies faster than this; the point is only not to drift. */
    private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

    fun initialize() {
        loadFromDisk()
        scope.launch {
            // Saved accounts need their cookies rotated too, or switching to one after a few
            // weeks lands on a session Google has stopped honouring.
            while (true) {
                if (_authState.value.isLoggedIn) refreshSession()
                refreshSavedAccounts()
                delay(REFRESH_INTERVAL_MS)
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
            filesLock.withLock {
                // A switch may have happened while the page was loading.
                if (readCredentials()?.accountId != credentials.accountId) return
                Timber.w("Stored YouTube session is no longer valid, marking signed out")
                _authState.value = AuthState(
                    isLoggedIn = false,
                    error = "Your YouTube session expired. Sign in again to sync your library."
                )
            }
            return
        }

        val refreshed = credentials.copy(
            cookie = mergeCookies(credentials.cookie, answer.setCookies),
            visitorData = answer.values["VISITOR_DATA"] ?: credentials.visitorData,
            dataSyncId = answer.values["DATASYNC_ID"] ?: credentials.dataSyncId,
            accountIndex = answer.values["SESSION_INDEX"]?.toIntOrNull() ?: credentials.accountIndex
        )
        if (refreshed == credentials) return
        filesLock.withLock {
            if (readCredentials() != credentials) {
                Timber.i("Credentials changed while refreshing, dropping the stale refresh")
                return
            }
            runCatching {
                credentialsFile.writeText(json.encodeToString(refreshed))
                applyCredentials(refreshed)
                Timber.i("Refreshed the stored YouTube session")
            }.onFailure { Timber.e("Could not save the refreshed session: ${it.message}") }
        }
    }

    /**
     * Same job as [refreshSession] for the accounts not in use: fold rotated cookies back in, and
     * mark any that YouTube reports signed out so the switcher can say so instead of switching
     * into an anonymous session. An unreachable page leaves the file alone, as it does for the
     * active account.
     */
    private suspend fun refreshSavedAccounts() {
        for (file in savedAccountFiles()) {
            val saved = readAccountFile(file) ?: continue
            val cfg = fetchYtCfg(saved.cookie).takeIf { it.loggedIn != null } ?: continue
            val updated = if (cfg.loggedIn == false) {
                saved.copy(sessionExpired = true)
            } else {
                saved.copy(
                    cookie = mergeCookies(saved.cookie, cfg.setCookies),
                    visitorData = cfg.values["VISITOR_DATA"] ?: saved.visitorData,
                    sessionExpired = false,
                )
            }
            if (updated == saved) continue
            filesLock.withLock {
                // Switched to, or removed, while the page was loading.
                if (!file.isFile || readAccountFile(file) != saved) return@withLock
                runCatching { file.writeText(json.encodeToString(updated)) }
                    .onFailure { Timber.w("Could not save a refreshed saved account: ${it.message}") }
            }
        }
        loadSavedAccounts()
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

    private fun readAccountFile(file: File): AuthCredentials? = runCatching {
        json.decodeFromString<AuthCredentials>(file.readText())
    }.getOrNull()

    private fun savedAccountFiles(): List<File> =
        accountsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    /** Reads the active and saved accounts. Separate from [initialize] so tests skip the network. */
    internal fun loadFromDisk() {
        loadCredentials()
        // A restored backup can bring back a saved copy of the account that is now active.
        // The active one is the newer of the two, so the saved copy goes.
        readCredentials()?.let { active -> dropSavedCopiesOf(active) }
        loadSavedAccounts()
    }

    private fun loadSavedAccounts() {
        val active = readCredentials()
        _savedAccounts.value = savedAccountFiles().mapNotNull { file ->
            val creds = readAccountFile(file) ?: return@mapNotNull null
            // Never list the active account a second time, whatever is on disk.
            if (active != null && sameAccount(creds, active)) return@mapNotNull null
            SavedAccount(
                id = file.nameWithoutExtension,
                accountInfo = creds.accountInfo,
                sessionExpired = creds.sessionExpired,
            )
        }.sortedBy { it.accountInfo?.name?.lowercase() ?: "" }
    }

    /**
     * Derives an account id from what identifies the account to YouTube. The DATASYNC_ID is the
     * account's own id and is preferred; the SAPISID cookie is per sign-in and stands in when the
     * page did not return one. Hashed, so the id is a safe file name and says nothing on its own.
     */
    internal fun deriveAccountId(dataSyncId: String, cookie: String): String {
        val basis = dataSyncId.substringBefore("||").takeIf { it.isNotBlank() }
            ?: parseCookieString(cookie)["SAPISID"]
            ?: cookie
        return sha1(basis).take(16)
    }

    /**
     * Whether two credential sets are the same Google account.
     *
     * The id alone is not enough. It falls back to the SAPISID cookie when a sign-in's ytcfg page
     * gave no DATASYNC_ID, and SAPISID changes with every sign-in, so one account can carry two
     * ids. The DATASYNC_ID and the email address are checked as well, whenever both sides have one.
     */
    internal fun sameAccount(a: AuthCredentials, b: AuthCredentials): Boolean {
        if (a.accountId != null && a.accountId == b.accountId) return true
        val da = a.dataSyncId.substringBefore("||")
        val db = b.dataSyncId.substringBefore("||")
        if (da.isNotBlank() && db.isNotBlank()) return da == db
        // Email only settles it when one side has no DATASYNC_ID: a brand channel shares its
        // owner's email while being a different account with its own id.
        val ea = a.accountInfo?.email.orEmpty()
        val eb = b.accountInfo?.email.orEmpty()
        return ea.isNotBlank() && ea.equals(eb, ignoreCase = true)
    }

    /** Deletes every saved entry for the same account as [creds]. Caller holds [filesLock], or is starting up. */
    private fun dropSavedCopiesOf(creds: AuthCredentials) {
        for (file in savedAccountFiles()) {
            val saved = readAccountFile(file) ?: continue
            if (sameAccount(saved, creds) && file.delete()) {
                Timber.i("Dropped a saved copy of the active account")
            }
        }
    }

    private fun AuthCredentials.withId(): AuthCredentials =
        if (accountId != null) this else copy(accountId = deriveAccountId(dataSyncId, cookie))

    private fun loadCredentials() {
        try {
            if (credentialsFile.exists()) {
                val stored = json.decodeFromString<AuthCredentials>(credentialsFile.readText())
                // Credentials from before account switching have no id yet. Give them one and
                // keep it, so it never changes under a refresh.
                val credentials = stored.withId()
                if (credentials != stored) {
                    runCatching { credentialsFile.writeText(json.encodeToString(credentials)) }
                }
                applyCredentials(credentials)
                _authState.value = AuthState(
                    isLoggedIn = true,
                    accountInfo = credentials.accountInfo,
                    accountId = credentials.accountId,
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
                accountInfo = accountInfo,
                accountId = deriveAccountId(actualDataSyncId, cookie),
            )

            filesLock.withLock {
                // Signing in while already signed in is how an account gets added: the one that
                // was active is kept, not overwritten. Signing the same account in again just
                // replaces it, and drops any saved copy of it.
                stashActive(except = credentials)
                dropSavedCopiesOf(credentials)
                credentialsFile.writeText(json.encodeToString(credentials))
                // Inside the lock, so memory and disk can never name different accounts.
                applyCredentials(credentials)
                _authState.value = AuthState(
                    isLoggedIn = true,
                    accountInfo = accountInfo,
                    accountId = credentials.accountId,
                )
            }
            loadSavedAccounts()

            Result.success(accountInfo)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(
                isLoading = false,
                error = "Failed to save credentials: ${e.message}"
            )
            Result.failure(e)
        }
    }

    /**
     * Moves the active account into the saved set, unless it is the same account as [except].
     * Caller holds [filesLock].
     */
    private fun stashActive(except: AuthCredentials?) {
        val active = readCredentials()?.withId() ?: return
        if (except != null && sameAccount(active, except)) return
        accountsDir.mkdirs()
        File(accountsDir, "${active.accountId}.json")
            // An active account YouTube already signed out is kept, but marked, so it is not offered
            // as a one-click switch into an anonymous session.
            .writeText(json.encodeToString(active.copy(sessionExpired = !_authState.value.isLoggedIn)))
        Timber.i("Kept ${active.accountInfo?.name ?: "the previous account"} for switching back")
    }

    /**
     * Makes a saved account the active one, keeping the current one saved in its place.
     *
     * No network is involved in the switch itself, so it is instant. The session is checked
     * straight afterwards by the same refresh that runs at startup, which also marks the account
     * signed out if YouTube no longer honours it.
     */
    suspend fun switchTo(accountId: String): Result<Unit> = runCatching {
        val switched = filesLock.withLock {
            val file = File(accountsDir, "$accountId.json")
            val target = readAccountFile(file)?.withId() ?: error("That account is no longer saved")
            if (target.sessionExpired) {
                error("This account's session expired. Sign in to it again to use it.")
            }
            stashActive(except = target)
            credentialsFile.writeText(json.encodeToString(target))
            file.delete()
            // Inside the lock: two switches in quick succession otherwise interleaved here, and
            // the app could end up playing as one account while credentials.json held another.
            applyCredentials(target)
            _authState.value = AuthState(
                isLoggedIn = true,
                accountInfo = target.accountInfo,
                accountId = target.accountId,
            )
            target
        }
        loadSavedAccounts()
        Timber.i("Switched to ${switched.accountInfo?.name ?: "another account"}")
        scope.launch { refreshSession() }
    }

    /** Forgets a saved account. Its cookies are deleted from disk. */
    suspend fun removeSaved(accountId: String) {
        filesLock.withLock { File(accountsDir, "$accountId.json").delete() }
        loadSavedAccounts()
    }

    /**
     * Signs the active account out. Saved accounts are kept, so the others stay one click away;
     * removing those is its own action.
     */
    fun logout() {
        // Taken so a refresh cannot write credentials.json back between the delete and the
        // state change, which would leave a live session on disk behind a signed-out UI. The
        // lock is only ever held for file writes, so this waits milliseconds at most.
        runBlocking { filesLock.withLock { logoutLocked() } }
    }

    /**
     * Keeps the account that is active before a backup's credentials.json is written over it,
     * so restoring a backup never loses whoever was signed in.
     */
    internal fun stashActiveBeforeRestore() {
        runBlocking { filesLock.withLock { stashActive(except = null) } }
    }

    private fun logoutLocked() {
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
