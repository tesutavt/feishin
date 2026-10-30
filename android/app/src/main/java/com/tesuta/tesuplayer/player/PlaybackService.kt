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
import com.tesuta.tesuplayer.data.S
import com.tesuta.tesuplayer.ui.MainActivity

/** Background playback; media3 builds the notification with play/pause/next/prev buttons. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var dynamics: android.media.audiofx.AudioEffect? = null

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
        setupAudio(player)
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

    /** Playback → Audio / Equalizer / Window settings that live on the player itself */
    private fun setupAudio(player: ExoPlayer) {
        val prefs = (application as TesuApp).prefs
        val loudness = runCatching { android.media.audiofx.LoudnessEnhancer(player.audioSessionId) }.getOrNull()
        loudnessEnhancer = loudness
        val apply = {
            runCatching {
                val manager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                val device = S.audioDevice.takeIf { it != 0 }?.let { id ->
                    manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                }
                player.setPreferredAudioDevice(device)
                player.setWakeMode(if (S.preventSuspend) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
                loudness?.setTargetGain(S.eqPreamp.coerceAtLeast(0))
                loudness?.enabled = prefs.equalizerEnabled && S.eqPreamp > 0
            }
            runCatching { applyCompressor(player.audioSessionId) }
        }
        apply()
        prefs.listen { key -> if (key != null && (key.startsWith("s_") || key == "eq")) apply() }
    }

    /** The desktop compressor: DynamicsProcessing limiter-style compressor on every channel (Android 9+) */
    private fun applyCompressor(sessionId: Int) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        if (!S.compressor) { dynamics?.release(); dynamics = null; return }
        val dp = (dynamics as? android.media.audiofx.DynamicsProcessing) ?: run {
            val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                false, 0, true, 1, false, 0, false,
            ).build()
            android.media.audiofx.DynamicsProcessing(0, sessionId, config).also { dynamics = it }
        }
        for (channel in 0 until 2) {
            val band = dp.getMbcBandByChannelIndex(channel, 0)
            band.isEnabled = true
            band.threshold = S.compThreshold.toFloat()
            band.ratio = S.compRatio.toFloat()
            band.attackTime = S.compAttack.toFloat()
            band.releaseTime = S.compRelease.toFloat()
            band.postGain = S.compMakeup.toFloat()
            band.kneeWidth = S.compKnee.toFloat()
            dp.setMbcBandByChannelIndex(channel, 0, band)
        }
        dp.enabled = true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        equalizer?.release()
        loudnessEnhancer?.release()
        dynamics?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
