package com.tesuta.tesuplayer.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tesuta.tesuplayer.TesuApp
import com.tesuta.tesuplayer.data.Album
import com.tesuta.tesuplayer.data.AppJson
import com.tesuta.tesuplayer.data.DeviceMusic
import com.tesuta.tesuplayer.data.MusicApi
import com.tesuta.tesuplayer.data.PcApi
import com.tesuta.tesuplayer.data.Playlist
import com.tesuta.tesuplayer.data.RemoteDevice
import com.tesuta.tesuplayer.data.ServerConfig
import com.tesuta.tesuplayer.data.Source
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
enum class Library { PHONE, PC }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val tesu = app as TesuApp
    val prefs = tesu.prefs
    val downloads = tesu.downloads
    val player = tesu.player
    val online = tesu.online
    val phone = DeviceMusic(app)

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

    val pc: PcApi? get() = _server.value?.let(::PcApi)

    fun api(library: Library = _library.value): MusicApi = if (library == Library.PC) pc ?: phone else phone

    init {
        player.warmUp()
        startHeartbeat()
    }

    fun toast(text: String?) { _message.value = text }

    /** bumps after the music permission is granted, so the phone library reloads */
    val permissionsVersion = MutableStateFlow(0)
    fun permissionsChanged() { permissionsVersion.value++ }

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

    /** Network first, cached copy when the computer is out of reach */
    private suspend fun <T> cachedList(key: String, serializer: KSerializer<T>, library: Library, load: suspend (MusicApi) -> List<T>): List<T> {
        if (library == Library.PHONE) return load(phone)
        val listSerializer = ListSerializer(serializer)
        return try {
            load(api(library)).also { prefs.cache(key, AppJson.encodeToString(listSerializer, it)) }
        } catch (e: Exception) {
            prefs.cached(key)?.let { AppJson.decodeFromString(listSerializer, it) } ?: throw e
        }
    }

    suspend fun albums(library: Library) = cachedList("albums", Album.serializer(), library) { it.albums() }
    suspend fun playlists() = cachedList("playlists", Playlist.serializer(), Library.PC) { it.playlists() }
    suspend fun albumTracks(id: String) =
        cachedList("album_$id", Track.serializer(), if (id.startsWith("local:")) Library.PHONE else Library.PC) { it.albumTracks(id) }
    suspend fun playlistTracks(id: String) = cachedList("playlist_$id", Track.serializer(), Library.PC) { it.playlistTracks(id) }
    suspend fun favorites() = cachedList("favorites", Track.serializer(), Library.PC) { (it as PcApi).starred() }

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

    fun download(tracks: List<Track>) {
        viewModelScope.launch {
            var failed = 0
            val todo = tracks.filter { it.source != Source.TWITCH && !it.id.startsWith("local:") && !downloads.isDownloaded(it.id) }
            if (todo.isEmpty()) { toast("Уже на телефоне"); return@launch }
            toast("Скачиваю: ${todo.size}")
            for (t in todo) runCatching { downloads.download(t) }.onFailure { failed++ }
            toast(if (failed == 0) "Загрузка началась — смотрите уведомления" else "Не удалось скачать: $failed")
        }
    }

    fun star(track: Track, starred: Boolean) {
        viewModelScope.launch {
            runCatching { pc?.star(track.id, starred) }
                .onSuccess { toast(if (starred) "Добавлено в избранное" else "Убрано из избранного") }
                .onFailure { toast("Компьютер недоступен") }
        }
    }

    // ---------------------------------------------------------------- "where is it playing"

    private fun isPcTrack(id: String) = !id.contains(':')

    private fun startHeartbeat() = viewModelScope.launch {
        var lastScrobbled: String? = null
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
                    )
                    commands.forEach(::runCommand)
                    _devices.value = devices
                    _pcReachable.value = true
                    // play counts / "recently played" on the computer
                    if (songId != null && state.isPlaying && songId != lastScrobbled) {
                        lastScrobbled = songId
                        api.scrobble(songId)
                    }
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
    fun addFavorite(name: String, url: String) {
        viewModelScope.launch {
            runCatching { pc?.addStation(name, url) }
                .onSuccess { toast("Добавлено в избранное") }
                .onFailure { toast("Компьютер недоступен") }
        }
    }

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
