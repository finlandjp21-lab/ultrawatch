package com.harshvardhan.ultrawatch.media

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.harshvardhan.ultrawatch.core.MediaItemInfo

/**
 * Background music service. Playback keeps going with the screen off and is controllable
 * from the system media controls and Bluetooth headphones. If no headphones are connected,
 * Media3 pauses and opens the Wear OS output switcher instead of blasting the speaker.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        fun controller(ctx: Context): ListenableFuture<MediaController> =
            MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()

        fun toMediaItem(i: MediaItemInfo): MediaItem = MediaItem.Builder()
            .setMediaId(i.id)
            .setUri(i.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(i.title).setArtist(i.artist).build())
            .build()
    }
}

/** Reads music and videos stored on the watch. */
object MediaLibrary {
    fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun videoPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun songs(ctx: Context): List<MediaItemInfo> {
        if (!granted(ctx, audioPermission())) return emptyList()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        val out = ArrayList<MediaItemInfo>()
        ctx.contentResolver.query(uri, proj, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.TITLE + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += MediaItemInfo(id.toString(), c.getString(1) ?: "Unknown", c.getString(2)?.takeIf { it != "<unknown>" } ?: "",
                    ContentUris.withAppendedId(uri, id).toString(), c.getLong(3))
            }
        }
        return out
    }

    fun videos(ctx: Context): List<MediaItemInfo> {
        if (!granted(ctx, videoPermission())) return emptyList()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION)
        val out = ArrayList<MediaItemInfo>()
        ctx.contentResolver.query(uri, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += MediaItemInfo("v$id", c.getString(1) ?: "Video", "", ContentUris.withAppendedId(uri, id).toString(), c.getLong(2), isVideo = true)
            }
        }
        return out
    }
}
