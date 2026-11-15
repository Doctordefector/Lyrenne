package com.lyrenne.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.movein.*
import com.lyrenne.desktop.settings.ThemeMode
import com.lyrenne.desktop.ui.movein.MoveInHost
import com.lyrenne.desktop.ui.movein.MoveInOnboardingStep
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Dev tool like UiSnapshot: renders the Move in screens with prepared state to
 * build/snapshots/movein-*.png, in both themes, wide and narrow. Skipped unless -Psnapshots.
 *
 *   ./gradlew :desktop:test --tests '*MoveInSnapshot*' -Psnapshots
 */
class MoveInSnapshot {

    private fun shot(name: String, w: Int, h: Int, theme: ThemeMode, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f)) {
            LyrenneTheme(themeMode = theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        val start = System.nanoTime()
        repeat(30) { scene.render(System.nanoTime() - start); Thread.sleep(30) }
        val data = scene.render(System.nanoTime() - start).encodeToData(EncodedImageFormat.PNG)!!
        File("build/snapshots").mkdirs()
        File("build/snapshots/$name.png").writeBytes(data.bytes)
        scene.close()
        println("snapshot: $name")
    }

    private fun bigLibrary(): MetroListLibrary {
        val start = LocalDateTime.parse("2024-03-01T10:00")
        val songs = (0 until 1300).associate { "v$it" to MlSong("v$it", "Song $it") }
        return MetroListLibrary(
            flavor = BackupFlavor.METROLIST_13, databaseVersion = 35, songs = songs,
            likes = (0 until 1240).map { "v$it" to start },
            librarySongs = (0 until 300).map { "v$it" to start },
            artists = (0 until 42).map { MlArtist("UC$it", "Artist $it", null, null, start) },
            albums = (0 until 27).map { MlAlbum("MP$it", null, "Album $it", null, null, 10, 2000, false, start) },
            albumRows = emptyMap(),
            playlists = (0 until 18).map { MlPlaylist("LP$it", "List $it", if (it < 3) "PL$it" else null, start, true, null, emptyList()) },
            plays = (0 until 9300).map { MlPlay("v${it % 1300}", 1_709_287_200_000L + it * 3_600_000L, 200_000) },
            lyrics = listOf(MlLyrics("v1", "Edited", "x")), lyricsOffsets = emptyMap()
        )
    }

    private fun reviewState(phase: PlaylistPhase): PlaylistImportState {
        val tracks = listOf(
            SourceTrack("Bohemian Rhapsody - Remastered 2011", listOf("Queen"), null, 354_000),
            SourceTrack("SICKO MODE (feat. Drake)", listOf("Travis Scott"), null, 312_000),
            SourceTrack("夜に駆ける", listOf("YOASOBI"), null, 261_000),
            SourceTrack("Hotel California - Live On MTV, 1994", listOf("Eagles"), null, 427_000),
            SourceTrack("Some Bedroom Demo", listOf("Nobody Known"), null, 120_000),
            SourceTrack("Midnight City", listOf("M83"), null, 243_000),
            SourceTrack("One More Time", listOf("Daft Punk"), null, 320_000),
        )
        fun c(id: String, t: String, a: String, s: Int, song: Boolean = true) = Candidate(id, t, listOf(a), null, s, null, song)
        fun m(i: Int, q: MatchQuality, vararg opts: Candidate): TrackMatch {
            val ranked = opts.map { ScoredCandidate(it, TrackMatcher.score(tracks[i], it)) }
            return TrackMatch(tracks[i], ranked, if (q == MatchQuality.NONE) null else opts.firstOrNull(), q)
        }
        val matches = listOf(
            m(0, MatchQuality.GOOD, c("a", "Bohemian Rhapsody", "Queen", 355)),
            m(1, MatchQuality.GOOD, c("b", "SICKO MODE", "Travis Scott", 313)),
            m(2, MatchQuality.GOOD, c("c", "夜に駆ける", "YOASOBI", 261)),
            m(3, MatchQuality.UNSURE, c("d", "Hotel California", "Eagles", 391), c("d2", "Hotel California (Live)", "Eagles", 430)),
            m(4, MatchQuality.NONE, c("e", "Bedroom", "Someone Else", 200)),
            if (phase == PlaylistPhase.MATCHING) null else m(5, MatchQuality.GOOD, c("f", "M83 - Midnight City (Official Video)", "M83", 244, false)),
            if (phase == PlaylistPhase.MATCHING) null else m(6, MatchQuality.GOOD, c("g", "One More Time", "Daft Punk", 320)),
        )
        return PlaylistImportState(
            phase = phase, source = SourcePlaylist(SourceService.SPOTIFY, "All Out 80s", tracks,
                limitNote = "Spotify only shows the first 100 songs of a playlist without a login. To bring over the rest, export the playlist as text or CSV (Exportify works for Spotify) and paste it instead."),
            matches = matches, name = "All Out 80s"
        )
    }

    @Composable
    private fun Backdrop() {
        Box(Modifier.fillMaxSize().padding(40.dp)) { Text("Library", style = MaterialTheme.typography.headlineLarge) }
    }

    @Test
    fun render() {
        assumeTrue(System.getProperty("snapshots") != null)
        val lib = bigLibrary()
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val t = theme.name.lowercase()
            for ((w, h, size) in listOf(Triple(1200, 800, "wide"), Triple(720, 640, "narrow"))) {
                MoveInController.showForPreview(MoveInDialog.NONE, backup = BackupState.Idle)
                shot("movein-onboarding-$t-$size", w, h, theme) {
                    Column(Modifier.fillMaxSize().padding(32.dp)) { MoveInOnboardingStep() }
                }
                MoveInController.showForPreview(MoveInDialog.BACKUP, backup = BackupState.Preview("Metrolist_20261114.backup", lib, MoveInOptions()))
                shot("movein-preview-$t-$size", w, h, theme) { Backdrop(); MoveInHost() }
                MoveInController.showForPreview(MoveInDialog.BACKUP, backup = BackupState.Done(
                    MoveInResult(newLikes = 1203, alreadyLiked = 37, newLibrarySongs = 260, newPlaylists = 18, newAlbums = 27, newArtists = 40, newPlays = 9300, lyrics = 1),
                    pushedLikes = 412, pushTotal = 1203))
                shot("movein-done-$t-$size", w, h, theme) { Backdrop(); MoveInHost() }
                MoveInController.showForPreview(MoveInDialog.PLAYLIST, playlist = PlaylistImportState(input = "https://open.spotify.com/playlist/37i9dQZF1DX4UtSsGT1Sbe"))
                shot("movein-link-$t-$size", w, h, theme) { Backdrop(); MoveInHost() }
                MoveInController.showForPreview(MoveInDialog.PLAYLIST, playlist = reviewState(PlaylistPhase.MATCHING))
                shot("movein-matching-$t-$size", w, h, theme) { Backdrop(); MoveInHost() }
                MoveInController.showForPreview(MoveInDialog.PLAYLIST, playlist = reviewState(PlaylistPhase.REVIEW))
                shot("movein-review-$t-$size", w, h, theme) { Backdrop(); MoveInHost() }
            }
        }
    }
}
