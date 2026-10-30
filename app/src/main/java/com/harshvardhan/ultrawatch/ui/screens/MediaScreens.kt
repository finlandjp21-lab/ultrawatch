package com.harshvardhan.ultrawatch.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.google.common.util.concurrent.MoreExecutors
import com.harshvardhan.ultrawatch.core.Command
import com.harshvardhan.ultrawatch.core.MediaItemInfo
import com.harshvardhan.ultrawatch.core.MediaQueue
import com.harshvardhan.ultrawatch.media.MediaLibrary
import com.harshvardhan.ultrawatch.media.PlaybackService
import com.harshvardhan.ultrawatch.ui.*
import kotlinx.coroutines.delay

/** Voice commands waiting for the music screen to open. */
object MusicCommands { @Volatile var pending: Command? = null }

@Composable
fun MusicScreen() {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var songs by remember { mutableStateOf<List<MediaItemInfo>>(emptyList()) }
    var title by remember { mutableStateOf("Nothing playing") }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
    var search by remember { mutableStateOf<String?>(null) }

    val load = rememberPermissionGate(arrayOf(MediaLibrary.audioPermission()), then = { songs = MediaLibrary.songs(ctx) })
    val voiceSearch = rememberVoiceInput("Song or artist") { search = it }
    val urlInput = rememberTextInput("Stream URL (https://...)") { url ->
        controller?.run { setMediaItem(MediaItem.Builder().setUri(url).build()); prepare(); play() }
    }

    fun playList(list: List<MediaItemInfo>, start: Int = 0) {
        val c = controller ?: return
        c.setMediaItems(list.map(PlaybackService::toMediaItem), start, 0L)
        c.prepare(); c.play()
    }

    DisposableEffect(Unit) {
        load()
        val f = PlaybackService.controller(ctx)
        f.addListener({ controller = runCatching { f.get() }.getOrNull() }, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(f) }
    }

    // Pending voice command (play/pause/next) once the controller is ready
    LaunchedEffect(controller, songs) {
        val c = controller ?: return@LaunchedEffect
        when (val cmd = MusicCommands.pending) {
            is Command.PlayMusic -> {
                val list = MediaQueue.search(songs, cmd.query)
                if (list.isNotEmpty()) playList(list) else c.play()
                MusicCommands.pending = null
            }
            Command.PauseMusic -> { c.pause(); MusicCommands.pending = null }
            Command.NextTrack -> { c.seekToNext(); MusicCommands.pending = null }
            Command.PreviousTrack -> { c.seekToPrevious(); MusicCommands.pending = null }
            else -> {}
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                title = c.mediaMetadata.title?.toString() ?: if (c.mediaItemCount > 0) "Track" else "Nothing playing"
                playing = c.isPlaying; pos = c.currentPosition; dur = c.duration.coerceAtLeast(0)
                shuffle = c.shuffleModeEnabled; repeat = c.repeatMode
            }
            delay(500)
        }
    }

    val shown = MediaQueue.search(songs, search)
    AppScreen {
        item {
            Box(Modifier.size(130.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(progress = if (dur > 0) pos.toFloat() / dur else 0f, modifier = Modifier.fillMaxSize(), startAngle = 300f, endAngle = 240f, strokeWidth = 4.dp)
                androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier) {
                    Text(title, maxLines = 1)
                    InfoText("${MediaQueue.formatTime(pos)} / ${MediaQueue.formatTime(dur)}")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("⏮") { controller?.seekToPrevious() }
                SmallChip(if (playing) "Pause" else "Play") { controller?.let { if (it.isPlaying) it.pause() else { if (it.mediaItemCount == 0 && songs.isNotEmpty()) playList(songs) else it.play() } } }
                SmallChip("⏭") { controller?.seekToNext() }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip(if (shuffle) "Shuffle on" else "Shuffle") { controller?.shuffleModeEnabled = !shuffle }
                SmallChip(when (repeat) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat" }) {
                    controller?.repeatMode = when (repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                }
            }
        }
        item {
            ActionChip("Bluetooth headphones", "Connect earbuds for watch music") {
                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SmallChip("Search") { voiceSearch() }; SmallChip("Stream URL") { urlInput() } } }
        search?.let { s -> item { ActionChip("Clear \"$s\"") { search = null } } }
        if (shown.isNotEmpty()) item { ActionChip("Play all (${shown.size})", primary = true) { playList(shown) } }
        shown.take(100).forEachIndexed { i, s -> item { ActionChip(s.title, s.artist.ifBlank { MediaQueue.formatTime(s.durationMs) }) { playList(shown, i) } } }
        if (songs.isEmpty()) item { InfoText("No music on the watch yet. Copy songs over USB/ADB or stream from a URL.") }
    }
}

@Composable
fun VideoScreen() {
    val ctx = LocalContext.current
    var videos by remember { mutableStateOf<List<MediaItemInfo>>(emptyList()) }
    var playing by remember { mutableStateOf<String?>(null) }
    val load = rememberPermissionGate(arrayOf(MediaLibrary.videoPermission()), then = { videos = MediaLibrary.videos(ctx) })
    val urlInput = rememberTextInput("Video URL (https://...)") { playing = it }
    LaunchedEffect(Unit) { load() }

    val uri = playing
    if (uri != null) { VideoPlayer(uri) { playing = null }; return }

    AppScreen("Video") {
        item { ActionChip("Play from URL", "MP4, HLS (.m3u8) or DASH", primary = true) { urlInput() } }
        videos.forEach { v -> item { ActionChip(v.title, MediaQueue.formatTime(v.durationMs)) { playing = v.uri } } }
        if (videos.isEmpty()) item { InfoText("No videos on the watch. Small screens suit short clips; audio plays through earbuds or the speaker.") }
    }
}

@Composable
private fun VideoPlayer(uri: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true; setShowNextButton(false); setShowPreviousButton(false) } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
