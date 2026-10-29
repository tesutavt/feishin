package com.tesuta.tesuplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

val Http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/**
 * A music library with everything the desktop app can do:
 * the TesuPlayer computer ([PcApi]) or the phone itself ([DeviceMusic]).
 */
interface MusicApi {
    suspend fun albums(sort: AlbumSort = AlbumSort.NEWEST, size: Int = 200, offset: Int = 0): List<Album>
    suspend fun albumTracks(id: String): List<Track>
    suspend fun album(id: String): Album?
    suspend fun artists(): List<Artist>
    suspend fun artist(id: String): ArtistDetail
    suspend fun artistTracks(id: String): List<Track>
    suspend fun songs(sort: SongSort = SongSort.ARTIST, size: Int = 500, offset: Int = 0): List<Track>
    suspend fun randomSongs(size: Int = 50, genre: String? = null): List<Track>
    suspend fun genres(): List<Genre>
    suspend fun genreTracks(genre: String, size: Int = 500): List<Track>
    suspend fun folder(id: String?): FolderContent
    suspend fun search(query: String): SearchResult
    suspend fun starred(): Starred
    suspend fun star(type: StarType, id: String, starred: Boolean)
    suspend fun rate(id: String, rating: Int)
    suspend fun scrobble(id: String)
    suspend fun similar(id: String, count: Int = 30): List<Track>

    suspend fun playlists(): List<Playlist>
    suspend fun playlistTracks(id: String): List<Track>
    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): String
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun deletePlaylist(id: String)
    suspend fun addToPlaylist(id: String, trackIds: List<String>)
    suspend fun removeFromPlaylist(id: String, indexes: List<Int>)

    suspend fun radioStations(): List<RadioStation>
    suspend fun addRadioStation(name: String, url: String)
    suspend fun deleteRadioStation(id: String)

    suspend fun embeddedLyrics(track: Track): String? = null
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private suspend fun fetchJson(request: Request): JsonElement = withContext(Dispatchers.IO) {
    Http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(200)}")
        AppJson.parseToJsonElement(body)
    }
}

fun normalizeUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
}

/** TesuPlayer computer: a Subsonic-compatible API plus tesu* methods for devices */
class PcApi(private val config: ServerConfig) : MusicApi {
    private val base = config.url.toHttpUrl()

    private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun url(method: String, params: List<Pair<String, String>>): HttpUrl {
        val salt = UUID.randomUUID().toString().take(8)
        return base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(method)
            .addQueryParameter("u", config.username)
            .addQueryParameter("t", md5(config.password + salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "TesuPlayer Android")
            .addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    }

    private fun url(method: String, vararg params: Pair<String, String>) = url(method, params.toList())

    private suspend fun call(method: String, params: List<Pair<String, String>>): JsonObject {
        val root = fetchJson(Request.Builder().url(url(method, params)).build())
            .jsonObject["subsonic-response"]?.jsonObject ?: error("Это не TesuPlayer")
        if (root.str("status") != "ok") error(root.obj("error")?.str("message") ?: "Ошибка сервера")
        return root
    }

    private suspend fun call(method: String, vararg params: Pair<String, String>) = call(method, params.toList())

    private fun cover(id: String?) = id?.let { url("getCoverArt", "id" to it, "size" to "500").toString() }

    private fun album(o: JsonObject) = Album(
        id = o.str("id").orEmpty(),
        title = o.str("name") ?: o.str("title").orEmpty(),
        artist = o.str("artist").orEmpty(),
        coverUrl = cover(o.str("coverArt")),
        year = o.int("year"),
        artistId = o.str("artistId"),
        songCount = o.int("songCount") ?: 0,
        genre = o.str("genre"),
        starred = o.str("starred") != null,
        rating = o.int("userRating") ?: 0,
    )

    private fun artist(o: JsonObject) = Artist(
        id = o.str("id").orEmpty(),
        name = o.str("name").orEmpty(),
        albumCount = o.int("albumCount") ?: 0,
        coverUrl = cover(o.str("coverArt") ?: o.str("id")),
        starred = o.str("starred") != null,
    )

    fun track(o: JsonObject): Track {
        val id = o.str("id").orEmpty()
        val youtube = id.startsWith("yt-")
        return Track(
            id = id,
            title = o.str("title").orEmpty(),
            artist = o.str("artist").orEmpty(),
            album = o.str("album").orEmpty(),
            duration = o.long("duration") ?: 0,
            coverUrl = cover(o.str("coverArt")),
            streamUrl = url("stream", "id" to id).toString(),
            ext = o.str("suffix"),
            albumId = o.str("albumId"),
            artistId = o.str("artistId"),
            genre = o.str("genre"),
            year = o.int("year"),
            trackNumber = o.int("track"),
            rating = o.int("userRating") ?: 0,
            starred = o.str("starred") != null,
            playCount = o.int("playCount") ?: 0,
            bitRate = o.int("bitRate"),
            path = o.str("path"),
            pageUrl = if (youtube) o.str("path") else null,
        )
    }

    suspend fun ping() {
        call("ping")
    }

    override suspend fun albums(sort: AlbumSort, size: Int, offset: Int): List<Album> {
        val params = mutableListOf("type" to sort.subsonic, "size" to "$size", "offset" to "$offset")
        if (sort == AlbumSort.YEAR) params += listOf("fromYear" to "3000", "toYear" to "0")
        return call("getAlbumList2", params).obj("albumList2")?.arr("album").orEmpty().map(::album)
    }

    override suspend fun albumTracks(id: String) =
        call("getAlbum", "id" to id).obj("album")?.arr("song").orEmpty().map(::track)

    override suspend fun album(id: String) = call("getAlbum", "id" to id).obj("album")?.let(::album)

    override suspend fun artists() =
        call("getArtists").obj("artists")?.arr("index").orEmpty().flatMap { it.arr("artist") }.map(::artist)

    override suspend fun artist(id: String): ArtistDetail {
        val a = call("getArtist", "id" to id).obj("artist") ?: error("Исполнитель не найден")
        val info = artist(a)
        val top = runCatching {
            call("getTopSongs", "artist" to info.name, "count" to "20").obj("topSongs")?.arr("song").orEmpty().map(::track)
        }.getOrDefault(emptyList())
        return ArtistDetail(info, a.arr("album").map(::album), top)
    }

    override suspend fun artistTracks(id: String): List<Track> =
        artist(id).albums.flatMap { albumTracks(it.id) }

    override suspend fun songs(sort: SongSort, size: Int, offset: Int): List<Track> {
        val list = call(
            "search3", "query" to "", "songCount" to "$size", "songOffset" to "$offset",
            "albumCount" to "0", "artistCount" to "0",
        ).obj("searchResult3")?.arr("song").orEmpty().map(::track)
        return sortSongs(list, sort)
    }

    override suspend fun randomSongs(size: Int, genre: String?): List<Track> {
        val params = mutableListOf("size" to "$size")
        if (genre != null) params += "genre" to genre
        return call("getRandomSongs", params).obj("randomSongs")?.arr("song").orEmpty().map(::track)
    }

    override suspend fun genres() = call("getGenres").obj("genres")?.arr("genre").orEmpty().map {
        Genre(it.str("value").orEmpty(), it.int("songCount") ?: 0, it.int("albumCount") ?: 0)
    }.sortedBy { it.name.lowercase() }

    override suspend fun genreTracks(genre: String, size: Int) =
        call("getSongsByGenre", "genre" to genre, "count" to "$size").obj("songsByGenre")?.arr("song").orEmpty().map(::track)

    override suspend fun folder(id: String?): FolderContent {
        if (id == null) {
            val dirs = call("getIndexes").obj("indexes")?.arr("index").orEmpty().flatMap { it.arr("artist") }
                .map { it.str("id").orEmpty() to it.str("name").orEmpty() }
            return FolderContent("", "Папки", dirs, emptyList())
        }
        val dir = call("getMusicDirectory", "id" to id).obj("directory") ?: error("Папка не найдена")
        val children = dir.arr("child")
        return FolderContent(
            id, dir.str("name").orEmpty(),
            children.filter { it.str("isDir") == "true" }.map { it.str("id").orEmpty() to (it.str("title") ?: it.str("name")).orEmpty() },
            children.filter { it.str("isDir") != "true" }.map(::track),
        )
    }

    override suspend fun search(query: String): SearchResult {
        val r = call("search3", "query" to query, "albumCount" to "20", "songCount" to "60", "artistCount" to "20")
            .obj("searchResult3")
        return SearchResult(r?.arr("album").orEmpty().map(::album), r?.arr("song").orEmpty().map(::track), r?.arr("artist").orEmpty().map(::artist))
    }

    override suspend fun starred(): Starred {
        val s = call("getStarred2").obj("starred2")
        return Starred(s?.arr("song").orEmpty().map(::track), s?.arr("album").orEmpty().map(::album), s?.arr("artist").orEmpty().map(::artist))
    }

    override suspend fun star(type: StarType, id: String, starred: Boolean) {
        val key = when (type) { StarType.SONG -> "id"; StarType.ALBUM -> "albumId"; StarType.ARTIST -> "artistId" }
        call(if (starred) "star" else "unstar", key to id)
    }

    override suspend fun rate(id: String, rating: Int) {
        call("setRating", "id" to id, "rating" to "$rating")
    }

    /** play counts and "recently played" live on the computer */
    override suspend fun scrobble(id: String) {
        call("scrobble", "id" to id, "submission" to "true")
    }

    override suspend fun similar(id: String, count: Int) =
        call("getSimilarSongs2", "id" to id, "count" to "$count").obj("similarSongs2")?.arr("song").orEmpty().map(::track)

    override suspend fun playlists() =
        call("getPlaylists").obj("playlists")?.arr("playlist").orEmpty().map {
            Playlist(it.str("id").orEmpty(), it.str("name").orEmpty(), it.int("songCount") ?: 0, cover(it.str("coverArt")))
        }

    override suspend fun playlistTracks(id: String) =
        call("getPlaylist", "id" to id).obj("playlist")?.arr("entry").orEmpty().map(::track)

    override suspend fun createPlaylist(name: String, trackIds: List<String>): String =
        call("createPlaylist", listOf("name" to name) + trackIds.map { "songId" to it }).obj("playlist")?.str("id").orEmpty()

    override suspend fun renamePlaylist(id: String, name: String) {
        call("updatePlaylist", "playlistId" to id, "name" to name)
    }

    override suspend fun deletePlaylist(id: String) {
        call("deletePlaylist", "id" to id)
    }

    override suspend fun addToPlaylist(id: String, trackIds: List<String>) {
        call("updatePlaylist", listOf("playlistId" to id) + trackIds.map { "songIdToAdd" to it })
    }

    override suspend fun removeFromPlaylist(id: String, indexes: List<Int>) {
        call("updatePlaylist", listOf("playlistId" to id) + indexes.map { "songIndexToRemove" to "$it" })
    }

    override suspend fun radioStations(): List<RadioStation> =
        call("getInternetRadioStations").obj("internetRadioStations")?.arr("internetRadioStation").orEmpty()
            .map { RadioStation(it.str("id").orEmpty(), it.str("name").orEmpty(), it.str("streamUrl").orEmpty()) }

    override suspend fun addRadioStation(name: String, url: String) {
        call("createInternetRadioStation", "name" to name, "streamUrl" to url, "homepageUrl" to url)
    }

    override suspend fun deleteRadioStation(id: String) {
        call("deleteInternetRadioStation", "id" to id)
    }

    override suspend fun embeddedLyrics(track: Track): String? = runCatching {
        call("getLyrics", "artist" to track.artist, "title" to track.title).obj("lyrics")?.str("value")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun song(id: String): Track? = call("getSong", "id" to id).obj("song")?.let(::track)

    /** Favourite YouTube / Twitch links are radio stations on the computer (shared with the desktop app) */
    suspend fun stations(): List<Favorite> =
        radioStations().map { Favorite(it.id, it.name, it.url) }.filter { Streams.sourceOf(it.url) != null }

    suspend fun addStation(name: String, url: String) = addRadioStation(name, url)

    suspend fun deleteStation(id: String) = deleteRadioStation(id)

    /** Reports this phone's player; returns commands for it and the other devices */
    suspend fun heartbeat(
        deviceId: String,
        deviceName: String,
        songId: String?,
        positionSec: Long,
        state: String,
        title: String? = null,
        artist: String? = null,
    ): Pair<List<String>, List<RemoteDevice>> {
        val params = mutableListOf(
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "position" to positionSec.toString(),
            "state" to state,
        )
        if (songId != null) params += "id" to songId
        // phone music, YouTube, Twitch: the computer shows the title instead of a library song
        if (songId == null && title != null) {
            params += "title" to title
            if (artist != null) params += "artist" to artist
        }
        val r = call("tesuHeartbeat", params)
        val commands = (r["commands"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val devices = r.arr("devices").map { d ->
            RemoteDevice(
                id = d.str("id").orEmpty(),
                name = d.str("name").orEmpty(),
                state = d.str("state").orEmpty(),
                position = d.double("position") ?: 0.0,
                track = d.obj("song")?.let(::track),
            )
        }
        return commands to devices
    }

    suspend fun command(deviceId: String, command: String) {
        call("tesuCommand", "deviceId" to deviceId, "command" to command)
    }
}

fun sortSongs(list: List<Track>, sort: SongSort): List<Track> = when (sort) {
    SongSort.NAME -> list.sortedBy { it.title.lowercase() }
    SongSort.ARTIST -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNumber ?: 0 }))
    SongSort.ALBUM -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber ?: 0 }))
    SongSort.RECENT -> list
    SongSort.RANDOM -> list.shuffled()
}
