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

enum class ProxyType {
    HTTP, SOCKS
}

enum class LibraryViewMode {
    LIST, GRID
}

enum class LyricsPosition {
    LEFT, CENTER, RIGHT
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
    val notificationsEnabled: Boolean = true,
    val minimizeToTray: Boolean = true,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val volumeBeforeMute: Float = 1f,
    // Equalizer
    val eqEnabled: Boolean = false,
    val eqPreset: String? = null, // null = custom
    val eqPreamp: Float = 12f,
    val eqBands: List<Float> = List(10) { 0f }, // 10-band gains in dB (-20 to +20)
    // Playback extras
    val playbackSpeed: Float = 1.0f,           // 0.25x - 3.0x
    val crossfadeSec: Int = 0,                 // 0 = off, 1-12 seconds
    val autoLoadRadio: Boolean = false,        // auto-queue related tracks near queue end
    // Privacy
    val pauseListenHistory: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    // Content
    val contentCountry: String = "system",     // ISO 3166-1 alpha-2 or "system"
    val contentLanguage: String = "system",    // ISO 639-1 or "system"
    val hideExplicit: Boolean = false,
    val quickPicks: Boolean = true,            // show Quick picks section on home
    val homeRandomize: Boolean = false,        // shuffle home section order
    // Network proxy
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    // Library
    val libraryViewMode: LibraryViewMode = LibraryViewMode.LIST,
    val autoDownloadOnLike: Boolean = false,
    // Lyrics display
    val lyricsTextSize: Float = 16f,           // sp, 12-32
    val lyricsPosition: LyricsPosition = LyricsPosition.LEFT,
    val lyricsClickToSeek: Boolean = true,
)

object PreferencesManager {
    private val _preferences = MutableStateFlow(AppPreferences())
    val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

    private val prefsFile: File get() = com.metrolist.music.desktop.AppPaths.preferencesFile

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
                    notificationsEnabled = props.getProperty("notificationsEnabled")?.toBoolean() ?: true,
                    minimizeToTray = props.getProperty("minimizeToTray")?.toBoolean() ?: true,
                    volume = props.getProperty("volume")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                    isMuted = props.getProperty("isMuted")?.toBoolean() ?: false,
                    volumeBeforeMute = props.getProperty("volumeBeforeMute")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                    eqEnabled = props.getProperty("eqEnabled")?.toBoolean() ?: false,
                    eqPreset = props.getProperty("eqPreset"),
                    eqPreamp = props.getProperty("eqPreamp")?.toFloatOrNull()?.coerceIn(-20f, 20f) ?: 12f,
                    eqBands = props.getProperty("eqBands")?.split(",")
                        ?.mapNotNull { it.trim().toFloatOrNull()?.coerceIn(-20f, 20f) }
                        ?.takeIf { it.size == 10 } ?: List(10) { 0f },
                    playbackSpeed = props.getProperty("playbackSpeed")?.toFloatOrNull()?.coerceIn(0.25f, 3f) ?: 1f,
                    crossfadeSec = props.getProperty("crossfadeSec")?.toIntOrNull()?.coerceIn(0, 12) ?: 0,
                    autoLoadRadio = props.getProperty("autoLoadRadio")?.toBoolean() ?: false,
                    pauseListenHistory = props.getProperty("pauseListenHistory")?.toBoolean() ?: false,
                    pauseSearchHistory = props.getProperty("pauseSearchHistory")?.toBoolean() ?: false,
                    contentCountry = props.getProperty("contentCountry") ?: "system",
                    contentLanguage = props.getProperty("contentLanguage") ?: "system",
                    hideExplicit = props.getProperty("hideExplicit")?.toBoolean() ?: false,
                    quickPicks = props.getProperty("quickPicks")?.toBoolean() ?: true,
                    homeRandomize = props.getProperty("homeRandomize")?.toBoolean() ?: false,
                    proxyEnabled = props.getProperty("proxyEnabled")?.toBoolean() ?: false,
                    proxyType = props.getProperty("proxyType")?.let {
                        try { ProxyType.valueOf(it) } catch (_: Exception) { null }
                    } ?: ProxyType.HTTP,
                    proxyHost = props.getProperty("proxyHost") ?: "",
                    proxyPort = props.getProperty("proxyPort")?.toIntOrNull()?.coerceIn(1, 65535) ?: 8080,
                    proxyUsername = props.getProperty("proxyUsername") ?: "",
                    proxyPassword = props.getProperty("proxyPassword") ?: "",
                    libraryViewMode = props.getProperty("libraryViewMode")?.let {
                        try { LibraryViewMode.valueOf(it) } catch (_: Exception) { null }
                    } ?: LibraryViewMode.LIST,
                    autoDownloadOnLike = props.getProperty("autoDownloadOnLike")?.toBoolean() ?: false,
                    lyricsTextSize = props.getProperty("lyricsTextSize")?.toFloatOrNull()?.coerceIn(12f, 32f) ?: 16f,
                    lyricsPosition = props.getProperty("lyricsPosition")?.let {
                        try { LyricsPosition.valueOf(it) } catch (_: Exception) { null }
                    } ?: LyricsPosition.LEFT,
                    lyricsClickToSeek = props.getProperty("lyricsClickToSeek")?.toBoolean() ?: true,
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
            props.setProperty("minimizeToTray", prefs.minimizeToTray.toString())
            props.setProperty("volume", prefs.volume.toString())
            props.setProperty("isMuted", prefs.isMuted.toString())
            props.setProperty("volumeBeforeMute", prefs.volumeBeforeMute.toString())
            props.setProperty("eqEnabled", prefs.eqEnabled.toString())
            prefs.eqPreset?.let { props.setProperty("eqPreset", it) }
            props.setProperty("eqPreamp", prefs.eqPreamp.toString())
            props.setProperty("eqBands", prefs.eqBands.joinToString(","))
            props.setProperty("playbackSpeed", prefs.playbackSpeed.toString())
            props.setProperty("crossfadeSec", prefs.crossfadeSec.toString())
            props.setProperty("autoLoadRadio", prefs.autoLoadRadio.toString())
            props.setProperty("pauseListenHistory", prefs.pauseListenHistory.toString())
            props.setProperty("pauseSearchHistory", prefs.pauseSearchHistory.toString())
            props.setProperty("contentCountry", prefs.contentCountry)
            props.setProperty("contentLanguage", prefs.contentLanguage)
            props.setProperty("hideExplicit", prefs.hideExplicit.toString())
            props.setProperty("quickPicks", prefs.quickPicks.toString())
            props.setProperty("homeRandomize", prefs.homeRandomize.toString())
            props.setProperty("proxyEnabled", prefs.proxyEnabled.toString())
            props.setProperty("proxyType", prefs.proxyType.name)
            props.setProperty("proxyHost", prefs.proxyHost)
            props.setProperty("proxyPort", prefs.proxyPort.toString())
            props.setProperty("proxyUsername", prefs.proxyUsername)
            props.setProperty("proxyPassword", prefs.proxyPassword)
            props.setProperty("libraryViewMode", prefs.libraryViewMode.name)
            props.setProperty("autoDownloadOnLike", prefs.autoDownloadOnLike.toString())
            props.setProperty("lyricsTextSize", prefs.lyricsTextSize.toString())
            props.setProperty("lyricsPosition", prefs.lyricsPosition.name)
            props.setProperty("lyricsClickToSeek", prefs.lyricsClickToSeek.toString())

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

    fun setMinimizeToTray(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(minimizeToTray = enabled)
        savePreferences()
    }

    fun setVolume(volume: Float) {
        _preferences.value = _preferences.value.copy(volume = volume.coerceIn(0f, 1f))
        savePreferences()
    }

    fun setMuted(muted: Boolean) {
        _preferences.value = _preferences.value.copy(isMuted = muted)
        savePreferences()
    }

    fun setVolumeBeforeMute(volume: Float) {
        _preferences.value = _preferences.value.copy(volumeBeforeMute = volume.coerceIn(0f, 1f))
        savePreferences()
    }

    fun setEqEnabled(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(eqEnabled = enabled)
        savePreferences()
    }

    fun setEqPreset(preset: String?) {
        _preferences.value = _preferences.value.copy(eqPreset = preset)
        savePreferences()
    }

    fun setEqPreamp(preamp: Float) {
        _preferences.value = _preferences.value.copy(eqPreamp = preamp.coerceIn(-20f, 20f))
        savePreferences()
    }

    fun setEqBands(bands: List<Float>) {
        _preferences.value = _preferences.value.copy(
            eqBands = bands.map { it.coerceIn(-20f, 20f) },
            eqPreset = null // custom when bands changed manually
        )
        savePreferences()
    }

    fun setEqBand(index: Int, gain: Float) {
        val bands = _preferences.value.eqBands.toMutableList()
        if (index in bands.indices) {
            bands[index] = gain.coerceIn(-20f, 20f)
            _preferences.value = _preferences.value.copy(
                eqBands = bands,
                eqPreset = null
            )
            savePreferences()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _preferences.value = _preferences.value.copy(playbackSpeed = speed.coerceIn(0.25f, 3f))
        savePreferences()
    }

    fun setCrossfadeSec(sec: Int) {
        _preferences.value = _preferences.value.copy(crossfadeSec = sec.coerceIn(0, 12))
        savePreferences()
    }

    fun setAutoLoadRadio(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(autoLoadRadio = enabled)
        savePreferences()
    }

    fun setPauseListenHistory(paused: Boolean) {
        _preferences.value = _preferences.value.copy(pauseListenHistory = paused)
        savePreferences()
    }

    fun setPauseSearchHistory(paused: Boolean) {
        _preferences.value = _preferences.value.copy(pauseSearchHistory = paused)
        savePreferences()
    }

    fun setContentCountry(country: String) {
        _preferences.value = _preferences.value.copy(contentCountry = country)
        savePreferences()
    }

    fun setContentLanguage(language: String) {
        _preferences.value = _preferences.value.copy(contentLanguage = language)
        savePreferences()
    }

    fun setHideExplicit(hide: Boolean) {
        _preferences.value = _preferences.value.copy(hideExplicit = hide)
        savePreferences()
    }

    fun setQuickPicks(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(quickPicks = enabled)
        savePreferences()
    }

    fun setHomeRandomize(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(homeRandomize = enabled)
        savePreferences()
    }

    fun setProxyEnabled(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(proxyEnabled = enabled)
        savePreferences()
    }

    fun setProxyConfig(type: ProxyType, host: String, port: Int, username: String, password: String) {
        _preferences.value = _preferences.value.copy(
            proxyType = type,
            proxyHost = host,
            proxyPort = port.coerceIn(1, 65535),
            proxyUsername = username,
            proxyPassword = password
        )
        savePreferences()
    }

    fun setLibraryViewMode(mode: LibraryViewMode) {
        _preferences.value = _preferences.value.copy(libraryViewMode = mode)
        savePreferences()
    }

    fun setAutoDownloadOnLike(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(autoDownloadOnLike = enabled)
        savePreferences()
    }

    fun setLyricsTextSize(size: Float) {
        _preferences.value = _preferences.value.copy(lyricsTextSize = size.coerceIn(12f, 32f))
        savePreferences()
    }

    fun setLyricsPosition(position: LyricsPosition) {
        _preferences.value = _preferences.value.copy(lyricsPosition = position)
        savePreferences()
    }

    fun setLyricsClickToSeek(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(lyricsClickToSeek = enabled)
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

        // Default: Downloads folder next to the app
        val appDir = getAppDirectory()
        val baseDir = File(appDir, "Downloads")
        baseDir.mkdirs()
        return baseDir
    }

    private fun getAppDirectory(): File = com.metrolist.music.desktop.AppPaths.dataDir.parentFile

    fun getCacheDirectory(): File {
        val cacheDir = com.metrolist.music.desktop.AppPaths.cacheDir
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
