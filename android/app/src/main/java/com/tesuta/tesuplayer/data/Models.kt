package com.tesuta.tesuplayer.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Source { SERVER, YOUTUBE, TWITCH }

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    /** seconds */
    val duration: Long = 0,
    val coverUrl: String? = null,
    /** direct stream for server tracks; null for YouTube / Twitch (resolved by yt-dlp) */
    val streamUrl: String? = null,
    val source: Source = Source.SERVER,
    /** YouTube / Twitch page link */
    val pageUrl: String? = null,
    val ext: String? = null,
    /** set when downloaded for offline */
    val localPath: String? = null,
    val downloadId: Long? = null,
)

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String = "",
    val coverUrl: String? = null,
    val year: Int? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val count: Int = 0,
    val coverUrl: String? = null,
)

data class SearchResult(val albums: List<Album>, val tracks: List<Track>)

/** A computer running TesuPlayer with "Access from other devices" turned on */
@Serializable
data class ServerConfig(
    val url: String,
    val username: String,
    val password: String,
)

/** Saved YouTube video or Twitch channel */
data class Favorite(val id: String, val name: String, val url: String)

/** Another TesuPlayer that reports to the same computer ("where is it playing") */
data class RemoteDevice(
    val id: String,
    val name: String,
    val state: String,
    val position: Double,
    val track: Track?,
)

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}
