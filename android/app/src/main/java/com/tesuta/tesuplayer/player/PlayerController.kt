package com.tesuta.tesuplayer.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.Streams
import com.tesuta.tesuplayer.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerUi(
    val hasMedia: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artwork: Uri? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queue: List<String> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

/** UI-side handle to PlaybackService. */
class PlayerController(private val context: Context) {
    companion object {
        const val EXTRA_MIME = "mime"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()
    private var queueJob: Job? = null

    private suspend fun connect(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val c = MediaController.Builder(context, token).buildAsync().await()
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
        })
        controller = c
        startTicker()
        return c
    }

    fun warmUp() {
        scope.launch { runCatching { connect() }; publish() }
    }

    private fun startTicker() = scope.launch {
        while (isActive) {
            if (controller?.isPlaying == true) publish()
            delay(500)
        }
    }

    private fun publish() {
        val c = controller ?: return
        val meta = c.mediaMetadata
        _state.value = _state.value.copy(
            hasMedia = c.mediaItemCount > 0,
            mediaId = c.currentMediaItem?.mediaId,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            artwork = meta.artworkUri,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            isLive = c.isCurrentMediaItemLive,
            position = c.currentPosition.coerceAtLeast(0),
            duration = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaMetadata.title?.toString().orEmpty() },
            index = c.currentMediaItemIndex,
            error = c.playerError?.message ?: _state.value.error,
        )
    }

    private fun needsResolve(t: Track) = t.localPath == null && t.source != Source.SERVER

    /** Builds a MediaItem; YouTube / Twitch go through yt-dlp first. */
    private suspend fun toMediaItem(t: Track): MediaItem {
        var uri: String
        var mime: String? = null
        var title = t.title
        var artist = t.artist
        var cover = t.coverUrl
        when {
            t.localPath != null && File(t.localPath).exists() -> uri = Uri.fromFile(File(t.localPath)).toString()
            needsResolve(t) -> {
                val r = Streams.resolve(t.pageUrl ?: error("No link"))
                uri = r.url
                if (r.url.contains(".m3u8")) mime = MimeTypes.APPLICATION_M3U8
                if (title.isBlank() || t.source == Source.TWITCH) title = r.title.ifBlank { t.title }
                if (artist.isBlank()) artist = r.artist
                cover = cover ?: r.thumbnail
            }
            else -> uri = t.streamUrl ?: error("No stream")
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(t.album)
            .setArtworkUri(cover?.let(Uri::parse))
            .build()
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(uri)
            .setMimeType(mime)
            .setMediaMetadata(metadata)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(uri))
                    .setExtras(Bundle().apply { putString(EXTRA_MIME, mime) })
                    .build(),
            )
            .build()
    }

    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false, startPositionMs: Long = 0) {
        if (tracks.isEmpty()) return
        queueJob?.cancel()
        queueJob = scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val c = connect()
                c.shuffleModeEnabled = shuffle
                val startIndex = if (shuffle) tracks.indices.random() else start.coerceIn(tracks.indices)
                if (tracks.none(::needsResolve)) {
                    c.setMediaItems(tracks.map { toMediaItem(it) }, startIndex, startPositionMs)
                    c.prepare(); c.play()
                } else {
                    // resolve the first one now, the rest in the background
                    c.setMediaItem(toMediaItem(tracks[startIndex]), startPositionMs)
                    c.prepare(); c.play()
                    _state.value = _state.value.copy(loading = false)
                    for (t in tracks.drop(startIndex + 1)) {
                        runCatching { toMediaItem(t) }.onSuccess { c.addMediaItem(it) }
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.toString())
            } finally {
                _state.value = _state.value.copy(loading = false)
                publish()
            }
        }
    }

    fun enqueue(track: Track) {
        scope.launch {
            runCatching {
                val c = connect()
                c.addMediaItem(toMediaItem(track))
                if (c.mediaItemCount == 1) { c.prepare(); c.play() }
            }.onFailure { _state.value = _state.value.copy(error = it.message) }
            publish()
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block)
        publish()
    }

    fun toggle() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    /** A live stream stalled or lags: jump to the live edge */
    fun goLive() = withController { it.seekToDefaultPosition(); it.prepare(); it.play() }
    fun pause() = withController { it.pause() }
    fun resume() = withController { it.play() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun stop() = withController { it.stop(); it.clearMediaItems() }
    fun clearError() { _state.value = _state.value.copy(error = null) }
}
