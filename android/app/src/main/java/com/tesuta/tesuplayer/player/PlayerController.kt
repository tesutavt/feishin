package com.tesuta.tesuplayer.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.tesuta.tesuplayer.data.Prefs
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
    val track: Track? = null,
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
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val speed: Float = 1f,
    /** epoch ms when the sleep timer fires; -1 = after the current track */
    val sleepAt: Long? = null,
)

/** UI-side handle to PlaybackService: queue, timer, Auto-DJ like the desktop player */
class PlayerController(private val context: Context, private val prefs: Prefs) {
    companion object {
        const val EXTRA_MIME = "mime"
        const val SLEEP_END_OF_TRACK = -1L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUi(speed = prefs.speed))
    val state: StateFlow<PlayerUi> = _state.asStateFlow()
    private var queueJob: Job? = null
    private var sleepJob: Job? = null
    private var autoDjBusy = false
    private var lastSaved = 0L

    /** Track behind every queued MediaItem (by mediaId) */
    private val known = mutableMapOf<String, Track>()

    /** Set by the ViewModel: similar songs for Auto-DJ */
    var similarProvider: (suspend (Track) -> List<Track>)? = null

    private suspend fun connect(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val c = MediaController.Builder(context, token).buildAsync().await()
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (_state.value.sleepAt == SLEEP_END_OF_TRACK && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    c.pause(); _state.value = _state.value.copy(sleepAt = null)
                }
                maybeAutoDj()
            }
        })
        c.playbackParameters = PlaybackParameters(prefs.speed)
        controller = c
        startTicker()
        return c
    }

    /** Connects and restores the last queue (paused), like the desktop after a restart */
    fun warmUp() {
        scope.launch {
            val c = runCatching { connect() }.getOrNull() ?: return@launch
            if (c.mediaItemCount == 0) {
                prefs.savedQueue?.let { (tracks, pos) ->
                    val restorable = tracks.filter { !needsResolve(it) }
                    if (restorable.isNotEmpty()) {
                        restorable.forEach { known[it.id] = it }
                        runCatching {
                            c.setMediaItems(restorable.map { toMediaItem(it) }, pos.first.coerceIn(restorable.indices), pos.second)
                            c.prepare()
                        }
                    }
                }
            }
            publish()
        }
    }

    private fun startTicker() = scope.launch {
        while (isActive) {
            if (controller?.isPlaying == true) publish()
            val at = _state.value.sleepAt
            if (at != null && at > 0 && System.currentTimeMillis() >= at) {
                controller?.pause(); _state.value = _state.value.copy(sleepAt = null)
            }
            delay(500)
        }
    }

    private fun queueTracks(c: MediaController) = (0 until c.mediaItemCount).map { i ->
        val item = c.getMediaItemAt(i)
        known[item.mediaId] ?: Track(id = item.mediaId, title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty())
    }

    private fun publish() {
        val c = controller ?: return
        val meta = c.mediaMetadata
        val queue = queueTracks(c)
        _state.value = _state.value.copy(
            hasMedia = c.mediaItemCount > 0,
            mediaId = c.currentMediaItem?.mediaId,
            track = c.currentMediaItem?.mediaId?.let { known[it] },
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
            queue = queue,
            index = c.currentMediaItemIndex,
            speed = c.playbackParameters.speed,
            error = c.playerError?.message ?: _state.value.error,
        )
        // save the queue now and then
        val now = System.currentTimeMillis()
        if (now - lastSaved > 5000) {
            lastSaved = now
            prefs.savedQueue = if (queue.isEmpty()) null else queue to (c.currentMediaItemIndex to c.currentPosition)
        }
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
        known[t.id] = t.copy(title = title, artist = artist, coverUrl = cover)
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

    /** At the end of the queue ("Добавить в конец") */
    fun enqueue(tracks: List<Track>) {
        scope.launch {
            runCatching {
                val c = connect()
                for (t in tracks) c.addMediaItem(toMediaItem(t))
                if (c.mediaItemCount == tracks.size) { c.prepare(); c.play() }
            }.onFailure { _state.value = _state.value.copy(error = it.message) }
            publish()
        }
    }

    fun enqueue(track: Track) = enqueue(listOf(track))

    /** Right after the current track ("Играть далее") */
    fun playNext(tracks: List<Track>) {
        scope.launch {
            runCatching {
                val c = connect()
                if (c.mediaItemCount == 0) { play(tracks); return@launch }
                val at = c.currentMediaItemIndex + 1
                c.addMediaItems(at, tracks.map { toMediaItem(it) })
            }.onFailure { _state.value = _state.value.copy(error = it.message) }
            publish()
        }
    }

    /** Auto-DJ: when the last track starts, append similar ones */
    private fun maybeAutoDj() {
        val c = controller ?: return
        if (!prefs.autoDj || autoDjBusy || c.hasNextMediaItem() || c.repeatMode != Player.REPEAT_MODE_OFF) return
        val current = c.currentMediaItem?.mediaId?.let { known[it] } ?: return
        if (current.source == Source.TWITCH) return
        val provider = similarProvider ?: return
        autoDjBusy = true
        scope.launch {
            runCatching {
                val queued = queueTracks(c).map { it.id }.toSet()
                val more = provider(current).filter { it.id !in queued }.take(10)
                for (t in more) c.addMediaItem(toMediaItem(t))
            }
            autoDjBusy = false
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
    fun skipTo(index: Int) = withController { it.seekTo(index, 0); it.play() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun move(from: Int, to: Int) = withController { if (to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) }
    fun remove(index: Int) = withController { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    /** Everything except the current track */
    fun clearUpcoming() = withController { c ->
        val current = c.currentMediaItemIndex
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }
    /** Shuffles the tracks after the current one */
    fun shuffleUpcoming() = withController { c ->
        val start = c.currentMediaItemIndex + 1
        val count = c.mediaItemCount - start
        if (count < 2) return@withController
        val order = (0 until count).shuffled()
        val items = (0 until count).map { c.getMediaItemAt(start + it) }
        c.removeMediaItems(start, c.mediaItemCount)
        c.addMediaItems(start, order.map { items[it] })
    }
    fun setSpeed(speed: Float) = withController {
        prefs.speed = speed
        it.playbackParameters = PlaybackParameters(speed)
    }
    /** Pause after [minutes]; null cancels, [SLEEP_END_OF_TRACK] waits for the track to end */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        _state.value = _state.value.copy(
            sleepAt = when (minutes) {
                null -> null
                -1 -> SLEEP_END_OF_TRACK
                else -> System.currentTimeMillis() + minutes * 60_000L
            },
        )
    }
    fun stop() = withController { it.stop(); it.clearMediaItems() }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    /** Ratings / favourite changed elsewhere: keep the queue's copy in sync */
    fun updateTrack(track: Track) {
        known[track.id]?.let { known[track.id] = it.copy(starred = track.starred, rating = track.rating) }
        publish()
    }
}
