package com.metrolist.music.desktop.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.util.Properties

enum class AudioQuality(val displayName: String, val bitrate: Int) {
    LOW("Low (128 kbps)", 128),
    MEDIUM("Medium (192 kbps)", 192),
    HIGH("High (256 kbps)", 256),
    BEST("Best (320 kbps)", 320)
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val skipSilence: Boolean = false,
    val normalizeAudio: Boolean = true,
    val persistQueue: Boolean = true,
    val showLyrics: Boolean = true,
    val cacheSize: Long = 500L * 1024 * 1024, // 500 MB default
    val downloadPath: String? = null,
    val discordToken: String? = null,
    val discordRpcEnabled: Boolean = false,
    val lastFmEnabled: Boolean = false,
    val lastFmApiKey: String? = null,
    val lastFmSecret: String? = null,
    val lastFmSessionKey: String? = null,
    val lastFmUsername: String? = null,
    val notificationsEnabled: Boolean = true
)

object PreferencesManager {
    private val _preferences = MutableStateFlow(AppPreferences())
    val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

    private val prefsFile: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val baseDir = when {
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
        baseDir.mkdirs()
        File(baseDir, "preferences.properties")
    }

    fun initialize() {
        loadPreferences()
    }

    private fun loadPreferences() {
        try {
            if (prefsFile.exists()) {
                val props = Properties()
                prefsFile.inputStream().use { props.load(it) }

                _preferences.value = AppPreferences(
                    themeMode = props.getProperty("themeMode")?.let {
                        try { ThemeMode.valueOf(it) } catch (_: Exception) { null }
                    } ?: ThemeMode.SYSTEM,
                    audioQuality = props.getProperty("audioQuality")?.let {
                        try { AudioQuality.valueOf(it) } catch (_: Exception) { null }
                    } ?: AudioQuality.HIGH,
                    skipSilence = props.getProperty("skipSilence")?.toBoolean() ?: false,
                    normalizeAudio = props.getProperty("normalizeAudio")?.toBoolean() ?: true,
                    persistQueue = props.getProperty("persistQueue")?.toBoolean() ?: true,
                    showLyrics = props.getProperty("showLyrics")?.toBoolean() ?: true,
                    cacheSize = props.getProperty("cacheSize")?.toLongOrNull() ?: (500L * 1024 * 1024),
                    downloadPath = props.getProperty("downloadPath"),
                    discordToken = props.getProperty("discordToken"),
                    discordRpcEnabled = props.getProperty("discordRpcEnabled")?.toBoolean() ?: false,
                    lastFmEnabled = props.getProperty("lastFmEnabled")?.toBoolean() ?: false,
                    lastFmApiKey = props.getProperty("lastFmApiKey"),
                    lastFmSecret = props.getProperty("lastFmSecret"),
                    lastFmSessionKey = props.getProperty("lastFmSessionKey"),
                    lastFmUsername = props.getProperty("lastFmUsername"),
                    notificationsEnabled = props.getProperty("notificationsEnabled")?.toBoolean() ?: true
                )
            }
        } catch (e: Exception) {
            Timber.e("Failed to load preferences: ${e.message}")
        }
    }

    private fun savePreferences() {
        try {
            val props = Properties()
            val prefs = _preferences.value

            props.setProperty("themeMode", prefs.themeMode.name)
            props.setProperty("audioQuality", prefs.audioQuality.name)
            props.setProperty("skipSilence", prefs.skipSilence.toString())
            props.setProperty("normalizeAudio", prefs.normalizeAudio.toString())
            props.setProperty("persistQueue", prefs.persistQueue.toString())
            props.setProperty("showLyrics", prefs.showLyrics.toString())
            props.setProperty("cacheSize", prefs.cacheSize.toString())
            prefs.downloadPath?.let { props.setProperty("downloadPath", it) }
            prefs.discordToken?.let { props.setProperty("discordToken", it) }
            props.setProperty("discordRpcEnabled", prefs.discordRpcEnabled.toString())
            props.setProperty("lastFmEnabled", prefs.lastFmEnabled.toString())
            prefs.lastFmApiKey?.let { props.setProperty("lastFmApiKey", it) }
            prefs.lastFmSecret?.let { props.setProperty("lastFmSecret", it) }
            prefs.lastFmSessionKey?.let { props.setProperty("lastFmSessionKey", it) }
            prefs.lastFmUsername?.let { props.setProperty("lastFmUsername", it) }
            props.setProperty("notificationsEnabled", prefs.notificationsEnabled.toString())

            prefsFile.outputStream().use {
                props.store(it, "Metrolist Desktop Preferences")
            }
        } catch (e: Exception) {
            Timber.e("Failed to save preferences: ${e.message}")
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _preferences.value = _preferences.value.copy(themeMode = mode)
        savePreferences()
    }

    fun setAudioQuality(quality: AudioQuality) {
        _preferences.value = _preferences.value.copy(audioQuality = quality)
        savePreferences()
    }

    fun setSkipSilence(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(skipSilence = enabled)
        savePreferences()
    }

    fun setNormalizeAudio(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(normalizeAudio = enabled)
        savePreferences()
    }

    fun setPersistQueue(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(persistQueue = enabled)
        savePreferences()
    }

    fun setShowLyrics(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(showLyrics = enabled)
        savePreferences()
    }

    fun setCacheSize(sizeBytes: Long) {
        _preferences.value = _preferences.value.copy(cacheSize = sizeBytes)
        savePreferences()
    }

    fun setDownloadPath(path: String?) {
        _preferences.value = _preferences.value.copy(downloadPath = path)
        savePreferences()
    }

    fun setDiscordToken(token: String?) {
        _preferences.value = _preferences.value.copy(discordToken = token)
        savePreferences()
    }

    fun setDiscordRpcEnabled(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(discordRpcEnabled = enabled)
        savePreferences()
    }

    fun setLastFmEnabled(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(lastFmEnabled = enabled)
        savePreferences()
    }

    fun setLastFmCredentials(apiKey: String?, secret: String?, sessionKey: String?, username: String?) {
        _preferences.value = _preferences.value.copy(
            lastFmApiKey = apiKey,
            lastFmSecret = secret,
            lastFmSessionKey = sessionKey,
            lastFmUsername = username
        )
        savePreferences()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(notificationsEnabled = enabled)
        savePreferences()
    }

    fun getDownloadDirectory(): File {
        val customPath = _preferences.value.downloadPath
        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir
            }
        }

        // Default download directory
        val os = System.getProperty("os.name").lowercase()
        val baseDir = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(appData, "Metrolist/Downloads")
            }
            os.contains("mac") -> {
                File(System.getProperty("user.home"), "Library/Application Support/Metrolist/Downloads")
            }
            else -> {
                File(System.getProperty("user.home"), ".local/share/metrolist/downloads")
            }
        }
        baseDir.mkdirs()
        return baseDir
    }

    fun getCacheDirectory(): File {
        val os = System.getProperty("os.name").lowercase()
        val cacheDir = when {
            os.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(localAppData, "Metrolist/Cache")
            }
            os.contains("mac") -> {
                File(System.getProperty("user.home"), "Library/Caches/Metrolist")
            }
            else -> {
                File(System.getProperty("user.home"), ".cache/metrolist")
            }
        }
        cacheDir.mkdirs()
        return cacheDir
    }

    fun clearCache(): Long {
        val cacheDir = getCacheDirectory()
        var clearedBytes = 0L

        cacheDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                clearedBytes += file.length()
                file.delete()
            }
        }

        return clearedBytes
    }

    fun getCacheUsage(): Long {
        val cacheDir = getCacheDirectory()
        return cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}
