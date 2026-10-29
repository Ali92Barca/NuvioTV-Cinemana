package com.nuvio.tv.data.offline

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Only accepts an indexed completed item, never an arbitrary external URI. */
@UnstableApi
@AndroidEntryPoint
class OfflinePlayerActivity : ComponentActivity() {
    @Inject lateinit var downloads: OfflineDownloads
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var position = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        position = savedInstanceState?.getLong("position") ?: 0L
    }
    override fun onStart() {
        super.onStart()
        try {
            val item = downloads.list().single { it.key == intent.getStringExtra("item") }
            check(downloads.ready(item))
            val tracks = item.subtitles.map { (id, lang) ->
                val uri = downloads.uri(id)
                val beginning = contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = ByteArray(256); val size = input.read(bytes)
                    if (size > 0) String(bytes, 0, size, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r') else ""
                }.orEmpty()
                val mime = when {
                    beginning.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
                    beginning.startsWith("[Script Info]") -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                MediaItem.SubtitleConfiguration.Builder(uri).setLanguage(lang).setMimeType(mime).build()
            }
            val media = MediaItem.Builder().setUri(downloads.uri(item.video)).setSubtitleConfigurations(tracks).build()
            val engine = ExoPlayer.Builder(this).build()
            player = engine
            playerView = PlayerView(this).apply { player = engine; useController = true; keepScreenOn = true }
            setContentView(playerView)
            engine.setMediaItem(media)
            engine.seekTo(position)
            engine.prepare()
            engine.playWhenReady = true
            playerView?.requestFocus()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "تعذر تشغيل التنزيل؛ تأكد من اكتمال الفيديو والترجمة", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        playerView?.dispatchKeyEvent(event) == true || super.dispatchKeyEvent(event)
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        position = player?.currentPosition ?: position
        playerView?.player = null
        player?.release(); player = null
        super.onStop()
    }
}
