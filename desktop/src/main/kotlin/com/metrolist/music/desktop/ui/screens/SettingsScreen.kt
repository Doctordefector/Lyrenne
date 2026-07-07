package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.settings.AudioQuality
import com.metrolist.music.desktop.settings.PreferencesManager
import com.metrolist.music.desktop.settings.ThemeMode
import com.metrolist.music.desktop.integration.LastFmManager
import com.metrolist.music.desktop.media.suppressMediaKeys
import com.metrolist.music.desktop.update.AutoUpdater
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import javax.swing.JFileChooser

@Composable
fun SettingsScreen(
    onLoginClick: () -> Unit = {}
) {
    val authState by AuthManager.authState.collectAsState()
    val preferences by PreferencesManager.preferences.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Calculate cache size
    var cacheSize by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheSize = PreferencesManager.getCacheUsage()
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out? You'll need to sign in again to access your library and playlists.") },
            confirmButton = {
                Button(
                    onClick = {
                        AuthManager.logout()
                        showLogoutDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear cache dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Clear Cache") },
            text = { Text("This will delete ${formatBytes(cacheSize)} of cached data. Downloaded songs will not be affected.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            PreferencesManager.clearCache()
                            cacheSize = 0L
                        }
                        showClearCacheDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(16.dp))
        }

        // Account section
        item {
            SettingsSectionHeader("Account")
        }

        item {
            if (authState.isLoggedIn && authState.accountInfo != null) {
                // Logged in - show account card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar
                        if (authState.accountInfo?.avatarUrl != null) {
                            AsyncImage(
                                model = authState.accountInfo?.avatarUrl,
                                contentDescription = "Profile",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = authState.accountInfo?.name?.firstOrNull()?.uppercase() ?: "?",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        // Account info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = authState.accountInfo?.name ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (authState.accountInfo?.email?.isNotBlank() == true) {
                                Text(
                                    text = authState.accountInfo?.email ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (authState.accountInfo?.channelHandle?.isNotBlank() == true) {
                                Text(
                                    text = authState.accountInfo?.channelHandle?.let { handle ->
                                        if (handle.startsWith("@")) handle else "@$handle"
                                    } ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Logout button
                        FilledTonalIconButton(
                            onClick = { showLogoutDialog = true }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, "Sign Out")
                        }
                    }
                }
            } else {
                // Not logged in - show sign in prompt
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onLoginClick() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        Spacer(Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Sign in to YouTube Music",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Access your playlists, liked songs, and more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null
                        )
                    }
                }
            }
        }

        // Appearance section
        item {
            SettingsSectionHeader("Appearance")
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                icon = Icons.Default.DarkMode,
                title = "Theme",
                subtitle = when (preferences.themeMode) {
                    ThemeMode.DARK -> "Dark"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.SYSTEM -> "System"
                },
                onClick = { expanded = true }
            ) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                PreferencesManager.setThemeMode(mode)
                                expanded = false
                            },
                            leadingIcon = {
                                if (preferences.themeMode == mode) {
                                    Icon(Icons.Default.Check, null)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Audio section
        item {
            SettingsSectionHeader("Audio")
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                icon = Icons.Default.HighQuality,
                title = "Audio Quality",
                subtitle = preferences.audioQuality.displayName,
                onClick = { expanded = true }
            ) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    AudioQuality.entries.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(quality.displayName) },
                            onClick = {
                                PreferencesManager.setAudioQuality(quality)
                                expanded = false
                            },
                            leadingIcon = {
                                if (preferences.audioQuality == quality) {
                                    Icon(Icons.Default.Check, null)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Playback section
        item {
            SettingsSectionHeader("Playback")
        }

        item {
            SettingsItem(
                icon = Icons.Default.Speed,
                title = "Skip Silence",
                subtitle = "Automatically skip silent parts",
                trailing = {
                    Switch(
                        checked = preferences.skipSilence,
                        onCheckedChange = { PreferencesManager.setSkipSilence(it) }
                    )
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Normalize Audio",
                subtitle = "Keep volume consistent across tracks",
                trailing = {
                    Switch(
                        checked = preferences.normalizeAudio,
                        onCheckedChange = { PreferencesManager.setNormalizeAudio(it) }
                    )
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Remember Queue",
                subtitle = "Restore queue on app restart",
                trailing = {
                    Switch(
                        checked = preferences.persistQueue,
                        onCheckedChange = { PreferencesManager.setPersistQueue(it) }
                    )
                }
            )
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                icon = Icons.Default.SwapHoriz,
                title = "Crossfade",
                subtitle = if (preferences.crossfadeSec == 0) "Off"
                else "${preferences.crossfadeSec} seconds",
                onClick = { expanded = true }
            ) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(0, 1, 2, 3, 5, 8, 12).forEach { sec ->
                        DropdownMenuItem(
                            text = { Text(if (sec == 0) "Off" else "$sec seconds") },
                            onClick = {
                                PreferencesManager.setCrossfadeSec(sec)
                                expanded = false
                            },
                            leadingIcon = {
                                if (preferences.crossfadeSec == sec) Icon(Icons.Default.Check, null)
                            }
                        )
                    }
                }
            }
        }

        item {
            SettingsItem(
                icon = Icons.Default.Radio,
                title = "Auto-Queue Related Songs",
                subtitle = "Keep the music going when your queue runs out",
                trailing = {
                    Switch(
                        checked = preferences.autoLoadRadio,
                        onCheckedChange = { PreferencesManager.setAutoLoadRadio(it) }
                    )
                }
            )
        }

        // Content section
        item {
            SettingsSectionHeader("Content")
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                icon = Icons.Default.Public,
                title = "Content Country",
                subtitle = if (preferences.contentCountry == "system") "System default" else preferences.contentCountry,
                onClick = { expanded = true }
            ) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    contentCountries.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                PreferencesManager.setContentCountry(code)
                                com.metrolist.music.desktop.applyNetworkPreferences()
                                expanded = false
                            },
                            leadingIcon = {
                                if (preferences.contentCountry == code) Icon(Icons.Default.Check, null)
                            }
                        )
                    }
                }
            }
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                icon = Icons.Default.Language,
                title = "Content Language",
                subtitle = if (preferences.contentLanguage == "system") "System default" else preferences.contentLanguage,
                onClick = { expanded = true }
            ) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    contentLanguages.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                PreferencesManager.setContentLanguage(code)
                                com.metrolist.music.desktop.applyNetworkPreferences()
                                expanded = false
                            },
                            leadingIcon = {
                                if (preferences.contentLanguage == code) Icon(Icons.Default.Check, null)
                            }
                        )
                    }
                }
            }
        }

        item {
            SettingsItem(
                icon = Icons.Default.Block,
                title = "Hide Explicit Content",
                subtitle = "Filter explicit songs and albums from search, home and explore",
                trailing = {
                    Switch(
                        checked = preferences.hideExplicit,
                        onCheckedChange = { PreferencesManager.setHideExplicit(it) }
                    )
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Recommend,
                title = "Quick Picks",
                subtitle = "Show recommendations based on your last listen",
                trailing = {
                    Switch(
                        checked = preferences.quickPicks,
                        onCheckedChange = { PreferencesManager.setQuickPicks(it) }
                    )
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Shuffle,
                title = "Randomize Home Sections",
                subtitle = "Shuffle the order of home feed sections",
                trailing = {
                    Switch(
                        checked = preferences.homeRandomize,
                        onCheckedChange = { PreferencesManager.setHomeRandomize(it) }
                    )
                }
            )
        }

        // Library section
        item {
            SettingsSectionHeader("Library")
        }

        item {
            SettingsItem(
                icon = Icons.Default.Favorite,
                title = "Auto-Download on Like",
                subtitle = "Download songs automatically when you like them",
                trailing = {
                    Switch(
                        checked = preferences.autoDownloadOnLike,
                        onCheckedChange = { PreferencesManager.setAutoDownloadOnLike(it) }
                    )
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Sync,
                title = "Sync Library on Startup",
                subtitle = "Automatically fetch new liked songs, albums, artists, and playlists",
                trailing = {
                    Switch(
                        checked = preferences.autoSyncOnStartup,
                        onCheckedChange = { PreferencesManager.setAutoSyncOnStartup(it) }
                    )
                }
            )
        }

        // Privacy section
        item {
            SettingsSectionHeader("Privacy")
        }

        item {
            SettingsItem(
                icon = Icons.Default.History,
                title = "Pause Listen History",
                subtitle = "Stop recording plays for stats and history",
                trailing = {
                    Switch(
                        checked = preferences.pauseListenHistory,
                        onCheckedChange = { PreferencesManager.setPauseListenHistory(it) }
                    )
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.SearchOff,
                title = "Pause Search History",
                subtitle = "Stop saving your search queries",
                trailing = {
                    Switch(
                        checked = preferences.pauseSearchHistory,
                        onCheckedChange = { PreferencesManager.setPauseSearchHistory(it) }
                    )
                }
            )
        }

        // Lyrics section
        item {
            SettingsSectionHeader("Lyrics")
        }

        item {
            SettingsItem(
                icon = Icons.Default.FormatSize,
                title = "Lyrics Text Size",
                subtitle = "${preferences.lyricsTextSize.toInt()} pt",
                trailing = {
                    Slider(
                        value = preferences.lyricsTextSize,
                        onValueChange = { PreferencesManager.setLyricsTextSize(it) },
                        valueRange = 12f..32f,
                        steps = 9,
                        modifier = Modifier.width(160.dp)
                    )
                }
            )
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                icon = Icons.Default.FormatAlignLeft,
                title = "Lyrics Alignment",
                subtitle = preferences.lyricsPosition.name.lowercase().replaceFirstChar { it.uppercase() },
                onClick = { expanded = true }
            ) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    com.metrolist.music.desktop.settings.LyricsPosition.entries.forEach { pos ->
                        DropdownMenuItem(
                            text = { Text(pos.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                PreferencesManager.setLyricsPosition(pos)
                                expanded = false
                            },
                            leadingIcon = {
                                if (preferences.lyricsPosition == pos) Icon(Icons.Default.Check, null)
                            }
                        )
                    }
                }
            }
        }

        item {
            SettingsItem(
                icon = Icons.Default.TouchApp,
                title = "Click Lyrics to Seek",
                subtitle = "Jump to a line by clicking it",
                trailing = {
                    Switch(
                        checked = preferences.lyricsClickToSeek,
                        onCheckedChange = { PreferencesManager.setLyricsClickToSeek(it) }
                    )
                }
            )
        }

        // Network section
        item {
            SettingsSectionHeader("Network")
        }

        item {
            SettingsItem(
                icon = Icons.Default.VpnLock,
                title = "Use Proxy",
                subtitle = if (preferences.proxyEnabled)
                    "${preferences.proxyType.name} ${preferences.proxyHost}:${preferences.proxyPort}"
                else
                    "Route YouTube traffic through a proxy",
                trailing = {
                    Switch(
                        checked = preferences.proxyEnabled,
                        onCheckedChange = {
                            PreferencesManager.setProxyEnabled(it)
                            com.metrolist.music.desktop.applyNetworkPreferences()
                        }
                    )
                }
            )
        }

        if (preferences.proxyEnabled) {
            item {
                ProxyConfigCard(
                    type = preferences.proxyType,
                    host = preferences.proxyHost,
                    port = preferences.proxyPort,
                    username = preferences.proxyUsername,
                    password = preferences.proxyPassword
                )
            }
        }

        // Discord section
        item {
            SettingsSectionHeader("Discord")
        }

        item {
            SettingsItem(
                icon = Icons.Default.Gamepad,
                title = "Discord Rich Presence",
                subtitle = if (preferences.discordRpcEnabled)
                    "Showing what you're listening to on Discord"
                else
                    "Show what you're listening to on Discord",
                trailing = {
                    Switch(
                        checked = preferences.discordRpcEnabled,
                        onCheckedChange = { PreferencesManager.setDiscordRpcEnabled(it) }
                    )
                }
            )
        }

        // Last.fm section
        item {
            SettingsSectionHeader("Last.fm")
        }

        item {
            SettingsItem(
                icon = Icons.Default.MusicNote,
                title = "Last.fm Scrobbling",
                subtitle = if (preferences.lastFmEnabled) "Enabled" else "Disabled",
                trailing = {
                    Switch(
                        checked = preferences.lastFmEnabled,
                        onCheckedChange = { PreferencesManager.setLastFmEnabled(it) }
                    )
                }
            )
        }

        item {
            var apiKey by remember(preferences.lastFmApiKey) {
                mutableStateOf(preferences.lastFmApiKey ?: "")
            }
            var secret by remember(preferences.lastFmSecret) {
                mutableStateOf(preferences.lastFmSecret ?: "")
            }
            var username by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var loginStatus by remember { mutableStateOf<String?>(null) }
            var isLoggingIn by remember { mutableStateOf(false) }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (preferences.lastFmSessionKey != null) {
                    // Already authenticated
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Connected as ${preferences.lastFmUsername ?: "Unknown"}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            TextButton(onClick = {
                                PreferencesManager.setLastFmCredentials(null, null, null, null)
                            }) {
                                Text("Disconnect")
                            }
                        }
                    }
                } else {
                    // Login form
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth().suppressMediaKeys(),
                        label = { Text("API Key") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        modifier = Modifier.fillMaxWidth().suppressMediaKeys(),
                        label = { Text("Shared Secret") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth().suppressMediaKeys(),
                        label = { Text("Username") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth().suppressMediaKeys(),
                        label = { Text("Password") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    if (loginStatus != null) {
                        Text(
                            loginStatus!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (loginStatus!!.startsWith("Error"))
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    Button(
                        onClick = {
                            if (apiKey.isBlank() || secret.isBlank() || username.isBlank() || password.isBlank()) {
                                loginStatus = "Error: All fields are required"
                                return@Button
                            }
                            isLoggingIn = true
                            loginStatus = null
                            scope.launch {
                                LastFmManager.login(apiKey, secret, username, password)
                                    .onSuccess { sessionKey ->
                                        PreferencesManager.setLastFmCredentials(apiKey, secret, sessionKey, username)
                                        loginStatus = "Connected successfully!"
                                        password = ""
                                    }
                                    .onFailure { e ->
                                        loginStatus = "Error: ${e.message}"
                                    }
                                isLoggingIn = false
                            }
                        },
                        enabled = !isLoggingIn
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Sign in to Last.fm")
                    }

                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        try {
                            Desktop.getDesktop().browse(URI("https://www.last.fm/api/account/create"))
                        } catch (_: Exception) {}
                    }) {
                        Text("Get API key from Last.fm")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // System section
        item {
            SettingsSectionHeader("System")
        }

        item {
            SettingsItem(
                icon = Icons.Default.DesktopWindows,
                title = "Minimize to Tray on Close",
                subtitle = "Keep running in the system tray when the window is closed",
                trailing = {
                    Switch(
                        checked = preferences.minimizeToTray,
                        onCheckedChange = { PreferencesManager.setMinimizeToTray(it) }
                    )
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Notifications,
                title = "Song Change Notifications",
                subtitle = "Show a notification when the song changes",
                trailing = {
                    Switch(
                        checked = preferences.notificationsEnabled,
                        onCheckedChange = { PreferencesManager.setNotificationsEnabled(it) }
                    )
                }
            )
        }

        // Cache section
        item {
            SettingsSectionHeader("Storage")
        }

        item {
            val downloadDir = PreferencesManager.getDownloadDirectory().absolutePath
            SettingsItem(
                icon = Icons.Default.Folder,
                title = "Downloads Location",
                subtitle = downloadDir,
                onClick = {
                    val chooser = JFileChooser(downloadDir).apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Choose Downloads Folder"
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        PreferencesManager.setDownloadPath(chooser.selectedFile.absolutePath)
                    }
                },
                trailing = {
                    IconButton(onClick = {
                        try {
                            val dir = PreferencesManager.getDownloadDirectory()
                            if (dir.exists()) Desktop.getDesktop().open(dir)
                        } catch (_: Exception) {}
                    }) {
                        Icon(Icons.Default.FolderOpen, "Open folder")
                    }
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Delete,
                title = "Clear Cache",
                subtitle = "Currently using ${formatBytes(cacheSize)}",
                onClick = { showClearCacheDialog = true }
            )
        }

        // Backup & Restore section
        item {
            SettingsSectionHeader("Backup & Restore")
        }

        item {
            var backupStatus by remember { mutableStateOf<String?>(null) }
            SettingsItem(
                icon = Icons.Default.Save,
                title = "Back Up Data",
                subtitle = backupStatus ?: "Export settings, library database and login to a ZIP",
                onClick = {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "Save Backup"
                        selectedFile = java.io.File(
                            com.metrolist.music.desktop.backup.BackupManager.defaultBackupFileName()
                        )
                    }
                    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val target = chooser.selectedFile
                        com.metrolist.music.desktop.backup.BackupManager.exportBackup(target)
                            .onSuccess { count ->
                                backupStatus = "Backup saved (${count} files): ${target.name}"
                            }
                            .onFailure { e ->
                                backupStatus = "Backup failed: ${e.message}"
                            }
                    }
                }
            )
        }

        item {
            var restoreStatus by remember { mutableStateOf<String?>(null) }
            var showRestartNotice by remember { mutableStateOf(false) }
            SettingsItem(
                icon = Icons.Default.SettingsBackupRestore,
                title = "Restore from Backup",
                subtitle = restoreStatus ?: "Import a previously exported backup ZIP",
                onClick = {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "Choose Backup ZIP"
                        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("ZIP files", "zip")
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        com.metrolist.music.desktop.backup.BackupManager.importBackup(chooser.selectedFile)
                            .onSuccess { count ->
                                restoreStatus = "Restored $count files — restart Metrolist to apply"
                                showRestartNotice = true
                            }
                            .onFailure { e ->
                                restoreStatus = "Restore failed: ${e.message}"
                            }
                    }
                }
            )
            if (showRestartNotice) {
                Text(
                    "Restart the app for restored data to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 56.dp, top = 4.dp)
                )
            }
        }

        // Updates section
        item {
            SettingsSectionHeader("Updates")
        }

        item {
            val updateState by AutoUpdater.updateState.collectAsState()

            when (val state = updateState) {
                is AutoUpdater.UpdateState.Idle -> {
                    SettingsItem(
                        icon = Icons.Default.Update,
                        title = "Check for Updates",
                        subtitle = "Current version: ${AutoUpdater.CURRENT_VERSION}",
                        onClick = { AutoUpdater.checkForUpdates() }
                    )
                }
                is AutoUpdater.UpdateState.Checking -> {
                    SettingsItem(
                        icon = Icons.Default.Update,
                        title = "Checking for updates...",
                        subtitle = "Contacting GitHub",
                        trailing = {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    )
                }
                is AutoUpdater.UpdateState.UpToDate -> {
                    SettingsItem(
                        icon = Icons.Default.CheckCircle,
                        title = "Up to date",
                        subtitle = "Version ${AutoUpdater.CURRENT_VERSION} is the latest",
                        onClick = { AutoUpdater.dismiss() }
                    )
                }
                is AutoUpdater.UpdateState.UpdateAvailable -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.NewReleases, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Update available: v${state.version}",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Download size: ${formatBytes(state.downloadSize)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.releaseNotes != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    state.releaseNotes.take(200) + if (state.releaseNotes.length > 200) "..." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row {
                                Button(onClick = { AutoUpdater.downloadAndInstall() }) {
                                    Text("Download & Install")
                                }
                                Spacer(Modifier.width(8.dp))
                                if (state.releaseUrl != null) {
                                    TextButton(onClick = {
                                        try { Desktop.getDesktop().browse(URI(state.releaseUrl)) } catch (_: Exception) {}
                                    }) {
                                        Text("View Release")
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { AutoUpdater.dismiss() }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
                is AutoUpdater.UpdateState.Downloading -> {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SettingsItem(
                            icon = Icons.Default.Download,
                            title = "Downloading v${state.version}...",
                            subtitle = "${(state.progress * 100).toInt()}%"
                        )
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                is AutoUpdater.UpdateState.ReadyToInstall -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "v${state.version} ready to install",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "The app will restart to apply the update",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(onClick = { AutoUpdater.applyUpdate() }) {
                                Text("Restart & Update")
                            }
                        }
                    }
                }
                is AutoUpdater.UpdateState.Error -> {
                    SettingsItem(
                        icon = Icons.Default.ErrorOutline,
                        title = "Update error",
                        subtitle = state.message,
                        onClick = { AutoUpdater.dismiss() }
                    )
                }
            }
        }

        // About section
        item {
            SettingsSectionHeader("About")
        }

        item {
            // Easter egg: triple-click Version → "Hi Valerie!"
            var clickCount by remember { mutableIntStateOf(0) }
            var showEasterEgg by remember { mutableStateOf(false) }

            if (showEasterEgg) {
                AlertDialog(
                    onDismissRequest = { showEasterEgg = false },
                    title = { Text("💌", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    text = {
                        Text(
                            "Hi Valerie! 💜💚",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {}
                )
            }

            SettingsItem(
                icon = Icons.Default.Info,
                title = "Version",
                subtitle = "${AutoUpdater.CURRENT_VERSION} (Desktop)",
                onClick = {
                    clickCount++
                    if (clickCount >= 3) {
                        clickCount = 0
                        showEasterEgg = true
                    }
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Code,
                title = "Source Code",
                subtitle = "View on GitHub",
                onClick = {
                    try {
                        Desktop.getDesktop().browse(URI("https://github.com/Doctordefector/Metrolist-Desktop"))
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.BugReport,
                title = "Report Issue",
                subtitle = "Help improve the app",
                onClick = {
                    try {
                        Desktop.getDesktop().browse(URI("https://github.com/Doctordefector/Metrolist-Desktop/issues/new"))
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            )
        }

        item {
            Text(
                text = "Made by Andrei with love ❤",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
            )
        }

        // Spacer at bottom
        item {
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null)
        },
        trailingContent = trailing,
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    )
    content?.invoke()
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

@Composable
private fun ProxyConfigCard(
    type: com.metrolist.music.desktop.settings.ProxyType,
    host: String,
    port: Int,
    username: String,
    password: String
) {
    var editType by remember(type) { mutableStateOf(type) }
    var editHost by remember(host) { mutableStateOf(host) }
    var editPort by remember(port) { mutableStateOf(port.toString()) }
    var editUser by remember(username) { mutableStateOf(username) }
    var editPass by remember(password) { mutableStateOf(password) }

    Card(modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.metrolist.music.desktop.settings.ProxyType.entries.forEach { proxyType ->
                    FilterChip(
                        selected = editType == proxyType,
                        onClick = { editType = proxyType },
                        label = { Text(proxyType.name) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = editHost,
                    onValueChange = { editHost = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f).suppressMediaKeys()
                )
                OutlinedTextField(
                    value = editPort,
                    onValueChange = { editPort = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).suppressMediaKeys()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = editUser,
                    onValueChange = { editUser = it },
                    label = { Text("Username (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).suppressMediaKeys()
                )
                OutlinedTextField(
                    value = editPass,
                    onValueChange = { editPass = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f).suppressMediaKeys()
                )
            }
            Button(
                onClick = {
                    PreferencesManager.setProxyConfig(
                        type = editType,
                        host = editHost.trim(),
                        port = editPort.toIntOrNull() ?: 8080,
                        username = editUser.trim(),
                        password = editPass
                    )
                    com.metrolist.music.desktop.applyNetworkPreferences()
                },
                enabled = editHost.isNotBlank()
            ) {
                Text("Apply Proxy")
            }
        }
    }
}

/** Curated content country list (ISO 3166-1 alpha-2) */
private val contentCountries = listOf(
    "system" to "System default",
    "US" to "United States",
    "GB" to "United Kingdom",
    "CA" to "Canada",
    "AU" to "Australia",
    "DE" to "Germany",
    "FR" to "France",
    "ES" to "Spain",
    "IT" to "Italy",
    "NL" to "Netherlands",
    "PL" to "Poland",
    "PT" to "Portugal",
    "BR" to "Brazil",
    "MX" to "Mexico",
    "AR" to "Argentina",
    "JP" to "Japan",
    "KR" to "South Korea",
    "IN" to "India",
    "ID" to "Indonesia",
    "TR" to "Turkey",
    "RU" to "Russia",
    "UA" to "Ukraine",
    "SE" to "Sweden",
    "NO" to "Norway",
    "FI" to "Finland",
    "DK" to "Denmark",
    "CZ" to "Czechia",
    "SA" to "Saudi Arabia",
    "AE" to "United Arab Emirates",
    "EG" to "Egypt",
    "ZA" to "South Africa"
)

/** Curated content language list (ISO 639-1) */
private val contentLanguages = listOf(
    "system" to "System default",
    "en" to "English",
    "de" to "Deutsch",
    "fr" to "Français",
    "es" to "Español",
    "it" to "Italiano",
    "pt" to "Português",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "tr" to "Türkçe",
    "ru" to "Русский",
    "uk" to "Українська",
    "ar" to "العربية",
    "ja" to "日本語",
    "ko" to "한국어",
    "zh" to "中文",
    "hi" to "हिन्दी",
    "id" to "Bahasa Indonesia",
    "sv" to "Svenska",
    "cs" to "Čeština"
)
