package com.tesuta.tesuplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tesuta.tesuplayer.TesuApp
import com.tesuta.tesuplayer.ui.MainActivity

/** Background playback; media3 builds the notification with play/pause/next/prev buttons. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: android.media.audiofx.Equalizer? = null

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("TesuPlayer/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        setupEqualizer(player)
        (application as TesuApp).audioSession.value = player.audioSessionId

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // MediaController strips the uri; it travels in requestMetadata
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: return@map item
                        item.buildUpon()
                            .setUri(uri)
                            .setMimeType(item.requestMetadata.extras?.getString(PlayerController.EXTRA_MIME))
                            .build()
                    }.toMutableList(),
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** System equalizer on the player's audio session, driven by the settings screen */
    private fun setupEqualizer(player: ExoPlayer) {
        val prefs = (application as TesuApp).prefs
        val eq = runCatching { android.media.audiofx.Equalizer(0, player.audioSessionId) }.getOrNull() ?: return
        equalizer = eq
        val range = eq.bandLevelRange
        prefs.equalizerInfo = "${eq.numberOfBands};${range[0]};${range[1]};" +
            (0 until eq.numberOfBands).joinToString(",") { eq.getCenterFreq(it.toShort()).div(1000).toString() }
        val applySettings = {
            runCatching {
                val bands = prefs.equalizerBands
                for (i in 0 until eq.numberOfBands) {
                    eq.setBandLevel(i.toShort(), (bands.getOrNull(i) ?: 0).toShort())
                }
                eq.enabled = prefs.equalizerEnabled
            }
        }
        applySettings()
        prefs.listen { key -> if (key == "eq" || key == "eq_bands") applySettings() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        equalizer?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
