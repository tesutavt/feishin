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

/** A music source: this phone or the TesuPlayer computer */
interface MusicApi {
    suspend fun albums(): List<Album>
    suspend fun albumTracks(id: String): List<Track>
    suspend fun playlists(): List<Playlist>
    suspend fun playlistTracks(id: String): List<Track>
    suspend fun search(query: String): SearchResult
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

    private fun url(method: String, vararg params: Pair<String, String>): HttpUrl {
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

    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonObject {
        val root = fetchJson(Request.Builder().url(url(method, *params)).build())
            .jsonObject["subsonic-response"]?.jsonObject ?: error("Это не TesuPlayer")
        if (root.str("status") != "ok") error(root.obj("error")?.str("message") ?: "Ошибка сервера")
        return root
    }

    private fun cover(id: String?) = id?.let { url("getCoverArt", "id" to it, "size" to "500").toString() }

    private fun album(o: JsonObject) = Album(
        id = o.str("id").orEmpty(),
        title = o.str("name") ?: o.str("title").orEmpty(),
        artist = o.str("artist").orEmpty(),
        coverUrl = cover(o.str("coverArt")),
        year = o.int("year"),
    )

    fun track(o: JsonObject) = Track(
        id = o.str("id").orEmpty(),
        title = o.str("title").orEmpty(),
        artist = o.str("artist").orEmpty(),
        album = o.str("album").orEmpty(),
        duration = o.long("duration") ?: 0,
        coverUrl = cover(o.str("coverArt")),
        streamUrl = url("stream", "id" to o.str("id").orEmpty()).toString(),
        ext = o.str("suffix"),
    )

    suspend fun ping() {
        call("ping")
    }

    override suspend fun albums() =
        call("getAlbumList2", "type" to "newest", "size" to "200").obj("albumList2")?.arr("album").orEmpty().map(::album)

    override suspend fun albumTracks(id: String) =
        call("getAlbum", "id" to id).obj("album")?.arr("song").orEmpty().map(::track)

    override suspend fun playlists() =
        call("getPlaylists").obj("playlists")?.arr("playlist").orEmpty().map {
            Playlist(it.str("id").orEmpty(), it.str("name").orEmpty(), it.int("songCount") ?: 0, cover(it.str("coverArt")))
        }

    override suspend fun playlistTracks(id: String) =
        call("getPlaylist", "id" to id).obj("playlist")?.arr("entry").orEmpty().map(::track)

    override suspend fun search(query: String): SearchResult {
        val r = call("search3", "query" to query, "albumCount" to "20", "songCount" to "60", "artistCount" to "0")
            .obj("searchResult3")
        return SearchResult(r?.arr("album").orEmpty().map(::album), r?.arr("song").orEmpty().map(::track))
    }

    suspend fun song(id: String): Track? = call("getSong", "id" to id).obj("song")?.let(::track)

    /** play counts and "recently played" live on the computer */
    suspend fun scrobble(id: String) {
        call("scrobble", "id" to id, "submission" to "true")
    }

    suspend fun star(id: String, starred: Boolean) {
        call(if (starred) "star" else "unstar", "id" to id)
    }

    suspend fun starred(): List<Track> = call("getStarred2").obj("starred2")?.arr("song").orEmpty().map(::track)

    /** Reports this phone's player; returns commands for it and the other devices */
    suspend fun heartbeat(
        deviceId: String,
        deviceName: String,
        songId: String?,
        positionSec: Long,
        state: String,
    ): Pair<List<String>, List<RemoteDevice>> {
        val params = mutableListOf(
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "position" to positionSec.toString(),
            "state" to state,
        )
        if (songId != null) params += "id" to songId
        val r = call("tesuHeartbeat", *params.toTypedArray())
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

    /** Favourite YouTube / Twitch links are radio stations on the computer (shared with the desktop app) */
    suspend fun stations(): List<Favorite> =
        call("getInternetRadioStations").obj("internetRadioStations")?.arr("internetRadioStation").orEmpty()
            .map { Favorite(it.str("id").orEmpty(), it.str("name").orEmpty(), it.str("streamUrl").orEmpty()) }
            .filter { Streams.sourceOf(it.url) != null }

    suspend fun addStation(name: String, url: String) {
        call("createInternetRadioStation", "name" to name, "streamUrl" to url, "homepageUrl" to url)
    }

    suspend fun deleteStation(id: String) {
        call("deleteInternetRadioStation", "id" to id)
    }

    suspend fun command(deviceId: String, command: String) {
        call("tesuCommand", "deviceId" to deviceId, "command" to command)
    }
}
