package com.tesuta.tesuplayer.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Request
import java.io.File
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tesuplayer", Context.MODE_PRIVATE)

    init { S.init(sp) }

    val deviceId: String = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString("device_id", it).apply()
    }

    var server: ServerConfig?
        get() = sp.getString("server", null)?.let { runCatching { AppJson.decodeFromString(ServerConfig.serializer(), it) }.getOrNull() }
        set(value) {
            sp.edit().apply {
                if (value == null) remove("server") else putString("server", AppJson.encodeToString(ServerConfig.serializer(), value))
            }.apply()
        }

    var twitchChannels: List<String>
        get() = sp.getString("twitch", null)?.let {
            runCatching { AppJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        }.orEmpty()
        set(value) {
            sp.edit().putString("twitch", AppJson.encodeToString(ListSerializer(String.serializer()), value)).apply()
        }

    private fun bool(key: String, default: Boolean) = sp.getBoolean(key, default)
    private fun setBool(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    /** Settings that mirror the desktop app */
    var lyricsEnabled: Boolean get() = bool("lyrics", true); set(v) = setBool("lyrics", v)
    var lyricsFollow: Boolean get() = bool("lyrics_follow", true); set(v) = setBool("lyrics_follow", v)
    var autoDj: Boolean get() = bool("auto_dj", false); set(v) = setBool("auto_dj", v)
    var scrobble: Boolean get() = bool("scrobble", true); set(v) = setBool("scrobble", v)
    var speed: Float get() = sp.getFloat("speed", 1f); set(v) = sp.edit().putFloat("speed", v).apply()
    var equalizerEnabled: Boolean get() = bool("eq", false); set(v) = setBool("eq", v)
    var equalizerBands: List<Int>
        get() = sp.getString("eq_bands", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        set(v) = sp.edit().putString("eq_bands", v.joinToString(",")).apply()
    var homeSections: List<HomeSection>
        get() = sp.getString("home", null)?.split(',')?.mapNotNull { n -> HomeSection.entries.firstOrNull { it.name == n } }
            ?: HomeSection.entries.toList()
        set(v) = sp.edit().putString("home", v.joinToString(",") { it.name }).apply()

    /** Look: a preset theme or "CUSTOM" with three colours "#bg,#accent,#text" */
    var theme: String? get() = sp.getString("theme", null); set(v) = sp.edit().putString("theme", v).apply()
    var customTheme: String? get() = sp.getString("theme_custom", null); set(v) = sp.edit().putString("theme_custom", v).apply()
    /** Desktop theme .json files imported on this phone: name -> JSON */
    var importedThemes: Map<String, String>
        get() = sp.getString("themes_imported", null)?.let {
            runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull()
        }.orEmpty()
        set(v) = sp.edit().putString("themes_imported",
            AppJson.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), v)).apply()
    /** desktop-style lyrics offset in milliseconds (positive: lyrics later) */
    var lyricsOffsetMs: Int get() = sp.getInt("lyrics_offset", 0); set(v) = sp.edit().putInt("lyrics_offset", v).apply()
    /** a play counts after this many seconds or this share of the song, whichever comes first (desktop scrobbling) */
    var scrobbleSeconds: Int get() = sp.getInt("scrobble_seconds", 240); set(v) = sp.edit().putInt("scrobble_seconds", v).apply()
    var scrobblePercent: Int get() = sp.getInt("scrobble_percent", 50); set(v) = sp.edit().putInt("scrobble_percent", v).apply()

    /** Settings export / import, like the desktop "Export settings" (the computer login is left out) */
    fun exportAll(): String = AppJson.encodeToString(
        kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()),
        sp.all.filterKeys { it != "server" && it != "device_id" && !it.startsWith("cache_") && it != "queue" }
            .mapValues { (_, v) -> "${v!!::class.simpleName}:$v" },
    )

    fun importAll(json: String): Int {
        val map = AppJson.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), json)
        val e = sp.edit()
        map.forEach { (k, typed) ->
            val type = typed.substringBefore(':'); val v = typed.substringAfter(':')
            when (type) {
                "Boolean" -> e.putBoolean(k, v.toBoolean()); "Int" -> e.putInt(k, v.toInt())
                "Long" -> e.putLong(k, v.toLong()); "Float" -> e.putFloat(k, v.toFloat())
                else -> e.putString(k, v)
            }
        }
        e.apply()
        return map.size
    }

    /** drops the cached library lists (they are fetched again) */
    fun clearCache() = sp.edit().apply { sp.all.keys.filter { it.startsWith("cache_") }.forEach(::remove) }.apply()
    /** YouTube songs that are played get downloaded in the background */
    var autoDownload: Boolean get() = bool("auto_download", false); set(v) = setBool("auto_download", v)
    var visualizer: Boolean get() = bool("visualizer", false); set(v) = setBool("visualizer", v)
    /** Parts of the interface turned off in the settings */
    var hidden: Set<UiPart>
        get() = sp.getString("hidden", null)?.split(',')?.mapNotNull { n -> UiPart.entries.firstOrNull { it.name == n } }?.toSet().orEmpty()
        set(v) = sp.edit().putString("hidden", v.joinToString(",") { it.name }).apply()

    /** The queue survives a restart, like on the desktop */
    var savedQueue: Pair<List<Track>, Pair<Int, Long>>?
        get() = runCatching {
            val tracks = AppJson.decodeFromString(ListSerializer(Track.serializer()), sp.getString("queue", null)!!)
            tracks to (sp.getInt("queue_index", 0) to sp.getLong("queue_pos", 0))
        }.getOrNull()
        set(v) {
            sp.edit().apply {
                if (v == null) remove("queue") else {
                    putString("queue", AppJson.encodeToString(ListSerializer(Track.serializer()), v.first))
                    putInt("queue_index", v.second.first)
                    putLong("queue_pos", v.second.second)
                }
            }.apply()
        }

    /** Equalizer description written by the playback service: "bands;min;max;freq1,freq2..." */
    var equalizerInfo: String? get() = sp.getString("eq_info", null); set(v) = sp.edit().putString("eq_info", v).apply()

    private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
    fun listen(onChange: (String?) -> Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChange(key) }
        listeners += listener
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Server library cached for offline browsing */
    fun cache(key: String, json: String) = sp.edit().putString("cache_$key", json).apply()
    fun cached(key: String): String? = sp.getString("cache_$key", null)
}

enum class DownloadState { PENDING, DONE, FAILED }

/** Offline tracks: files via the system DownloadManager, index in downloads.json */
class Downloads(private val context: Context) {
    private val index = File(context.filesDir, "downloads.json")
    private val dir get() = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")
    private val coversDir get() = File(dir, ".covers").apply { mkdirs() }
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val serializer = ListSerializer(Track.serializer())

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Track>> = _items.asStateFlow()

    private fun load(): List<Track> =
        runCatching { AppJson.decodeFromString(serializer, index.readText()) }.getOrDefault(emptyList())

    private fun save(list: List<Track>) {
        _items.value = list
        index.writeText(AppJson.encodeToString(serializer, list))
    }

    fun isDownloaded(id: String) = _items.value.any { it.id == id }

    fun state(track: Track): DownloadState {
        val id = track.downloadId ?: return if (track.localPath?.let { File(it).exists() } == true) DownloadState.DONE else DownloadState.FAILED
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return if (File(track.localPath.orEmpty()).exists()) DownloadState.DONE else DownloadState.FAILED
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // the system may store the file under another name than asked for
                    val actual = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        ?.let { Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path
                    if (actual != null && actual != track.localPath && File(actual).exists()) update(track.id) { it.copy(localPath = actual) }
                    DownloadState.DONE
                }
                DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                else -> DownloadState.PENDING
            }
        }
    }

    /** The finished download with its real file path, or null while pending / failed */
    fun finished(track: Track): Track? =
        if (state(track) == DownloadState.DONE) _items.value.firstOrNull { it.id == track.id } else null

    private fun safeName(value: String) = value.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(80)

    private suspend fun saveCover(id: String, url: String?): String? = withContext(Dispatchers.IO) {
        if (url == null) return@withContext null
        runCatching {
            val file = File(coversDir, safeName(id) + ".jpg")
            Http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching url
                file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            Uri.fromFile(file).toString()
        }.getOrDefault(url)
    }

    suspend fun download(track: Track) {
        if (isDownloaded(track.id) || track.source == Source.TWITCH) return
        var url = track.streamUrl
        var ext = track.ext
        var cover = track.coverUrl
        var saved = track
        val youtube = track.source == Source.YOUTUBE
        if (youtube) {
            val resolved = Streams.resolve(track.pageUrl ?: error("No link"), forDownload = true)
            url = resolved.url
            ext = resolved.ext
            // album / artist / year / cover from the iTunes catalogue, a single otherwise
            val tags = MusicTagsLookup.lookup(resolved.rawTitle, resolved.artist, track.coverUrl ?: resolved.thumbnail)
            cover = tags.coverUrl
            saved = track.copy(
                title = tags.title, artist = tags.artist, album = tags.album, year = tags.year,
                trackNumber = tags.trackNumber, genre = tags.genre, duration = resolved.duration.takeIf { it > 0 } ?: track.duration,
            )
        }
        val request = DownloadManager.Request(Uri.parse(url ?: error("No stream")))
            .setTitle(track.title)
            .setDescription(track.artist)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val file: File
        if (youtube) {
            // a regular music file in Music/TesuPlayer, visible to every player on the phone
            val number = saved.trackNumber?.let { "%02d - ".format(it) }.orEmpty()
            val name = "TesuPlayer/${safeName(saved.artist)}/${safeName(saved.album)}/" +
                safeName("$number${saved.artist} - ${saved.title}") + "." + (ext ?: "m4a")
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, name)
            file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), name)
        } else {
            // offline copy of a computer track: private to the app
            file = File(dir, safeName("${track.artist} - ${track.title} [${track.id.takeLast(8)}]") + "." + (ext ?: "audio"))
            request.setDestinationUri(Uri.fromFile(file))
        }
        val downloadId = dm.enqueue(request)
        val localCover = saveCover(track.id, cover)
        synchronized(this) {
            save(_items.value + saved.copy(localPath = file.absolutePath, downloadId = downloadId, coverUrl = localCover, ext = ext))
        }
    }

    fun delete(track: Track) {
        track.downloadId?.let { runCatching { dm.remove(it) } }
        track.localPath?.let { File(it).delete() }
        synchronized(this) { save(_items.value.filterNot { it.id == track.id }) }
    }

    fun update(id: String, block: (Track) -> Track) = synchronized(this) {
        if (isDownloaded(id)) save(_items.value.map { if (it.id == id) block(it) else it })
    }

    /** Earlier YouTube downloads: artist / album / cover from iTunes again (fixes swapped artist and title) */
    suspend fun retag(): Pair<Int, Int> {
        val list = _items.value.filter { it.source == Source.YOUTUBE }
        var fixed = 0
        for (t in list) {
            val tags = MusicTagsLookup.find(t.artist, t.title) ?: continue
            val cover = tags.coverUrl?.let { saveCover(t.id, it) } ?: t.coverUrl
            update(t.id) {
                it.copy(title = tags.title, artist = tags.artist, album = tags.album, year = tags.year ?: it.year,
                    trackNumber = tags.trackNumber ?: it.trackNumber, genre = tags.genre ?: it.genre, coverUrl = cover)
            }
            fixed++
        }
        return fixed to list.size
    }

    /** For songs iTunes does not know: the title was taken for the artist */
    fun swapArtistTitle(id: String) = update(id) { it.copy(artist = it.title, title = it.artist) }

    fun refresh() {
        _items.value = _items.value.toList()
    }
}
