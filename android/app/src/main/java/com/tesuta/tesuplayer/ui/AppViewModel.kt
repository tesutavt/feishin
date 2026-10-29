package com.tesuta.tesuplayer.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tesuta.tesuplayer.TesuApp
import com.tesuta.tesuplayer.data.Album
import com.tesuta.tesuplayer.data.AlbumSort
import com.tesuta.tesuplayer.data.AppJson
import com.tesuta.tesuplayer.data.DeviceMusic
import com.tesuta.tesuplayer.data.LyricLine
import com.tesuta.tesuplayer.data.Lyrics
import com.tesuta.tesuplayer.data.MusicApi
import com.tesuta.tesuplayer.data.PcApi
import com.tesuta.tesuplayer.data.Playlist
import com.tesuta.tesuplayer.data.RemoteDevice
import com.tesuta.tesuplayer.data.ServerConfig
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.StarType
import com.tesuta.tesuplayer.data.Streams
import com.tesuta.tesuplayer.data.Track
import com.tesuta.tesuplayer.data.normalizeUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Where the music comes from: the phone itself or the TesuPlayer computer */
enum class Library(val label: String) { PHONE("На телефоне"), PC("На компьютере") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val tesu = app as TesuApp
    val prefs = tesu.prefs
    val downloads = tesu.downloads
    val player = tesu.player
    val online = tesu.online
    val phone = DeviceMusic(app, downloads)

    private val _server = MutableStateFlow(prefs.server)
    val server: StateFlow<ServerConfig?> = _server.asStateFlow()

    private val _library = MutableStateFlow(if (prefs.server != null) Library.PC else Library.PHONE)
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _twitch = MutableStateFlow(prefs.twitchChannels)
    val twitchChannels: StateFlow<List<String>> = _twitch.asStateFlow()

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _pcReachable = MutableStateFlow(false)
    val pcReachable: StateFlow<Boolean> = _pcReachable.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** bumps when library data changes (favourite, rating, playlist): screens reload */
    val revision = MutableStateFlow(0)
    private fun changed() { revision.value++ }

    /** tracks waiting for "Добавить в плейлист" */
    val addToPlaylist = MutableStateFlow<List<Track>?>(null)
    /** track for the info / rating sheet */
    val trackInfo = MutableStateFlow<Track?>(null)

    val pc: PcApi? get() = _server.value?.let(::PcApi)

    fun api(library: Library = _library.value): MusicApi = if (library == Library.PC) pc ?: phone else phone

    /** The library a track or id belongs to */
    fun apiFor(id: String): MusicApi = if (id.startsWith("local") || id.startsWith("lp:") || id.startsWith("lr:")) phone else pc ?: phone

    init {
        player.similarProvider = ::similar
        player.warmUp()
        startHeartbeat()
        watchScrobbles()
    }

    fun toast(text: String?) { _message.value = text }

    /** bumps after the music permission is granted, so the phone library reloads */
    val permissionsVersion = MutableStateFlow(0)
    fun permissionsChanged() { phone.invalidate(); permissionsVersion.value++ }

    fun setLibrary(library: Library) { _library.value = library }

    suspend fun connect(url: String, user: String, password: String) {
        val config = ServerConfig(normalizeUrl(url), user.trim(), password)
        PcApi(config).ping()
        prefs.server = config
        _server.value = config
        _library.value = Library.PC
    }

    fun disconnect() {
        prefs.server = null
        _server.value = null
        _library.value = Library.PHONE
        _devices.value = emptyList()
    }

    private fun launchAction(ok: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { if (ok != null) toast(ok); changed() }
            .onFailure { toast("Не получилось: ${it.message}") }
    }

    /** Network first, cached copy when the computer is out of reach */
    suspend fun <T> cached(key: String, serializer: KSerializer<T>, library: Library, load: suspend (MusicApi) -> List<T>): List<T> {
        if (library == Library.PHONE || pc == null) return load(phone)
        val listSerializer = ListSerializer(serializer)
        return try {
            load(api(library)).also { prefs.cache(key, AppJson.encodeToString(listSerializer, it)) }
        } catch (e: Exception) {
            prefs.cached(key)?.let { AppJson.decodeFromString(listSerializer, it) } ?: throw e
        }
    }

    suspend fun albums(library: Library, sort: AlbumSort = AlbumSort.NEWEST, size: Int = 200) =
        cached("albums_${library}_$sort", Album.serializer(), library) { it.albums(sort, size) }
    suspend fun playlists(library: Library) = cached("playlists_$library", Playlist.serializer(), library) { it.playlists() }
    suspend fun albumTracks(id: String) =
        cached("album_$id", Track.serializer(), if (apiFor(id) === phone) Library.PHONE else Library.PC) { it.albumTracks(id) }
    suspend fun playlistTracks(id: String) =
        cached("playlist_$id", Track.serializer(), if (apiFor(id) === phone) Library.PHONE else Library.PC) { it.playlistTracks(id) }

    // ---------------------------------------------------------------- playback

    /** Prefer the downloaded copy of a track (works offline, saves traffic) */
    private fun local(tracks: List<Track>): List<Track> {
        val saved = downloads.items.value.associateBy { it.id }
        val usable = if (online.value) tracks else tracks.filter { saved.containsKey(it.id) || it.id.startsWith("local:") }
        return usable.map { saved[it.id] ?: it }
    }

    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        val target = tracks.getOrNull(start)
        val list = local(tracks)
        if (list.isEmpty()) {
            toast("Нет сети, а эти треки не скачаны")
            return
        }
        player.play(list, list.indexOfFirst { it.id == target?.id }.coerceAtLeast(0), shuffle)
    }

    fun playNext(tracks: List<Track>) { player.playNext(local(tracks)); toast("Сыграет следующим") }
    fun enqueue(tracks: List<Track>) { player.enqueue(local(tracks)); toast("Добавлено в очередь") }

    /** Similar songs: the computer, the phone library or YouTube Mix */
    suspend fun similar(track: Track): List<Track> = when {
        track.source == Source.YOUTUBE -> Streams.mix(track)
        track.id.startsWith("local:") -> phone.similar(track.id)
        else -> pc?.similar(track.id).orEmpty()
    }

    /** "Радио по треку" like the desktop context menu */
    fun trackRadio(track: Track) = viewModelScope.launch {
        toast("Подбираю похожие…")
        runCatching { similar(track) }.onSuccess { more -> player.play(local(listOf(track) + more)) }
            .onFailure { toast("Не получилось: ${it.message}") }
    }

    fun artistRadio(artistId: String) = viewModelScope.launch {
        runCatching { apiFor(artistId).artistTracks(artistId).shuffled().take(100) }
            .onSuccess { play(it) }.onFailure { toast("Не получилось: ${it.message}") }
    }

    fun shuffleAll(library: Library) = viewModelScope.launch {
        runCatching { api(library).randomSongs(200) }.onSuccess { play(it) }.onFailure { toast("Не получилось: ${it.message}") }
    }

    fun download(tracks: List<Track>) {
        viewModelScope.launch {
            var failed = 0
            val todo = tracks.filter { it.source != Source.TWITCH && !it.id.startsWith("local:") && !downloads.isDownloaded(it.id) }
            if (todo.isEmpty()) { toast("Уже на телефоне"); return@launch }
            toast("Скачиваю: ${todo.size}")
            for (t in todo) runCatching { downloads.download(t) }.onFailure { failed++ }
            phone.invalidate()
            toast(if (failed == 0) "Загрузка началась — смотрите уведомления" else "Не удалось скачать: $failed")
        }
    }

    fun share(track: Track) {
        val text = track.pageUrl ?: "${track.artist} — ${track.title}"
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        getApplication<Application>().startActivity(Intent.createChooser(intent, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------------------------------------------------------- favourites, ratings, playlists

    fun star(type: StarType, id: String, starred: Boolean) = launchAction(if (starred) "Добавлено в избранное" else "Убрано из избранного") {
        apiFor(id).star(type, id, starred)
        player.state.value.queue.firstOrNull { it.id == id }?.let { player.updateTrack(it.copy(starred = starred)) }
    }

    /** YouTube song as a favourite track: on the computer (synced) or on the phone */
    fun starYoutube(track: Track, starred: Boolean) = launchAction(if (starred) "Добавлено в избранное" else "Убрано из избранного") {
        val api = pc
        val videoId = Streams.videoId(track)
        if (api != null && videoId != null) {
            api.song("yt-$videoId") // the computer learns the song (title, cover) first
            api.star(StarType.SONG, "yt-$videoId", starred)
        } else {
            phone.starYoutube(track, starred)
        }
    }

    fun rate(track: Track, rating: Int) = launchAction("Оценка: $rating") {
        apiFor(track.id).rate(track.id, rating)
        player.updateTrack(track.copy(rating = rating))
    }

    fun rateAlbum(id: String, rating: Int) = launchAction("Оценка: $rating") { apiFor(id).rate(id, rating) }

    /** Where new playlists go: the computer when connected (synced), otherwise the phone */
    fun playlistLibrary(): MusicApi = pc ?: phone

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()) = launchAction("Плейлист «$name» создан") {
        val target = if (tracks.isNotEmpty()) apiFor(tracks.first().id) else playlistLibrary()
        target.createPlaylist(name, tracks.map { it.id })
    }

    fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>) = launchAction("Добавлено в «${playlist.name}»") {
        apiFor(playlist.id).addToPlaylist(playlist.id, tracks.map { it.id })
    }

    fun removeFromPlaylist(playlistId: String, index: Int) = launchAction("Убрано из плейлиста") {
        apiFor(playlistId).removeFromPlaylist(playlistId, listOf(index))
    }

    fun renamePlaylist(id: String, name: String) = launchAction("Переименовано") { apiFor(id).renamePlaylist(id, name) }

    fun deletePlaylist(id: String) = launchAction("Плейлист удалён") { apiFor(id).deletePlaylist(id) }

    /** Playlists a track can be added to (the library it belongs to) */
    suspend fun playlistsFor(tracks: List<Track>): List<Playlist> =
        (tracks.firstOrNull()?.let { apiFor(it.id) } ?: playlistLibrary()).playlists()

    // ---------------------------------------------------------------- radio stations

    fun addRadioStation(name: String, url: String) = launchAction("Станция добавлена") { (pc ?: phone).addRadioStation(name, url) }
    fun deleteRadioStation(id: String) = launchAction("Станция удалена") { apiFor(id).deleteRadioStation(id) }

    fun playStation(name: String, url: String) {
        when (Streams.sourceOf(url)) {
            null -> player.play(listOf(Track(id = "radio:$url", title = name, artist = "Радио", streamUrl = url)))
            else -> playLink(url)
        }
    }

    // ---------------------------------------------------------------- lyrics

    suspend fun lyrics(track: Track): List<LyricLine>? {
        if (!prefs.lyricsEnabled) return null
        val text = runCatching { apiFor(track.id).embeddedLyrics(track) }.getOrNull() ?: Lyrics.lrclib(track)
        return text?.let(Lyrics::parse)?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- "where is it playing" and play counts

    private fun isPcTrack(id: String) = !id.contains(':')

    /** play counts / "recently played", like the desktop scrobbling setting */
    private fun watchScrobbles() = viewModelScope.launch {
        var last: String? = null
        player.state.collect { s ->
            val id = s.mediaId ?: return@collect
            if (!s.isPlaying || id == last) return@collect
            last = id
            if (!prefs.scrobble) return@collect
            runCatching {
                when {
                    id.startsWith("local:") -> phone.scrobble(id)
                    isPcTrack(id) -> pc?.scrobble(id)
                }
            }
        }
    }

    private fun startHeartbeat() = viewModelScope.launch {
        while (true) {
            val api = pc
            if (api != null && online.value) {
                val state = player.state.value
                val songId = state.mediaId?.takeIf(::isPcTrack)
                runCatching {
                    val (commands, devices) = api.heartbeat(
                        deviceId = prefs.deviceId,
                        deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                        songId = songId,
                        positionSec = state.position / 1000,
                        state = when { !state.hasMedia -> "stopped"; state.isPlaying -> "playing"; else -> "paused" },
                        title = state.title.takeIf { state.hasMedia && it.isNotBlank() },
                        artist = state.artist.takeIf { it.isNotBlank() },
                    )
                    commands.forEach(::runCommand)
                    _devices.value = devices
                    _pcReachable.value = true
                }.onFailure { _pcReachable.value = false }
            }
            delay(5000)
        }
    }

    private fun runCommand(command: String) = when (command) {
        "pause" -> player.pause()
        "play" -> player.resume()
        "next" -> player.next()
        "previous" -> player.previous()
        "stop" -> player.stop()
        else -> Unit
    }

    fun sendCommand(device: RemoteDevice, command: String) {
        viewModelScope.launch {
            runCatching { pc?.command(device.id, command) }.onFailure { toast("Устройство не отвечает") }
        }
    }

    /** Takes over what another device plays: same track and position here, paused there */
    fun playHere(device: RemoteDevice) {
        val track = device.track ?: return
        player.play(local(listOf(track)), 0, startPositionMs = (device.position * 1000).toLong())
        sendCommand(device, "pause")
    }

    // ---------------------------------------------------------------- Twitch / links

    /** Saved on the computer as a radio station: the desktop app shows it too */
    fun addFavorite(name: String, url: String) = launchAction("Добавлено в избранное") { (pc ?: phone).addRadioStation(name, url) }

    fun addTwitch(channel: String) {
        val c = channel.trim().removePrefix("@").substringAfterLast("twitch.tv/").substringBefore('?').trimEnd('/')
        if (c.isEmpty() || c in _twitch.value) return
        _twitch.value = _twitch.value + c
        prefs.twitchChannels = _twitch.value
    }

    fun removeTwitch(channel: String) {
        _twitch.value = _twitch.value - channel
        prefs.twitchChannels = _twitch.value
    }

    fun playTwitch(channel: String) = player.play(
        listOf(Track(id = "tw:$channel", title = channel, artist = "Twitch", source = Source.TWITCH, pageUrl = Streams.twitchUrl(channel))),
    )

    /** Shared YouTube / Twitch link */
    fun playLink(url: String) {
        when (Streams.sourceOf(url)) {
            Source.TWITCH -> playTwitch(url.substringAfterLast("twitch.tv/").substringBefore('?').trimEnd('/'))
            Source.YOUTUBE -> player.play(listOf(Track(id = "yt:$url", title = "", source = Source.YOUTUBE, pageUrl = url)))
            else -> toast("Это не ссылка YouTube или Twitch")
        }
    }
}
