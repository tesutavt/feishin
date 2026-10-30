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
    /** direct stream for library tracks; null for YouTube / Twitch (resolved by yt-dlp) */
    val streamUrl: String? = null,
    val source: Source = Source.SERVER,
    /** YouTube / Twitch page link */
    val pageUrl: String? = null,
    val ext: String? = null,
    /** set when downloaded for offline */
    val localPath: String? = null,
    val downloadId: Long? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val rating: Int = 0,
    val starred: Boolean = false,
    val playCount: Int = 0,
    val bitRate: Int? = null,
    val path: String? = null,
)

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String = "",
    val coverUrl: String? = null,
    val year: Int? = null,
    val artistId: String? = null,
    val songCount: Int = 0,
    val genre: String? = null,
    val starred: Boolean = false,
    val rating: Int = 0,
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverUrl: String? = null,
    val starred: Boolean = false,
)

@Serializable
data class Genre(val name: String, val songCount: Int = 0, val albumCount: Int = 0)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val count: Int = 0,
    val coverUrl: String? = null,
)

/** A folder in the music library: sub-folders and the songs in it */
data class FolderContent(val id: String, val name: String, val folders: List<Pair<String, String>>, val tracks: List<Track>)

data class ArtistDetail(val artist: Artist, val albums: List<Album>, val topSongs: List<Track>)

data class SearchResult(
    val albums: List<Album>,
    val tracks: List<Track>,
    val artists: List<Artist> = emptyList(),
)

data class Starred(val tracks: List<Track>, val albums: List<Album>, val artists: List<Artist>)

/** Album list orders, the same as the desktop */
enum class AlbumSort(val label: String, val subsonic: String) {
    NEWEST("Недавно добавленные", "newest"),
    NAME("По названию", "alphabeticalByName"),
    ARTIST("По исполнителю", "alphabeticalByArtist"),
    YEAR("По году", "byYear"),
    FREQUENT("Часто слушаемые", "frequent"),
    RECENT("Недавно игранные", "recent"),
    STARRED("Избранные", "starred"),
    RANDOM("Случайные", "random"),
}

enum class SongSort(val label: String) { NAME("По названию"), ARTIST("По исполнителю"), ALBUM("По альбому"), RECENT("Недавно добавленные"), RANDOM("Случайные") }

enum class StarType { SONG, ALBUM, ARTIST }

/** Home page sections, the same as the desktop */
enum class HomeSection(val label: String) {
    RECENTLY_ADDED("Недавно добавленные"),
    RECENTLY_PLAYED("Недавно игранные"),
    MOST_PLAYED("Часто слушаемые"),
    RANDOM("Случайные альбомы"),
    GENRES("Жанры"),
    PLAYLISTS("Плейлисты"),
}

/** Parts of the interface that can be hidden, like the desktop sidebar / player bar settings */
enum class UiPart(val label: String) {
    TAB_DEVICES("Вкладка «Где играет»"),
    TAB_DOWNLOADS("Вкладка «Загрузки»"),
    MINI_PROGRESS("Полоска прогресса в мини-плеере"),
    PLAYER_LYRICS("Текст песни в плеере"),
    PLAYER_QUEUE("Очередь в плеере"),
    PLAYER_SIMILAR("Похожие в плеере"),
    PLAYER_RATING("Оценка и «В плейлист» в плеере"),
    PLAYER_TIMER("Таймер сна в плеере"),
    PLAYER_SPEED("Скорость в плеере"),
}

@Serializable
data class RadioStation(val id: String, val name: String, val url: String)

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
