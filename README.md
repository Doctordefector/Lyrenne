# Metrolist Desktop

Desktop port of [Metrolist](https://github.com/mostafaalagamy/Metrolist) (YouTube Music client) using Compose Desktop (JVM).

## Features

- **Authentication** via browser cookie extraction (Opera, Chrome, Edge, Brave, Vivaldi)
- **Personalized home feed** with multi-account support
- **Search** with filters (songs, videos, albums, artists, playlists)
- **Library sync** from your YouTube Music account
- **Playback** via VLC (streaming + local files)
- **Lyrics** (synced + plain) via lrclib
- **Queue management** with persistence across restarts
- **Downloads** with progress tracking
- **Discord Rich Presence** via kizzy
- **Media keys** support (play/pause, next, prev)
- **Material3 theme** with system dark/light detection

## Requirements

- **JDK 21+**
- **VLC** installed on your system
- A Chromium-based browser with an active YouTube Music login

## Building

```bash
./gradlew :desktop:run
```

## Based On

- Upstream: [Metrolist v13.3.0](https://github.com/mostafaalagamy/Metrolist)
- Desktop module: `desktop/`
- Shared InnerTube API client: `innertube/`

## License

GPL-3.0 — see [LICENSE](LICENSE)
