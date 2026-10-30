package com.tesuta.tesuplayer.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tesuta.tesuplayer.data.Album
import com.tesuta.tesuplayer.data.AlbumSort
import com.tesuta.tesuplayer.data.Artist
import com.tesuta.tesuplayer.data.HomeSection
import com.tesuta.tesuplayer.data.Playlist
import com.tesuta.tesuplayer.data.SongSort
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.StarType
import com.tesuta.tesuplayer.data.Track

const val FAVORITES_ID = "__favorites"

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Fail(val message: String) : Load<Nothing>
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Load<T> {
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys) {
        state = try { Load.Ok(block()) } catch (e: Exception) { Load.Fail(e.message ?: e.toString()) }
    }
    return state
}

/** Screen data that reloads after favourites / playlists / ratings change */
@Composable
fun <T> rememberLibraryLoad(vm: AppViewModel, vararg keys: Any?, block: suspend () -> T): Load<T> {
    val revision by vm.revision.collectAsState()
    return rememberLoad(*keys, revision, block = block)
}

@Composable
fun <T> LoadContent(state: Load<T>, empty: String = "Пусто", isEmpty: (T) -> Boolean = { (it as? List<*>)?.isEmpty() == true }, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Loading()
        is Load.Fail -> Empty("Не удалось загрузить:\n${state.message}")
        is Load.Ok -> if (isEmpty(state.value)) Empty(empty, Icons.Default.MusicNote) else content(state.value)
    }
}

/** Navigation from any list, like the desktop "Go to" actions */
class Nav(private val go: (String) -> Unit) {
    private fun e(s: String) = Uri.encode(s)
    fun album(id: String) = go("album/${e(id)}")
    fun artist(id: String) = go("artist/${e(id)}")
    fun genre(name: String) = go("genre/${e(name)}")
    fun folder(id: String) = go("folder/${e(id)}")
    fun playlist(id: String, name: String) = go("playlist/${e(id)}/${e(name)}")
    fun to(route: String) = go(route)
}

private fun isLibraryTrack(t: Track) = t.source == Source.SERVER && !t.id.startsWith("radio:")

/** The desktop context menu for a track */
@Composable
fun trackActions(vm: AppViewModel, nav: Nav, track: Track, list: List<Track>, playlistId: String? = null, index: Int = -1): List<TrackAction> {
    val downloaded = vm.downloads.items.collectAsState().value.any { it.id == track.id }
    return buildList {
        add(TrackAction("Играть") { vm.play(list, list.indexOf(track).coerceAtLeast(0)) })
        add(TrackAction("Играть далее") { vm.playNext(listOf(track)) })
        add(TrackAction("Добавить в конец очереди") { vm.enqueue(listOf(track)) })
        if (track.source != Source.TWITCH) add(TrackAction("Радио по треку") { vm.trackRadio(track) })
        if (isLibraryTrack(track)) {
            add(TrackAction("Добавить в плейлист") { vm.addToPlaylist.value = listOf(track) })
        }
        if (isLibraryTrack(track) || (track.source == Source.YOUTUBE && track.pageUrl != null)) {
            add(TrackAction(if (track.starred) "Убрать из избранного" else "В избранное") { vm.toggleStar(track) })
        }
        if (playlistId != null && index >= 0) add(TrackAction("Убрать из плейлиста") { vm.removeFromPlaylist(playlistId, index) })
        if (track.albumId != null && isLibraryTrack(track)) add(TrackAction("Перейти к альбому") { nav.album(track.albumId) })
        if (track.artistId != null && isLibraryTrack(track)) add(TrackAction("Перейти к исполнителю") { nav.artist(track.artistId) })
        val downloadable = track.source != Source.TWITCH && !track.id.startsWith("local:") && !track.id.startsWith("radio:")
        if (downloadable && !downloaded) {
            add(TrackAction(if (track.source == Source.YOUTUBE) "Скачать как музыку" else "Скачать на телефон") { vm.download(listOf(track)) })
        }
        if (vm.canSendToPc(track)) add(TrackAction("Отправить на компьютер") { vm.sendToPc(listOf(track)) })
        if (downloaded) add(TrackAction("Удалить загрузку") { vm.downloads.items.value.find { it.id == track.id }?.let(vm.downloads::delete) })
        add(TrackAction("Информация и оценка") { vm.trackInfo.value = track })
        add(TrackAction("Поделиться") { vm.share(track) })
    }
}

@Composable
fun TrackList(
    vm: AppViewModel,
    nav: Nav,
    tracks: List<Track>,
    numbered: Boolean = false,
    playlistId: String? = null,
    header: @Composable () -> Unit = {},
) {
    val saved = vm.downloads.items.collectAsState().value.map { it.id }.toSet()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { header() }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
            TrackRow(t, t.id in saved, onClick = { vm.play(tracks, i) },
                actions = trackActions(vm, nav, t, tracks, playlistId, i), index = if (numbered) (t.trackNumber ?: (i + 1)) else null)
        }
    }
}

/** Play / shuffle / play next / to playlist / download, like the desktop list headers */
@Composable
fun ListActions(vm: AppViewModel, tracks: List<Track>, extra: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Default.Shuffle, "Вперемешку") { vm.play(tracks, shuffle = true) }
        PillButton("Играть", Icons.Default.PlayArrow, { vm.play(tracks) })
        RoundIconButton(Icons.Default.SkipNext, "Играть далее") { vm.playNext(tracks) }
        if (tracks.any(::isLibraryTrack)) RoundIconButton(Icons.AutoMirrored.Filled.PlaylistAdd, "В плейлист") { vm.addToPlaylist.value = tracks }
        if (tracks.any { !it.id.startsWith("local:") && it.source != Source.TWITCH }) RoundIconButton(Icons.Default.Download, "Скачать") { vm.download(tracks) }
        if (tracks.any(vm::canSendToPc)) RoundIconButton(Icons.Default.Computer, "На компьютер") { vm.sendToPc(tracks) }
        extra()
    }
}

@Composable
fun ListHeader(title: String, subtitle: String, cover: String?, vm: AppViewModel, tracks: List<Track>, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(cover, 200.dp, radius = 18.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, color = Palette.subtext, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        ListActions(vm, tracks, extra)
    }
}

@Composable
fun LibrarySwitch(vm: AppViewModel) {
    val library by vm.library.collectAsState()
    if (vm.server.collectAsState().value == null) return
    Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Library.entries.forEach { l -> Chip(l.label, library == l) { vm.setLibrary(l) } }
    }
}

// ---------------------------------------------------------------- cards

@Composable
fun AlbumCard(album: Album, size: Int = 160, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Cover(album.coverUrl, size.dp, radius = 14.dp)
        Spacer(Modifier.height(6.dp))
        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(size.dp))
        Text(listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = Palette.subtext, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(size.dp))
    }
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(artist.coverUrl, 52.dp, radius = 26.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(artist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.albumCount > 0) Text("Альбомов: ${artist.albumCount}", color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        }
        if (artist.starred) Icon(Icons.Default.Favorite, null, tint = Palette.pink, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SimpleRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface0), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.mauve)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = Palette.subtext, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        trailing()
    }
}

@Composable
private fun AlbumShelf(title: String, albums: List<Album>, nav: Nav) {
    if (albums.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
        items(albums, key = { it.id }) { AlbumCard(it, 140) { nav.album(it.id) } }
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav) {
    val library by vm.library.collectAsState()
    val sections = remember { vm.prefs.homeSections }
    val api = vm.api(library)
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.padding(16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Перемешать всё", Icons.Default.Shuffle, { vm.shuffleAll(library) })
                }
            }
            for (section in sections) {
                item(key = section.name) {
                    when (section) {
                        HomeSection.RECENTLY_ADDED -> HomeAlbums(vm, nav, section.label, library, AlbumSort.NEWEST)
                        HomeSection.RECENTLY_PLAYED -> HomeAlbums(vm, nav, section.label, library, AlbumSort.RECENT)
                        HomeSection.MOST_PLAYED -> HomeAlbums(vm, nav, section.label, library, AlbumSort.FREQUENT)
                        HomeSection.RANDOM -> HomeAlbums(vm, nav, section.label, library, AlbumSort.RANDOM)
                        HomeSection.GENRES -> {
                            val genres = rememberLoad("home_genres", library) { api.genres().sortedByDescending { it.songCount }.take(20) }
                            if (genres is Load.Ok && genres.value.isNotEmpty()) {
                                SectionTitle(section.label)
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(genres.value, key = { it.name }) { g -> Chip(g.name, false) { nav.genre(g.name) } }
                                }
                            }
                        }
                        HomeSection.PLAYLISTS -> {
                            val playlists = rememberLibraryLoad(vm, "home_playlists", library) { vm.playlists(library) }
                            if (playlists is Load.Ok && playlists.value.isNotEmpty()) {
                                SectionTitle(section.label)
                                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    items(playlists.value, key = { it.id }) { p ->
                                        AlbumCard(Album(p.id, p.name, "${p.count} треков", p.coverUrl), 140) { nav.playlist(p.id, p.name) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val any = rememberLoad("home_any", library) { api.albums(AlbumSort.NEWEST, 1) }
                if (any is Load.Ok && any.value.isEmpty()) {
                    Empty(
                        if (library == Library.PHONE) "На телефоне пока нет музыки.\nНайдите песню в «Поиске» на YouTube и скачайте её, или подключите свой компьютер в настройках."
                        else "На компьютере пока нет музыки",
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeAlbums(vm: AppViewModel, nav: Nav, title: String, library: Library, sort: AlbumSort) {
    val albums = rememberLibraryLoad(vm, "home", library, sort) { vm.albums(library, sort, 20) }
    if (albums is Load.Ok) AlbumShelf(title, albums.value, nav)
}

// ---------------------------------------------------------------- Library hub

@Composable
fun LibraryScreen(vm: AppViewModel, nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        LazyColumn {
            item { SimpleRow(Icons.Default.Album, "Альбомы", onClick = { nav.to("albums") }) }
            item { SimpleRow(Icons.Default.Person, "Исполнители", onClick = { nav.to("artists") }) }
            item { SimpleRow(Icons.Default.MusicNote, "Треки", onClick = { nav.to("songs") }) }
            item { SimpleRow(Icons.Default.Sell, "Жанры", onClick = { nav.to("genres") }) }
            item { SimpleRow(Icons.Default.Folder, "Папки", onClick = { nav.to("folders") }) }
            item { SimpleRow(Icons.Default.Favorite, "Избранное", "Треки, альбомы, исполнители", onClick = { nav.to("favorites") }) }
            item { SimpleRow(Icons.AutoMirrored.Filled.QueueMusic, "Плейлисты", onClick = { nav.to("playlists") }) }
            item { SimpleRow(Icons.Default.Radio, "Радиостанции", "Интернет-радио, Twitch и YouTube", onClick = { nav.to("radio") }) }
        }
    }
}

// ---------------------------------------------------------------- Albums

@Composable
fun AlbumsScreen(vm: AppViewModel, nav: Nav) {
    val library by vm.library.collectAsState()
    var sort by rememberSaveable { mutableStateOf(AlbumSort.NEWEST) }
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AlbumSort.entries) { s -> Chip(s.label, sort == s) { sort = s } }
        }
        LoadContent(rememberLibraryLoad(vm, "albums", library, sort) { vm.albums(library, sort, 500) }, "Альбомов нет") { albums ->
            LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                items(albums, key = { it.id }) { AlbumCard(it) { nav.album(it.id) } }
            }
        }
    }
}

@Composable
fun AlbumScreen(vm: AppViewModel, nav: Nav, id: String) {
    val api = vm.apiFor(id)
    val album = rememberLibraryLoad(vm, "album_info", id) { api.album(id) }
    LoadContent(rememberLibraryLoad(vm, "album", id) { vm.albumTracks(id) }, "В альбоме нет треков") { tracks ->
        val info = (album as? Load.Ok)?.value
        val first = tracks.firstOrNull()
        TrackList(vm, nav, tracks, numbered = true) {
            ListHeader(info?.title ?: first?.album.orEmpty(),
                listOfNotNull(info?.artist ?: first?.artist, info?.year?.toString(), "${tracks.size} треков · ${formatTime(tracks.sumOf { it.duration })}").joinToString(" · "),
                info?.coverUrl ?: first?.coverUrl, vm, tracks) {
                if (info != null) {
                    RoundIconButton(if (info.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Избранное") {
                        vm.star(StarType.ALBUM, info.id, !info.starred)
                    }
                }
            }
            if (info != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    RatingBar(info.rating, 24) { vm.rateAlbum(info.id, it) }
                }
                info.artistId?.let { artistId ->
                    TextButton(onClick = { nav.artist(artistId) }, modifier = Modifier.fillMaxWidth()) { Text("Исполнитель: ${info.artist}") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Artists

@Composable
fun ArtistsScreen(vm: AppViewModel, nav: Nav) {
    val library by vm.library.collectAsState()
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        LoadContent(rememberLibraryLoad(vm, "artists", library) { vm.api(library).artists() }, "Исполнителей нет") { artists ->
            LazyColumn { items(artists, key = { it.id }) { a -> ArtistRow(a) { nav.artist(a.id) } } }
        }
    }
}

@Composable
fun ArtistScreen(vm: AppViewModel, nav: Nav, id: String) {
    val api = vm.apiFor(id)
    LoadContent(rememberLibraryLoad(vm, "artist", id) { api.artist(id) }, isEmpty = { false }) { detail ->
        val a = detail.artist
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(a.coverUrl ?: detail.albums.firstOrNull()?.coverUrl, 160.dp, radius = 80.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(a.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Альбомов: ${detail.albums.size}", color = Palette.subtext)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton("Радио исполнителя", Icons.Default.Radio, { vm.artistRadio(id) })
                        RoundIconButton(if (a.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Избранное") {
                            vm.star(StarType.ARTIST, id, !a.starred)
                        }
                    }
                }
            }
            if (detail.topSongs.isNotEmpty()) {
                item { SectionTitle("Популярные треки") }
                itemsIndexed(detail.topSongs.take(10), key = { i, t -> "top$i${t.id}" }) { i, t ->
                    TrackRow(t, false, onClick = { vm.play(detail.topSongs, i) }, actions = trackActions(vm, nav, t, detail.topSongs))
                }
            }
            item { AlbumShelf("Альбомы", detail.albums, nav) }
            item {
                TextButton(onClick = { nav.to("artist_songs/${Uri.encode(id)}") }, modifier = Modifier.fillMaxWidth()) { Text("Все треки исполнителя") }
            }
        }
    }
}

@Composable
fun ArtistSongsScreen(vm: AppViewModel, nav: Nav, id: String) {
    LoadContent(rememberLibraryLoad(vm, "artist_songs", id) { vm.apiFor(id).artistTracks(id) }, "Треков нет") { tracks ->
        TrackList(vm, nav, tracks) { Column(Modifier.padding(16.dp)) { ListActions(vm, tracks) } }
    }
}

// ---------------------------------------------------------------- Songs, genres, folders

@Composable
fun SongsScreen(vm: AppViewModel, nav: Nav) {
    val library by vm.library.collectAsState()
    var sort by rememberSaveable { mutableStateOf(SongSort.ARTIST) }
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SongSort.entries) { s -> Chip(s.label, sort == s) { sort = s } }
        }
        LoadContent(rememberLibraryLoad(vm, "songs", library, sort) { vm.api(library).songs(sort, 2000) }, "Треков нет") { tracks ->
            TrackList(vm, nav, tracks) {
                Column(Modifier.padding(16.dp)) {
                    Text("Треков: ${tracks.size}", color = Palette.subtext)
                    Spacer(Modifier.height(8.dp))
                    ListActions(vm, tracks)
                }
            }
        }
    }
}

@Composable
fun GenresScreen(vm: AppViewModel, nav: Nav) {
    val library by vm.library.collectAsState()
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        LoadContent(rememberLoad("genres", library) { vm.api(library).genres() }, "Жанров нет") { genres ->
            LazyColumn {
                items(genres, key = { it.name }) { g ->
                    SimpleRow(Icons.Default.Sell, g.name, "Треков: ${g.songCount} · альбомов: ${g.albumCount}", onClick = { nav.genre(g.name) })
                }
            }
        }
    }
}

@Composable
fun GenreScreen(vm: AppViewModel, nav: Nav, name: String) {
    val library by vm.library.collectAsState()
    LoadContent(rememberLibraryLoad(vm, "genre", name, library) { vm.api(library).genreTracks(name) }, "Треков нет") { tracks ->
        TrackList(vm, nav, tracks) {
            Column(Modifier.padding(16.dp)) {
                Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Треков: ${tracks.size}", color = Palette.subtext)
                Spacer(Modifier.height(8.dp))
                ListActions(vm, tracks)
            }
        }
    }
}

@Composable
fun FoldersScreen(vm: AppViewModel, nav: Nav, id: String?) {
    val library by vm.library.collectAsState()
    val api = if (id != null) (if (id.startsWith("/")) vm.phone else vm.api(library)) else vm.api(library)
    Column(Modifier.fillMaxSize()) {
        if (id == null) LibrarySwitch(vm)
        LoadContent(rememberLibraryLoad(vm, "folder", id, library) { api.folder(id) }, isEmpty = { it.folders.isEmpty() && it.tracks.isEmpty() }) { f ->
            TrackList(vm, nav, f.tracks) {
                if (id != null) Text(f.name, Modifier.padding(16.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                f.folders.forEach { (folderId, name) -> SimpleRow(Icons.Default.Folder, name, onClick = { nav.folder(folderId) }) }
                if (f.tracks.isNotEmpty()) Column(Modifier.padding(16.dp)) { ListActions(vm, f.tracks) }
            }
        }
    }
}

// ---------------------------------------------------------------- Favorites

@Composable
fun FavoritesScreen(vm: AppViewModel, nav: Nav) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Треки", "Альбомы", "Исполнители").forEachIndexed { i, label -> Chip(label, tab == i) { tab = i } }
        }
        LoadContent(rememberLibraryLoad(vm, "starred") { vm.starred() }, isEmpty = { false }) { s ->
            when (tab) {
                0 -> if (s.tracks.isEmpty()) Empty("Нет избранных треков", Icons.Default.FavoriteBorder) else
                    TrackList(vm, nav, s.tracks) { Column(Modifier.padding(16.dp)) { ListActions(vm, s.tracks) } }
                1 -> if (s.albums.isEmpty()) Empty("Нет избранных альбомов", Icons.Default.FavoriteBorder) else
                    LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                        items(s.albums, key = { it.id }) { AlbumCard(it) { nav.album(it.id) } }
                    }
                else -> if (s.artists.isEmpty()) Empty("Нет избранных исполнителей", Icons.Default.FavoriteBorder) else
                    LazyColumn { items(s.artists, key = { it.id }) { a -> ArtistRow(a) { nav.artist(a.id) } } }
            }
        }
    }
}

// ---------------------------------------------------------------- Playlists

@Composable
fun PlaylistsScreen(vm: AppViewModel, nav: Nav) {
    val library by vm.library.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        Row(Modifier.padding(16.dp, 4.dp)) { PillButton("Новый плейлист", Icons.Default.Add, { creating = true }) }
        LoadContent(rememberLibraryLoad(vm, "playlists", library) { vm.playlists(library) }, "Плейлистов пока нет") { list ->
            LazyColumn {
                items(list, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { nav.playlist(p.id, p.name) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(p.coverUrl, 56.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.count} треков", color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { renaming = p }) { Icon(Icons.Default.Edit, "Переименовать", tint = Palette.subtext) }
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "Удалить", tint = Palette.subtext) }
                    }
                }
            }
        }
    }
    if (creating) NameDialog("Новый плейлист", onDismiss = { creating = false }) { vm.createPlaylist(it); creating = false }
    renaming?.let { p -> NameDialog("Переименовать", p.name, onDismiss = { renaming = null }) { vm.renamePlaylist(p.id, it); renaming = null } }
    deleting?.let { p -> ConfirmDialog("Удалить плейлист «${p.name}»?", onDismiss = { deleting = null }) { vm.deletePlaylist(p.id) } }
}

@Composable
fun PlaylistScreen(vm: AppViewModel, nav: Nav, id: String, name: String) {
    LoadContent(rememberLibraryLoad(vm, "playlist", id) { vm.playlistTracks(id) }, "Плейлист пуст — добавляйте треки через меню «⋮ → Добавить в плейлист»") { tracks ->
        TrackList(vm, nav, tracks, playlistId = id) {
            ListHeader(name, "${tracks.size} треков · ${formatTime(tracks.sumOf { it.duration })}", tracks.firstOrNull()?.coverUrl, vm, tracks)
        }
    }
}

// ---------------------------------------------------------------- Radio stations

@Composable
fun RadioScreen(vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val api = vm.pc ?: vm.phone
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp, 4.dp)) { PillButton("Добавить станцию", Icons.Default.Add, { adding = true }) }
        LoadContent(rememberLibraryLoad(vm, "radio") { api.radioStations() }, "Станций нет. Добавьте ссылку на поток интернет-радио, Twitch-канал или видео YouTube.") { list ->
            LazyColumn {
                items(list, key = { it.id }) { s ->
                    SimpleRow(Icons.Default.Radio, s.name, s.url, onClick = { vm.playStation(s.name, s.url) }) {
                        IconButton(onClick = { vm.deleteRadioStation(s.id) }) { Icon(Icons.Default.Delete, "Удалить", tint = Palette.subtext) }
                    }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Новая станция") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("Ссылка на поток") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { if (url.isNotBlank()) { vm.addRadioStation(name.ifBlank { url }, url.trim()); adding = false } }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun LibraryIcon() = Icon(Icons.Default.LibraryMusic, null)
