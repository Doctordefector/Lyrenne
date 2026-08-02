package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.auth.BrowserLoginHelper
import com.lyrenne.desktop.auth.CookieExtractResult
import com.lyrenne.desktop.integration.LastFmManager
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.settings.AudioQuality
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.settings.ThemeMode
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import javax.swing.JFileChooser

private const val STEP_WELCOME = 0
private const val STEP_SIGN_IN = 1
private const val STEP_LOOK = 2
private const val STEP_FILES = 3
private const val STEP_CONTENT = 4
private const val STEP_INTEGRATIONS = 5
private const val STEP_DONE = 6
private const val STEP_COUNT = 7

/** Sub-state of the sign-in step. Mirrors LoginScreen's flow without its Scaffold and back button. */
private enum class SignInPhase { IDLE, WAITING, VALIDATING, ERROR }

/**
 * First-run setup. Shown when `onboardingCompleted` is false, and re-runnable from Settings.
 *
 * Signing in is the one step with no way past it. Every other screen only sets preferences that
 * already have working defaults, so "Next" without touching anything is a valid way through.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by remember { mutableStateOf(STEP_WELCOME) }
    val authState by AuthManager.authState.collectAsState()

    // The only hard gate. Playback needs a signed-in session, so advancing past it without one
    // would drop the user into an app that looks fine and plays nothing.
    val canAdvance = step != STEP_SIGN_IN || authState.isLoggedIn

    fun finish() {
        PreferencesManager.setOnboardingCompleted(true)
        onFinish()
    }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        StepDots(current = step, count = STEP_COUNT)
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            when (step) {
                STEP_WELCOME -> WelcomeStep()
                STEP_SIGN_IN -> SignInStep()
                STEP_LOOK -> LookStep()
                STEP_FILES -> FilesStep()
                STEP_CONTENT -> ContentStep()
                STEP_INTEGRATIONS -> IntegrationsStep()
                STEP_DONE -> DoneStep()
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > STEP_WELCOME) {
                TextButton(onClick = { step-- }) { Text("Back") }
            }

            Spacer(Modifier.weight(1f))

            // Everything after sign-in is optional, so offer a way out of the rest of it.
            if (step in (STEP_SIGN_IN + 1) until STEP_DONE) {
                TextButton(onClick = { step = STEP_DONE }) { Text("Skip the rest") }
                Spacer(Modifier.width(8.dp))
            }

            Button(
                onClick = { if (step == STEP_DONE) finish() else step++ },
                enabled = canAdvance
            ) {
                Text(if (step == STEP_DONE) "Start listening" else "Next")
                if (step != STEP_DONE) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .weight(1f)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (i <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
}

/** A labelled row of choices. FilterChip rather than SegmentedButton, which is still experimental. */
@Composable
private fun <T> ChipChoice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) }
            )
        }
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

// ---------------------------------------------------------------- steps

private val CLOUD_MARKERS = listOf("onedrive", "dropbox", "icloud", "google drive", "creative cloud")

@Composable
private fun WelcomeStep() {
    StepHeading(
        "Welcome to Lyrenne",
        "A YouTube Music player for Windows. This takes about a minute, and everything here " +
            "can be changed later in Settings."
    )

    val appDir = remember { AppPaths.appDir.absolutePath }
    val cloudSynced = remember(appDir) { CLOUD_MARKERS.any { appDir.contains(it, ignoreCase = true) } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Default.FolderSpecial, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Lyrenne is portable", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your library, login, settings and downloads all live in this folder and " +
                        "nowhere else. Nothing is written to AppData, and moving the folder " +
                        "takes everything with it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    appDir,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    if (cloudSynced) {
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(Modifier.padding(16.dp)) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "This folder looks cloud-synced",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A sync client that reopens the database mid-write will corrupt it or " +
                            "lock it on startup. Close Lyrenne and move this folder somewhere " +
                            "local, such as C:\\Lyrenne.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun SignInStep() {
    val scope = rememberCoroutineScope()
    val authState by AuthManager.authState.collectAsState()
    var phase by remember { mutableStateOf(SignInPhase.IDLE) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val browser = remember { BrowserLoginHelper.findBrowserExecutable() }
    val browserName = remember(browser) {
        when {
            browser == null -> "your browser"
            browser.absolutePath.contains("Edge", ignoreCase = true) -> "Edge"
            browser.absolutePath.contains("Chrome", ignoreCase = true) -> "Chrome"
            browser.absolutePath.contains("brave", ignoreCase = true) -> "Brave"
            else -> "your browser"
        }
    }

    fun signIn() {
        phase = SignInPhase.WAITING
        status = "Opening $browserName..."
        error = null
        scope.launch {
            when (val result = BrowserLoginHelper.loginWithBrowser { status = it }) {
                is CookieExtractResult.Success -> {
                    phase = SignInPhase.VALIDATING
                    status = "Signing in..."
                    AuthManager.saveCredentials(result.cookie, visitorData = "", dataSyncId = "")
                        .onSuccess { phase = SignInPhase.IDLE }
                        .onFailure {
                            error = "${it::class.simpleName}: ${it.message}"
                            phase = SignInPhase.ERROR
                        }
                }
                is CookieExtractResult.Error -> {
                    error = result.message
                    phase = SignInPhase.ERROR
                }
            }
        }
    }

    StepHeading(
        "Sign in to YouTube Music",
        "This one is required. Lyrenne plays from your own YouTube Music account, so without " +
            "a signed-in session there is no library to show and nothing to play."
    )

    if (authState.isLoggedIn) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Signed in as ${authState.accountInfo?.name ?: "your account"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    authState.accountInfo?.channelHandle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
        return
    }

    when (phase) {
        SignInPhase.WAITING, SignInPhase.VALIDATING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(status, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Accepted the cookie prompt and signed in? Close $browserName completely to " +
                    "finish. That means every window of it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { phase = SignInPhase.IDLE }) { Text("Cancel") }
        }

        else -> {
            // Shown before the button, not after. Once the browser launches it takes focus and
            // nobody reads this window again until they are done in it.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("What is about to happen", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    InstructionLine(
                        "1",
                        "$browserName opens on YouTube Music in a clean, separate profile."
                    )
                    InstructionLine(
                        "2",
                        "When it asks about cookies, choose Accept all. Lyrenne signs in by " +
                            "reading those cookies afterwards. If you decline them, there is " +
                            "nothing for it to read."
                    )
                    InstructionLine(
                        "3",
                        "Sign in to your Google account as normal. If $browserName offers to " +
                            "save your password afterwards, you can say no. Accepting the " +
                            "cookies is the part that matters. Saving the password is not, and " +
                            "Lyrenne never sees it either way."
                    )
                    InstructionLine(
                        "4",
                        "Close $browserName completely. Lyrenne picks it up from there."
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (browser == null) {
                Text(
                    "No supported browser found. Lyrenne needs Microsoft Edge, Chrome or Brave " +
                        "installed to sign in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Button(onClick = { signIn() }) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (error != null) "Try again" else "Open $browserName and sign in")
                }
            }
        }
    }
}

@Composable
private fun InstructionLine(number: String, text: String) {
    Row(Modifier.padding(bottom = 12.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LookStep() {
    val preferences by PreferencesManager.preferences.collectAsState()

    StepHeading("Look and sound", "Two quick ones.")

    ChipChoice(
        label = "Theme",
        options = listOf(
            ThemeMode.SYSTEM to "Follow system",
            ThemeMode.LIGHT to "Light",
            ThemeMode.DARK to "Dark"
        ),
        selected = preferences.themeMode,
        onSelect = { PreferencesManager.setThemeMode(it) }
    )

    Spacer(Modifier.height(24.dp))

    ChipChoice(
        label = "Audio quality",
        options = AudioQuality.entries.map { it to it.displayName },
        selected = preferences.audioQuality,
        onSelect = { PreferencesManager.setAudioQuality(it) }
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Higher quality uses more bandwidth. This applies to streaming and to downloads.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FilesStep() {
    val preferences by PreferencesManager.preferences.collectAsState()
    // getDownloadDirectory() resolves the default when downloadPath is null, so this always
    // shows the real path rather than an empty field the user has to guess at.
    val downloadDir = remember(preferences.downloadPath) {
        PreferencesManager.getDownloadDirectory().absolutePath
    }

    StepHeading(
        "Downloads and the window",
        "Where music lands, and what the close button does."
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Downloaded music", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                downloadDir,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "By default this is a Downloads folder inside Lyrenne's own folder, so it " +
                    "travels with the app. Point it anywhere you like.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val chooser = JFileChooser(downloadDir).apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Choose Downloads Folder"
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        PreferencesManager.setDownloadPath(chooser.selectedFile.absolutePath)
                    }
                }) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Change folder")
                }
                if (preferences.downloadPath != null) {
                    TextButton(onClick = { PreferencesManager.setDownloadPath(null) }) {
                        Text("Use default")
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    ToggleRow(
        icon = Icons.Default.DesktopWindows,
        title = "Keep playing when I close the window",
        subtitle = "Closing the window hides Lyrenne in the system tray instead of quitting. " +
            "Turn this off and the close button really does quit.",
        checked = preferences.minimizeToTray,
        onCheckedChange = { PreferencesManager.setMinimizeToTray(it) }
    )
}

@Composable
private fun ContentStep() {
    val preferences by PreferencesManager.preferences.collectAsState()

    StepHeading(
        "Content and startup",
        "Region decides what your home feed and charts look like."
    )

    var countryOpen by remember { mutableStateOf(false) }
    var languageOpen by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("Content country") },
        supportingContent = {
            Text(
                contentCountries.firstOrNull { it.first == preferences.contentCountry }?.second
                    ?: preferences.contentCountry
            )
        },
        leadingContent = { Icon(Icons.Default.Public, contentDescription = null) },
        trailingContent = {
            TextButton(onClick = { countryOpen = true }) { Text("Change") }
        }
    )
    DropdownMenu(expanded = countryOpen, onDismissRequest = { countryOpen = false }) {
        contentCountries.forEach { (code, name) ->
            DropdownMenuItem(
                text = { Text(name) },
                onClick = {
                    PreferencesManager.setContentCountry(code)
                    com.lyrenne.desktop.applyNetworkPreferences()
                    countryOpen = false
                },
                leadingIcon = {
                    if (preferences.contentCountry == code) Icon(Icons.Default.Check, null)
                }
            )
        }
    }

    ListItem(
        headlineContent = { Text("Content language") },
        supportingContent = {
            Text(
                contentLanguages.firstOrNull { it.first == preferences.contentLanguage }?.second
                    ?: preferences.contentLanguage
            )
        },
        leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
        trailingContent = {
            TextButton(onClick = { languageOpen = true }) { Text("Change") }
        }
    )
    DropdownMenu(expanded = languageOpen, onDismissRequest = { languageOpen = false }) {
        contentLanguages.forEach { (code, name) ->
            DropdownMenuItem(
                text = { Text(name) },
                onClick = {
                    PreferencesManager.setContentLanguage(code)
                    com.lyrenne.desktop.applyNetworkPreferences()
                    languageOpen = false
                },
                leadingIcon = {
                    if (preferences.contentLanguage == code) Icon(Icons.Default.Check, null)
                }
            )
        }
    }

    ToggleRow(
        icon = Icons.Default.Block,
        title = "Hide explicit content",
        subtitle = "Filters explicit songs and albums out of search, home and explore",
        checked = preferences.hideExplicit,
        onCheckedChange = { PreferencesManager.setHideExplicit(it) }
    )

    ToggleRow(
        icon = Icons.Default.Sync,
        title = "Sync my library at startup",
        subtitle = "Fetches new liked songs, albums, artists and playlists shortly after launch",
        checked = preferences.autoSyncOnStartup,
        onCheckedChange = { PreferencesManager.setAutoSyncOnStartup(it) }
    )

    ToggleRow(
        icon = Icons.Default.SystemUpdate,
        title = "Check for updates at startup",
        subtitle = "Asks GitHub once per launch whether a newer version exists. Nothing " +
            "installs without you clicking it.",
        checked = preferences.checkUpdatesOnLaunch,
        onCheckedChange = { PreferencesManager.setCheckUpdatesOnLaunch(it) }
    )

    ToggleRow(
        icon = Icons.Default.Notifications,
        title = "Notify me on song change",
        subtitle = "A tray notification each time the track changes",
        checked = preferences.notificationsEnabled,
        onCheckedChange = { PreferencesManager.setNotificationsEnabled(it) }
    )
}

@Composable
private fun IntegrationsStep() {
    val preferences by PreferencesManager.preferences.collectAsState()
    val scope = rememberCoroutineScope()

    StepHeading("Discord and Last.fm", "Both optional, both off unless you turn them on.")

    ToggleRow(
        icon = Icons.Default.Gamepad,
        title = "Discord Rich Presence",
        subtitle = "Shows what you're listening to on your Discord profile",
        checked = preferences.discordRpcEnabled,
        onCheckedChange = { PreferencesManager.setDiscordRpcEnabled(it) }
    )

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    // Four fields including a password is a lot to put in front of someone who does not use
    // Last.fm, so the form stays closed until they say they do.
    var wantsLastFm by remember { mutableStateOf(preferences.lastFmSessionKey != null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = wantsLastFm, onCheckedChange = { wantsLastFm = it })
        Spacer(Modifier.width(8.dp))
        Column {
            Text("I use Last.fm", style = MaterialTheme.typography.titleSmall)
            Text(
                "Scrobble every track you play to your Last.fm profile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (!wantsLastFm) return

    Spacer(Modifier.height(16.dp))

    if (preferences.lastFmSessionKey != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Connected as ${preferences.lastFmUsername ?: "unknown"}",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                TextButton(onClick = {
                    PreferencesManager.setLastFmCredentials(null, null, null, null)
                    PreferencesManager.setLastFmEnabled(false)
                }) { Text("Disconnect") }
            }
        }
        return
    }

    var apiKey by remember { mutableStateOf(preferences.lastFmApiKey ?: "") }
    var secret by remember { mutableStateOf(preferences.lastFmSecret ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(
        "Last.fm needs an API key of your own. It is free and takes a minute to create.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = { Text("API Key") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().suppressMediaKeys()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = secret,
        onValueChange = { secret = it },
        label = { Text("Shared Secret") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().suppressMediaKeys()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("Last.fm username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().suppressMediaKeys()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Last.fm password") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().suppressMediaKeys()
    )

    status?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (it.startsWith("Error")) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy,
            onClick = {
                if (apiKey.isBlank() || secret.isBlank() || username.isBlank() || password.isBlank()) {
                    status = "Error: all four fields are required"
                    return@Button
                }
                busy = true
                status = null
                scope.launch {
                    LastFmManager.login(apiKey, secret, username, password)
                        .onSuccess { sessionKey ->
                            PreferencesManager.setLastFmCredentials(apiKey, secret, sessionKey, username)
                            PreferencesManager.setLastFmEnabled(true)
                            status = "Connected."
                            password = ""
                        }
                        .onFailure { status = "Error: ${it.message}" }
                    busy = false
                }
            }
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Connect")
        }
        TextButton(onClick = {
            try {
                Desktop.getDesktop().browse(URI("https://www.last.fm/api/account/create"))
            } catch (_: Exception) {
            }
        }) { Text("Get an API key") }
    }
}

private val SHORTCUTS = listOf(
    "Space  ·  Ctrl+P" to "Play / pause",
    "Ctrl+←  ·  Ctrl+→" to "Previous / next track",
    "Ctrl+↑  ·  Ctrl+↓" to "Volume up / down",
    "Ctrl+S" to "Shuffle",
    "Ctrl+R" to "Repeat",
    "Ctrl+F" to "Jump to search",
    "Ctrl+Q" to "Show the queue",
    "Ctrl+L" to "Show lyrics",
    "Ctrl+K" to "Command palette",
    "Esc" to "Close an overlay, or go back",
    "Media keys" to "Always work, even in another app"
)

@Composable
private fun DoneStep() {
    StepHeading(
        "You're set up",
        "Everything here lives in Settings if you want to change it later, and you can run " +
            "this setup again from Settings → System."
    )

    Text("Worth knowing", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(12.dp))
    SHORTCUTS.forEach { (keys, action) ->
        Row(Modifier.padding(vertical = 4.dp)) {
            Text(
                keys,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(180.dp)
            )
            Text(
                action,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Default.Backup, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(
                "Because Lyrenne keeps everything in its own folder, backing it up is a matter " +
                    "of copying that folder. Settings → Backup & Restore does the same job.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
