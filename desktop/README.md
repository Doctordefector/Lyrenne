# Metrolist Desktop (Windows)

A desktop port of the Metrolist YouTube Music client using Compose Multiplatform.

## Prerequisites

1. **Java 21+** - Download from [Adoptium](https://adoptium.net/)
2. **VLC Media Player (64-bit)** - Required for audio playback. Download from [videolan.org](https://www.videolan.org/vlc/)

## Building

From the project root directory:

```bash
# Run the desktop app
./gradlew :desktop:run

# Build a distributable (MSI/EXE for Windows)
./gradlew :desktop:packageMsi
./gradlew :desktop:packageExe

# Create a distribution folder
./gradlew :desktop:createDistributable
```

## Project Structure

```
desktop/
├── src/main/kotlin/com/metrolist/music/desktop/
│   ├── Main.kt                    # Application entry point
│   ├── playback/
│   │   └── DesktopPlayer.kt       # VLC-based audio player
│   └── ui/
│       ├── App.kt                 # Main app with navigation
│       ├── theme/
│       │   └── Theme.kt           # Material 3 theming
│       ├── components/
│       │   └── MiniPlayer.kt      # Bottom player bar
│       └── screens/
│           ├── HomeScreen.kt      # Home page with recommendations
│           ├── SearchScreen.kt    # Search functionality
│           ├── LibraryScreen.kt   # User library
│           └── SettingsScreen.kt  # App settings
└── build.gradle.kts               # Desktop module build config
```

## Features (Core Playback Focus)

- [x] Browse YouTube Music home page
- [x] Search for songs, artists, albums, playlists
- [x] Audio playback via VLC
- [x] Play/Pause/Skip controls
- [x] Volume control
- [x] Progress bar with seeking
- [x] Queue management
- [ ] Offline caching
- [ ] Local library persistence
- [ ] Lyrics display
- [ ] Equalizer

## Architecture

The desktop app reuses the following modules from the Android app:
- `:innertube` - YouTube Music API client
- `:kugou` - Lyrics provider
- `:lrclib` - Lyrics database
- `:betterlyrics` - Time-synced lyrics
- `:simpmusic` - Additional lyrics API

Audio playback uses [VLCJ](https://github.com/caprica/vlcj) instead of ExoPlayer.

## Troubleshooting

### "VLC not found"
Make sure you have VLC 64-bit installed. The app uses native discovery to find VLC.

### "Could not load audio stream"
Some videos may be region-restricted or require authentication. Try a different song.

### Build fails
Ensure you have Java 21+ installed and JAVA_HOME is set correctly.
