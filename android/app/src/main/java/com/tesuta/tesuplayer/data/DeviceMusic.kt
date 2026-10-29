package com.tesuta.tesuplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Music stored on the phone itself (MediaStore): works with no network and no computer */
class DeviceMusic(private val context: Context) : MusicApi {
    private val artBase = android.net.Uri.parse("content://media/external/audio/albumart")

    private suspend fun load(selection: String? = null, args: Array<String>? = null): List<Track> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TRACK,
        )
        val where = listOfNotNull("${MediaStore.Audio.Media.IS_MUSIC} != 0", selection).joinToString(" AND ")
        val tracks = mutableListOf<Track>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, where, args,
                "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val fileName = c.getString(6).orEmpty().substringBeforeLast('.')
                    var title = c.getString(1)?.takeIf { it.isNotBlank() } ?: fileName
                    var artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" }.orEmpty()
                    // "Artist - Title.m4a" (e.g. YouTube downloads) without tags
                    if (artist.isEmpty()) {
                        val parts = fileName.split(" - ", limit = 2)
                        if (parts.size == 2) { artist = parts[0]; if (title == fileName) title = parts[1] }
                    }
                    tracks += Track(
                        id = "local:$id",
                        title = title,
                        artist = artist,
                        album = c.getString(3).orEmpty(),
                        duration = c.getLong(5) / 1000,
                        coverUrl = ContentUris.withAppendedId(artBase, c.getLong(4)).toString(),
                        streamUrl = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        localPath = null,
                    )
                }
            }
        }
        tracks
    }

    suspend fun allTracks() = load()

    override suspend fun albums(): List<Album> {
        val byAlbum = load().groupBy { it.album to it.coverUrl }
        return byAlbum.map { (key, tracks) ->
            val albumId = key.second?.substringAfterLast('/') ?: "0"
            Album(
                id = "local:$albumId",
                title = key.first.ifBlank { "Без альбома" },
                artist = tracks.map { it.artist }.distinct().singleOrNull() ?: "Разные исполнители",
                coverUrl = key.second,
            )
        }.sortedBy { it.title.lowercase() }
    }

    override suspend fun albumTracks(id: String) =
        load("${MediaStore.Audio.Media.ALBUM_ID} = ?", arrayOf(id.removePrefix("local:")))

    override suspend fun playlists() = emptyList<Playlist>()

    override suspend fun playlistTracks(id: String) = emptyList<Track>()

    override suspend fun search(query: String): SearchResult {
        val q = query.lowercase()
        val tracks = load().filter { listOf(it.title, it.artist, it.album).any { f -> f.lowercase().contains(q) } }
        return SearchResult(emptyList(), tracks)
    }
}
