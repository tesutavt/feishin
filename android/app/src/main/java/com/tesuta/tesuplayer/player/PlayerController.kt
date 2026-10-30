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
import com.tesuta.tesuplayer.data.S
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
        c.playbackParameters = params(prefs.speed)
        controller = c
        startTicker()
        return c
    }

    /** Connects and restores the last queue (paused), like the desktop after a restart */
    fun warmUp() {
        scope.launch {
            val c = runCatching { connect() }.getOrNull() ?: return@launch
            if (c.mediaItemCount == 0 && S.savePlayQueue) {
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
        if (S.savePlayQueue && now - lastSaved > 5000) {
            lastSaved = now
            prefs.savedQueue = if (queue.isEmpty()) null else queue to (c.currentMediaItemIndex to c.currentPosition)
        }
    }

    /** YouTube / Twitch without a downloaded file (or the file is gone): yt-dlp finds the stream */
    private fun needsResolve(t: Track) = t.source != Source.SERVER && (t.localPath == null || !File(t.localPath).exists())

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

    /** Playback → Player filters: "field:text" rules; matching songs never enter the queue */
    private fun allowed(t: Track) = S.playerFilters.none { rule ->
        val (field, text) = rule.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (text.isBlank()) return@none false
        val value = when (field) { "title" -> t.title; "artist" -> t.artist; "album" -> t.album; "genre" -> t.genre; else -> null }
        value?.contains(text, ignoreCase = true) == true
    }

    private fun filtered(tracks: List<Track>) = if (S.playerFilters.isEmpty()) tracks else tracks.filter(::allowed)

    /** Speed with or without the pitch following it ("Сохранять тональность") */
    private fun params(speed: Float) = PlaybackParameters(speed, if (S.preservePitch) 1f else speed)

    /** Re-applies the audio settings that live on the controller */
    fun applyAudioSettings() = withController { it.playbackParameters = params(it.playbackParameters.speed) }

    fun play(all: List<Track>, start: Int = 0, shuffle: Boolean = false, startPositionMs: Long = 0) {
        val startTrack = all.getOrNull(start)
        val tracks = filtered(all).ifEmpty { listOfNotNull(startTrack) }
        @Suppress("NAME_SHADOWING") val start = startTrack?.let { tracks.indexOf(it) }?.takeIf { it >= 0 } ?: 0
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
    fun enqueue(all: List<Track>) {
        val tracks = filtered(all)
        if (tracks.isEmpty()) return
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
    fun playNext(all: List<Track>) {
        val tracks = filtered(all)
        if (tracks.isEmpty()) return
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

    /** Every track played in this session, for Auto-DJ "Разрешить дубликаты" */
    private val played = mutableSetOf<String>()

    /** Auto-DJ: when [S.autoDjTiming] tracks are left, append [S.autoDjCount] similar ones */
    private fun maybeAutoDj() {
        val c = controller ?: return
        c.currentMediaItem?.mediaId?.let { played += it }
        val left = c.mediaItemCount - c.currentMediaItemIndex - 1
        if (!prefs.autoDj || autoDjBusy || left >= S.autoDjTiming || c.repeatMode != Player.REPEAT_MODE_OFF) return
        val current = c.currentMediaItem?.mediaId?.let { known[it] } ?: return
        if (current.source == Source.TWITCH) return
        val provider = similarProvider ?: return
        autoDjBusy = true
        scope.launch {
            runCatching {
                val queued = queueTracks(c).map { it.id }.toSet() + if (S.autoDjDuplicates) emptySet() else played
                val more = provider(current).filter { it.id !in queued && allowed(it) }.take(S.autoDjCount)
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

    private var fadeJob: Job? = null

    /** "Плавное изменение звука": a short volume ramp on pause and play */
    private fun fade(c: MediaController, play: Boolean) {
        fadeJob?.cancel()
        if (!S.audioFade) { c.volume = 1f; if (play) c.play() else c.pause(); return }
        fadeJob = scope.launch {
            if (play) { c.volume = 0f; c.play() }
            val from = c.volume
            val to = if (play) 1f else 0f
            for (i in 1..10) { c.volume = from + (to - from) * i / 10f; delay(25) }
            if (!play) { c.pause(); c.volume = 1f }
            publish()
        }
    }

    fun toggle() = withController {
        if (it.isPlaying) fade(it, false) else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); fade(it, true) }
    }
    /** A live stream stalled or lags: jump to the live edge */
    fun goLive() = withController { it.seekToDefaultPosition(); it.prepare(); it.play() }
    fun pause() = withController { fade(it, false) }
    fun resume() = withController { fade(it, true) }
    /** The ±N seconds buttons ("Отображать кнопки перехода") */
    fun skipBy(seconds: Int) = withController { it.seekTo((it.currentPosition + seconds * 1000L).coerceIn(0, it.duration.coerceAtLeast(0))) }
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
        it.playbackParameters = params(speed)
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
