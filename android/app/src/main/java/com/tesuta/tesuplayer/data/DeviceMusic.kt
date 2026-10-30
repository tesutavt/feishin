package com.tesuta.tesuplayer.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** Playlists, favourites, ratings and play counts of the phone's own music */
@Serializable
data class LocalStoreData(
    val starred: Set<String> = emptySet(),
    val ratings: Map<String, Int> = emptyMap(),
    val playCounts: Map<String, Int> = emptyMap(),
    val lastPlayed: Map<String, Long> = emptyMap(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val radio: List<RadioStation> = emptyList(),
    /** favourite YouTube songs (no computer connected) */
    val youtube: List<Track> = emptyList(),
    /** YouTube songs in playlists (Spotify import) */
    val playlistTracks: List<Track> = emptyList(),
)

@Serializable
data class LocalPlaylist(val id: String, val name: String, val trackIds: List<String>)

class LocalStore(context: Context) {
    private val file = File(context.filesDir, "phone-library.json")
    var data: LocalStoreData = runCatching { AppJson.decodeFromString(LocalStoreData.serializer(), file.readText()) }
        .getOrDefault(LocalStoreData())
        private set

    @Synchronized
    fun update(block: (LocalStoreData) -> LocalStoreData) {
        data = block(data)
        file.writeText(AppJson.encodeToString(LocalStoreData.serializer(), data))
    }
}

/** Music stored on the phone itself (MediaStore): works with no network and no computer */
class DeviceMusic(private val context: Context, private val downloads: Downloads) : MusicApi {
    private val artBase = android.net.Uri.parse("content://media/external/audio/albumart")
    private val store = LocalStore(context)
    private val mutex = Mutex()
    private var cache: List<Track>? = null
    private var cachedAt = 0L
    private var cachedDownloads: List<Track>? = null

    fun invalidate() { cache = null }

    private fun key(type: StarType, id: String) = "${type.name}:$id"

    private fun withState(t: Track) = t.copy(
        starred = key(StarType.SONG, t.id) in store.data.starred,
        rating = store.data.ratings[t.id] ?: 0,
        playCount = store.data.playCounts[t.id] ?: 0,
    )

    private suspend fun all(): List<Track> = mutex.withLock {
        val saved = downloads.items.value
        cache?.takeIf { System.currentTimeMillis() - cachedAt < 120_000 && cachedDownloads === saved }?.let { return it }
        val fresh = load(saved)
        cache = fresh
        cachedAt = System.currentTimeMillis()
        cachedDownloads = saved
        fresh
    }

    private suspend fun load(saved: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        val genreColumn = if (Build.VERSION.SDK_INT >= 30) MediaStore.Audio.Media.GENRE else null
        val projection = listOfNotNull(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            genreColumn,
        ).toTypedArray()
        // downloads come from their own index below (real tags, stable ids)
        val finished = saved.mapNotNull(downloads::finished).filter { it.localPath != null }
        val savedPaths = (saved + finished).mapNotNullTo(HashSet()) { it.localPath }
        val tracks = mutableListOf<Pair<Track, Long>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(9)
                    if (path != null && path in savedPaths) continue
                    val fileName = c.getString(6).orEmpty().substringBeforeLast('.')
                    var title = c.getString(1)?.takeIf { it.isNotBlank() } ?: fileName
                    var artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" }.orEmpty()
                    if (artist.isEmpty()) {
                        val parts = fileName.split(" - ", limit = 2)
                        if (parts.size == 2) { artist = parts[0]; if (title == fileName) title = parts[1] }
                    }
                    val albumId = c.getLong(4)
                    val track = Track(
                        id = "local:$id",
                        title = title,
                        artist = artist.ifEmpty { "Неизвестный исполнитель" },
                        album = c.getString(3).orEmpty(),
                        duration = c.getLong(5) / 1000,
                        coverUrl = ContentUris.withAppendedId(artBase, albumId).toString(),
                        streamUrl = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        albumId = "local:$albumId",
                        trackNumber = (c.getInt(7) % 1000).takeIf { it > 0 },
                        year = c.getInt(8).takeIf { it > 0 },
                        path = path,
                        genre = if (genreColumn != null) c.getString(11) else null,
                    )
                    tracks += track.copy(artistId = "local-artist:${track.artist}") to c.getLong(10)
                }
            }
        }
        // finished downloads, also those the system has not indexed (yet) and the offline copies
        // of computer songs, which live in the app folder the system never indexes
        for (d in finished) {
            val path = d.localPath!!
            val file = File(path)
            val artist = d.artist.ifBlank { "Неизвестный исполнитель" }
            tracks += d.copy(
                id = "local:dl:${d.id}", artist = artist, source = Source.SERVER,
                streamUrl = android.net.Uri.fromFile(file).toString(), path = path,
                albumId = "local:$artist:${d.album}", artistId = "local-artist:$artist",
                starred = false, rating = 0, playCount = 0,
            ) to file.lastModified() / 1000
        }
        tracks.sortedByDescending { it.second }.map { it.first }
    }

    private fun albumsOf(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumId }.map { (id, list) ->
            val first = list.first()
            Album(
                id = id ?: "local:0",
                title = first.album.ifBlank { "Без альбома" },
                artist = list.map { it.artist }.distinct().singleOrNull() ?: "Разные исполнители",
                coverUrl = first.coverUrl,
                year = list.mapNotNull { it.year }.maxOrNull(),
                artistId = first.artistId,
                songCount = list.size,
                genre = first.genre,
                starred = key(StarType.ALBUM, id.orEmpty()) in store.data.starred,
                rating = store.data.ratings[id] ?: 0,
            )
        }

    override suspend fun albums(sort: AlbumSort, size: Int, offset: Int): List<Album> {
        val tracks = all()
        val list = albumsOf(tracks)
        val plays = { a: Album -> tracks.filter { it.albumId == a.id }.sumOf { store.data.playCounts[it.id] ?: 0 } }
        val played = { a: Album -> tracks.filter { it.albumId == a.id }.maxOfOrNull { store.data.lastPlayed[it.id] ?: 0 } ?: 0 }
        val sorted = when (sort) {
            AlbumSort.NEWEST -> list // tracks are newest first
            AlbumSort.NAME -> list.sortedBy { it.title.lowercase() }
            AlbumSort.ARTIST -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            AlbumSort.YEAR -> list.sortedByDescending { it.year ?: 0 }
            AlbumSort.FREQUENT -> list.filter { plays(it) > 0 }.sortedByDescending(plays)
            AlbumSort.RECENT -> list.filter { played(it) > 0 }.sortedByDescending(played)
            AlbumSort.STARRED -> list.filter { it.starred }
            AlbumSort.RANDOM -> list.shuffled()
        }
        return sorted.drop(offset).take(size)
    }

    override suspend fun albumTracks(id: String) =
        all().filter { it.albumId == id }.sortedBy { it.trackNumber ?: 0 }.map(::withState)

    override suspend fun album(id: String) = albumsOf(all().filter { it.albumId == id }).firstOrNull()

    override suspend fun artists(): List<Artist> = all().groupBy { it.artistId }.map { (id, list) ->
        Artist(
            id = id.orEmpty(), name = list.first().artist, albumCount = list.map { it.albumId }.distinct().size,
            coverUrl = list.first().coverUrl, starred = key(StarType.ARTIST, id.orEmpty()) in store.data.starred,
        )
    }.sortedBy { it.name.lowercase() }

    override suspend fun artist(id: String): ArtistDetail {
        val tracks = all().filter { it.artistId == id }
        val info = artists().firstOrNull { it.id == id } ?: Artist(id, tracks.firstOrNull()?.artist.orEmpty())
        val top = tracks.sortedByDescending { store.data.playCounts[it.id] ?: 0 }.take(20).map(::withState)
        return ArtistDetail(info, albumsOf(tracks), top)
    }

    override suspend fun artistTracks(id: String) = all().filter { it.artistId == id }.map(::withState)

    override suspend fun songs(sort: SongSort, size: Int, offset: Int) =
        sortSongs(all(), sort).drop(offset).take(size).map(::withState)

    override suspend fun randomSongs(size: Int, genre: String?) =
        all().filter { genre == null || it.genre == genre }.shuffled().take(size).map(::withState)

    override suspend fun genres() = all().filter { !it.genre.isNullOrBlank() }.groupBy { it.genre!! }
        .map { (name, list) -> Genre(name, list.size, list.map { it.albumId }.distinct().size) }
        .sortedBy { it.name.lowercase() }

    override suspend fun genreTracks(genre: String, size: Int) = all().filter { it.genre == genre }.take(size).map(::withState)

    /** Folders that hold music; id = absolute path */
    override suspend fun folder(id: String?): FolderContent {
        val tracks = all().filter { it.path != null }
        val dirs = tracks.map { File(it.path!!).parent.orEmpty() }.distinct()
        if (id == null) {
            // top level: the shortest folders that are not inside another music folder
            val roots = dirs.filter { d -> dirs.none { o -> o != d && d.startsWith("$o/") } }.sorted()
            return FolderContent("", "Папки", roots.map { it to it.substringAfterLast('/') }, emptyList())
        }
        val children = dirs.filter { it.startsWith("$id/") }
            .map { id + "/" + it.removePrefix("$id/").substringBefore('/') }.distinct().sorted()
        return FolderContent(
            id, id.substringAfterLast('/'),
            children.map { it to it.substringAfterLast('/') },
            tracks.filter { File(it.path!!).parent == id }.sortedBy { it.trackNumber ?: 0 }.map(::withState),
        )
    }

    override suspend fun search(query: String): SearchResult {
        val q = query.lowercase()
        val tracks = all().filter { listOf(it.title, it.artist, it.album).any { f -> f.lowercase().contains(q) } }
        return SearchResult(
            albumsOf(all()).filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) },
            tracks.map(::withState),
            artists().filter { it.name.lowercase().contains(q) },
        )
    }

    override suspend fun starred(): Starred {
        val s = store.data.starred
        return Starred(
            store.data.youtube.map { it.copy(starred = true) } + all().filter { key(StarType.SONG, it.id) in s }.map(::withState),
            albumsOf(all()).filter { it.starred },
            artists().filter { it.starred },
        )
    }

    override suspend fun star(type: StarType, id: String, starred: Boolean) =
        store.update { d -> d.copy(starred = if (starred) d.starred + key(type, id) else d.starred - key(type, id)) }

    fun starYoutube(track: Track, starred: Boolean) = store.update { d ->
        d.copy(youtube = if (starred) d.youtube.filterNot { it.id == track.id } + track else d.youtube.filterNot { it.id == track.id })
    }

    override suspend fun rate(id: String, rating: Int) =
        store.update { d -> d.copy(ratings = if (rating > 0) d.ratings + (id to rating) else d.ratings - id) }

    override suspend fun scrobble(id: String) = store.update { d ->
        d.copy(playCounts = d.playCounts + (id to (d.playCounts[id] ?: 0) + 1), lastPlayed = d.lastPlayed + (id to System.currentTimeMillis()))
    }

    override suspend fun similar(id: String, count: Int): List<Track> {
        val base = all().firstOrNull { it.id == id }
        val pool = all().filter { it.id != id && (base == null || it.artistId == base.artistId || (base.genre != null && it.genre == base.genre)) }
        return (pool.ifEmpty { all() }).shuffled().take(count).map(::withState)
    }

    override suspend fun playlists() = store.data.playlists.map { p ->
        val first = (all() + store.data.playlistTracks).firstOrNull { it.id == p.trackIds.firstOrNull() }
        Playlist(p.id, p.name, p.trackIds.size, first?.coverUrl)
    }

    override suspend fun playlistTracks(id: String): List<Track> {
        val byId = (store.data.playlistTracks + all()).associateBy { it.id }
        return store.data.playlists.firstOrNull { it.id == id }?.trackIds.orEmpty().mapNotNull { byId[it] }.map(::withState)
    }

    override suspend fun createPlaylist(name: String, trackIds: List<String>): String {
        val id = "lp:" + UUID.randomUUID().toString().take(8)
        store.update { it.copy(playlists = it.playlists + LocalPlaylist(id, name, trackIds)) }
        return id
    }

    /** A playlist of phone songs and YouTube songs (these are kept with it) */
    fun createPlaylistOf(name: String, tracks: List<Track>) {
        val extra = tracks.filter { !it.id.startsWith("local") }
        store.update { d ->
            d.copy(
                playlists = d.playlists + LocalPlaylist("lp:" + UUID.randomUUID().toString().take(8), name, tracks.map { it.id }),
                playlistTracks = d.playlistTracks.filterNot { t -> extra.any { it.id == t.id } } + extra,
            )
        }
    }

    /** Spotify import: the phone's own copy of a song */
    suspend fun find(title: String, artist: String): Track? {
        val t = Spotify.normalize(title)
        val artists = artist.split(',', '&').map(Spotify::normalize).filter { it.isNotEmpty() }
        return all().firstOrNull { s ->
            Spotify.normalize(s.title) == t && (artists.isEmpty() || Spotify.normalize(s.artist).let { a -> artists.any { a.contains(it) || it.contains(a) } })
        }
    }

    private fun editPlaylist(id: String, block: (LocalPlaylist) -> LocalPlaylist) =
        store.update { d -> d.copy(playlists = d.playlists.map { if (it.id == id) block(it) else it }) }

    override suspend fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }

    override suspend fun deletePlaylist(id: String) = store.update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }

    override suspend fun addToPlaylist(id: String, trackIds: List<String>) = editPlaylist(id) { it.copy(trackIds = it.trackIds + trackIds) }

    override suspend fun removeFromPlaylist(id: String, indexes: List<Int>) =
        editPlaylist(id) { p -> p.copy(trackIds = p.trackIds.filterIndexed { i, _ -> i !in indexes }) }

    override suspend fun radioStations() = store.data.radio

    override suspend fun addRadioStation(name: String, url: String) =
        store.update { it.copy(radio = it.radio + RadioStation("lr:" + UUID.randomUUID().toString().take(8), name, url)) }

    override suspend fun deleteRadioStation(id: String) = store.update { d -> d.copy(radio = d.radio.filterNot { it.id == id }) }
}
