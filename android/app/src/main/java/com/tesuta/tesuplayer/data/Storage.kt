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
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.DONE
                DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                else -> DownloadState.PENDING
            }
        }
    }

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
        val youtube = track.source == Source.YOUTUBE
        if (youtube) {
            val resolved = Streams.resolve(track.pageUrl ?: error("No link"), forDownload = true)
            url = resolved.url
            ext = resolved.ext
            cover = cover ?: resolved.thumbnail
        }
        val request = DownloadManager.Request(Uri.parse(url ?: error("No stream")))
            .setTitle(track.title)
            .setDescription(track.artist)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val file: File
        if (youtube) {
            // a regular music file in Music/TesuPlayer, visible to every player on the phone
            val name = "TesuPlayer/" + safeName("${track.artist} - ${track.title}") + "." + (ext ?: "m4a")
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
            save(_items.value + track.copy(localPath = file.absolutePath, downloadId = downloadId, coverUrl = localCover, ext = ext))
        }
    }

    fun delete(track: Track) {
        track.downloadId?.let { runCatching { dm.remove(it) } }
        track.localPath?.let { File(it).delete() }
        synchronized(this) { save(_items.value.filterNot { it.id == track.id }) }
    }

    fun refresh() {
        _items.value = _items.value.toList()
    }
}
