package com.tesuta.tesuplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tesuta.tesuplayer.BuildConfig
import com.tesuta.tesuplayer.data.Album
import com.tesuta.tesuplayer.data.DownloadState
import com.tesuta.tesuplayer.data.Playlist
import com.tesuta.tesuplayer.data.RemoteDevice
import com.tesuta.tesuplayer.data.SearchResult
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.Streams
import com.tesuta.tesuplayer.data.Track
import kotlinx.coroutines.launch

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

private fun isPcTrack(t: Track) = t.source == Source.SERVER && !t.id.startsWith("local:")

@Composable
fun trackActions(vm: AppViewModel, track: Track, list: List<Track>): List<TrackAction> {
    val downloaded = vm.downloads.items.collectAsState().value.any { it.id == track.id }
    val hasPc = vm.server.collectAsState().value != null
    return buildList {
        add(TrackAction("Играть") { vm.play(list, list.indexOf(track)) })
        add(TrackAction("В очередь") { vm.player.enqueue(vm.downloads.items.value.find { it.id == track.id } ?: track) })
        val downloadable = track.source != Source.TWITCH && !track.id.startsWith("local:")
        if (downloadable && !downloaded) {
            add(TrackAction(if (track.source == Source.YOUTUBE) "Скачать как музыку" else "Скачать на телефон") { vm.download(listOf(track)) })
        }
        if (downloaded) add(TrackAction("Удалить загрузку") { vm.downloads.items.value.find { it.id == track.id }?.let(vm.downloads::delete) })
        if (isPcTrack(track) && hasPc) {
            add(TrackAction("В избранное") { vm.star(track, true) })
            add(TrackAction("Убрать из избранного") { vm.star(track, false) })
        }
        if (track.source == Source.YOUTUBE && hasPc && track.pageUrl != null) {
            add(TrackAction("★ Сохранить ссылку") { vm.addFavorite(track.title, track.pageUrl) })
        }
    }
}

@Composable
fun TrackList(vm: AppViewModel, tracks: List<Track>, numbered: Boolean = false, header: @Composable () -> Unit = {}) {
    val saved = vm.downloads.items.collectAsState().value.map { it.id }.toSet()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { header() }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
            TrackRow(t, t.id in saved, onClick = { vm.play(tracks, i) }, actions = trackActions(vm, t, tracks),
                index = if (numbered) i + 1 else null)
        }
    }
}

@Composable
fun ListHeader(title: String, subtitle: String, cover: String?, vm: AppViewModel, tracks: List<Track>) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(cover, 200.dp, radius = 18.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Palette.subtext)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Играть", Icons.Default.PlayArrow, { vm.play(tracks) })
            PillButton("Вперемешку", Icons.Default.Shuffle, { vm.play(tracks, shuffle = true) }, primary = false)
            if (tracks.any(::isPcTrack)) PillButton("Скачать", Icons.Default.Download, { vm.download(tracks) }, primary = false)
        }
    }
}

@Composable
fun LibrarySwitch(vm: AppViewModel) {
    val library by vm.library.collectAsState()
    if (vm.server.collectAsState().value == null) return
    Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("На телефоне", library == Library.PHONE) { vm.setLibrary(Library.PHONE) }
        Chip("На компьютере", library == Library.PC) { vm.setLibrary(Library.PC) }
    }
}

// ---------------------------------------------------------------- Library

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Cover(album.coverUrl, 160.dp, radius = 14.dp)
        Spacer(Modifier.height(6.dp))
        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(160.dp))
        Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.subtext,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(160.dp))
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, openAlbum: (String) -> Unit) {
    val library by vm.library.collectAsState()
    Column(Modifier.fillMaxSize()) {
        LibrarySwitch(vm)
        when (val s = rememberLoad("albums", library) { vm.albums(library) }) {
            Load.Loading -> Loading()
            is Load.Fail -> Empty("Не удалось загрузить альбомы:\n${s.message}")
            is Load.Ok -> if (s.value.isEmpty()) {
                Empty(
                    if (library == Library.PHONE) "На телефоне пока нет музыки.\nНайдите песню в «Поиске» на YouTube и скачайте её, или подключите свой компьютер в настройках."
                    else "На компьютере пока нет музыки",
                )
            } else {
                LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                    items(s.value, key = { it.id }) { AlbumCard(it) { openAlbum(it.id) } }
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(vm: AppViewModel, id: String) {
    when (val s = rememberLoad(id) { vm.albumTracks(id) }) {
        Load.Loading -> Loading()
        is Load.Fail -> Empty(s.message)
        is Load.Ok -> {
            val first = s.value.firstOrNull()
            TrackList(vm, s.value, numbered = !id.startsWith("local:")) {
                ListHeader(first?.album.orEmpty(), first?.artist.orEmpty(), first?.coverUrl, vm, s.value)
            }
        }
    }
}

@Composable
fun PlaylistsScreen(vm: AppViewModel, open: (String, String) -> Unit) {
    if (vm.server.collectAsState().value == null) {
        Empty("Плейлисты и избранное хранятся на компьютере.\nПодключите TesuPlayer на ПК в настройках.", Icons.AutoMirrored.Filled.QueueMusic)
        return
    }
    when (val s = rememberLoad("playlists") { listOf(Playlist(FAVORITES_ID, "★ Избранное")) + vm.playlists() }) {
        Load.Loading -> Loading()
        is Load.Fail -> Empty(s.message)
        is Load.Ok -> LazyColumn(Modifier.fillMaxSize()) {
            items(s.value, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { open(p.id, p.name) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(p.coverUrl, 56.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        if (p.id != FAVORITES_ID) Text("${p.count} треков", color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(vm: AppViewModel, id: String, name: String) {
    when (val s = rememberLoad(id) { if (id == FAVORITES_ID) vm.favorites() else vm.playlistTracks(id) }) {
        Load.Loading -> Loading()
        is Load.Fail -> Empty(s.message)
        is Load.Ok -> TrackList(vm, s.value) {
            ListHeader(name, "${s.value.size} треков", s.value.firstOrNull()?.coverUrl, vm, s.value)
        }
    }
}

// ---------------------------------------------------------------- Search: phone / computer / YouTube + Twitch

private enum class Where(val label: String) { ONLINE("YouTube + Twitch"), PHONE("Телефон"), PC("Компьютер") }

private val TWITCH_LOGIN = Regex("^[a-zA-Z0-9_]{3,25}$")

@Composable
fun SearchScreen(vm: AppViewModel, openAlbum: (String) -> Unit) {
    val hasPc = vm.server.collectAsState().value != null
    var query by rememberSaveable { mutableStateOf("") }
    var where by rememberSaveable { mutableStateOf(Where.ONLINE) }
    var submitted by remember { mutableStateOf<Pair<String, Where>?>(null) }
    var favoritesVersion by remember { mutableIntStateOf(0) }

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (where == Where.ONLINE && Streams.sourceOf(q) != null) vm.playLink(q) else submitted = q to where
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(if (where == Where.ONLINE) "YouTube, Twitch-канал или ссылка" else "Песня, альбом, исполнитель") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Where.entries.filter { it != Where.PC || hasPc }.forEach { w ->
                Chip(w.label, where == w) { where = w; if (query.isNotBlank()) submit() }
            }
        }
        Spacer(Modifier.height(8.dp))
        val req = submitted
        if (req == null) {
            if (where == Where.ONLINE) OnlineFavorites(vm, favoritesVersion) else Empty("Введите запрос", Icons.Default.Search)
            return
        }
        val (q, w) = req
        when (val s = rememberLoad(q, w) {
            when (w) {
                Where.ONLINE -> SearchResult(emptyList(), runCatching { Streams.searchYoutube(q) }.getOrElse { e ->
                    vm.toast("YouTube: ${e.message?.lineSequence()?.firstOrNull()}"); emptyList()
                })
                Where.PHONE -> vm.phone.search(q)
                Where.PC -> vm.api(Library.PC).search(q)
            }
        }) {
            Load.Loading -> Loading()
            is Load.Fail -> Empty("Ошибка поиска:\n${s.message}")
            is Load.Ok -> TrackList(vm, s.value.tracks) {
                if (w == Where.ONLINE && TWITCH_LOGIN.matches(q.removePrefix("@"))) {
                    TwitchChannelCard(vm, q.removePrefix("@")) { favoritesVersion++ }
                }
                if (s.value.albums.isNotEmpty()) {
                    SectionTitle("Альбомы")
                    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                        items(s.value.albums, key = { it.id }) { AlbumCard(it) { openAlbum(it.id) } }
                    }
                    SectionTitle("Треки")
                }
                if (w == Where.ONLINE && s.value.tracks.isNotEmpty()) SectionTitle("YouTube")
            }
        }
    }
}

/** Checks the channel with yt-dlp: live or not, and what is streaming */
@Composable
private fun TwitchChannelCard(vm: AppViewModel, login: String, onSaved: () -> Unit) {
    val info = rememberLoad("tw", login.lowercase()) { Streams.resolve(Streams.twitchUrl(login)) }
    val hasPc = vm.server.collectAsState().value != null
    Surface(color = Palette.surface0, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover((info as? Load.Ok)?.value?.thumbnail, 56.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Twitch: ${(info as? Load.Ok)?.value?.artist ?: login}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    when (info) {
                        Load.Loading -> "проверяю…"
                        is Load.Ok -> "● в эфире · ${info.value.title}"
                        is Load.Fail -> if (info.message.contains("not currently live", true)) "не в эфире" else "канал не найден"
                    },
                    color = if (info is Load.Ok) Palette.red else Palette.subtext,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { vm.playTwitch(login) }, enabled = info !is Load.Fail) { Icon(Icons.Default.PlayArrow, "Play") }
            IconButton(onClick = { vm.addTwitch(login); if (hasPc) vm.addFavorite("Twitch: $login", Streams.twitchUrl(login)); onSaved() }) {
                Icon(Icons.Default.StarBorder, "Save")
            }
        }
    }
}

/** Saved channels and videos; with a computer they are its radio stations, shared with the desktop */
@Composable
private fun OnlineFavorites(vm: AppViewModel, version: Int) {
    val local by vm.twitchChannels.collectAsState()
    val hasPc = vm.server.collectAsState().value != null
    var reload by remember { mutableIntStateOf(0) }
    val remote = rememberLoad("favs", hasPc, version, reload) { if (hasPc) vm.pc?.stations().orEmpty() else emptyList() }
    val remoteList = (remote as? Load.Ok)?.value.orEmpty()
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("★ Избранное") }
        if (remoteList.isEmpty() && local.isEmpty()) {
            item {
                Text("Найдите Twitch-канал или видео и нажмите ☆ — они появятся здесь" +
                    if (hasPc) " и в «Радиостанциях» на компьютере." else ".",
                    color = Palette.subtext, modifier = Modifier.padding(16.dp, 0.dp))
            }
        }
        items(remoteList, key = { "r" + it.id }) { f ->
            FavoriteRow(f.name, Streams.sourceOf(f.url) == Source.TWITCH, onPlay = { vm.playLink(f.url) }) {
                scope.launch { runCatching { vm.pc?.deleteStation(f.id) }; reload++ }
            }
        }
        val remoteTwitch = remoteList.map { it.url.substringAfterLast('/').lowercase() }.toSet()
        items(local.filter { it.lowercase() !in remoteTwitch }, key = { "l$it" }) { c ->
            FavoriteRow("Twitch: $c", true, onPlay = { vm.playTwitch(c) }) { vm.removeTwitch(c) }
        }
    }
}

@Composable
private fun FavoriteRow(name: String, twitch: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (twitch) Icons.Default.LiveTv else Icons.Default.Star, null, tint = Palette.mauve, modifier = Modifier.size(28.dp))
        Text(name, Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Palette.subtext) }
    }
}

// ---------------------------------------------------------------- Where is it playing

@Composable
fun DevicesScreen(vm: AppViewModel) {
    val devices by vm.devices.collectAsState()
    val reachable by vm.pcReachable.collectAsState()
    if (vm.server.collectAsState().value == null) {
        Empty("Подключите компьютер с TesuPlayer в настройках — здесь будет видно, где сейчас играет музыка.", Icons.Default.Devices)
        return
    }
    if (devices.isEmpty()) {
        Empty(if (reachable) "Других устройств сейчас нет" else "Компьютер недоступен", Icons.Default.Devices)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(devices, key = { it.id }) { DeviceCard(vm, it) }
    }
}

@Composable
private fun DeviceCard(vm: AppViewModel, d: RemoteDevice) {
    Surface(color = Palette.surface0, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneAndroid, null, tint = Palette.mauve)
                Text(d.name, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold)
                Text(when (d.state) { "playing" -> "● играет"; "paused" -> "пауза"; else -> "тихо" },
                    color = if (d.state == "playing") Palette.green else Palette.subtext)
            }
            Spacer(Modifier.height(8.dp))
            val t = d.track
            if (t != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(t.coverUrl, 48.dp, radius = 8.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.artist} · ${formatTime(d.position.toLong()).ifEmpty { "0:00" }}",
                            color = Palette.subtext, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            } else {
                Text("Ничего не играет", color = Palette.subtext)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.sendCommand(d, "previous") }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                IconButton(onClick = { vm.sendCommand(d, if (d.state == "playing") "pause" else "play") }) {
                    Icon(if (d.state == "playing") Icons.Default.Pause else Icons.Default.PlayArrow, "Play")
                }
                IconButton(onClick = { vm.sendCommand(d, "next") }) { Icon(Icons.Default.SkipNext, "Next") }
                Spacer(Modifier.weight(1f))
                if (t != null) TextButton(onClick = { vm.playHere(d) }) { Text("Слушать здесь") }
            }
        }
    }
}

// ---------------------------------------------------------------- Downloads

@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val items by vm.downloads.items.collectAsState()
    LaunchedEffect(Unit) { vm.downloads.refresh() }
    if (items.isEmpty()) {
        Empty("Здесь будут скачанные треки — они играют без интернета.\nМузыка с YouTube сохраняется в «Музыка/TesuPlayer».", Icons.Default.Download)
        return
    }
    val ready = items.filter { vm.downloads.state(it) == DownloadState.DONE }
    TrackList(vm, items) {
        Column(Modifier.padding(16.dp)) {
            Text("${ready.size} из ${items.size} готово", color = Palette.subtext)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Играть всё", Icons.Default.PlayArrow, { vm.player.play(ready) })
                PillButton("Вперемешку", Icons.Default.Shuffle, { vm.player.play(ready, shuffle = true) }, primary = false)
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val server by vm.server.collectAsState()
    val reachable by vm.pcReachable.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ytdlp by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ytdlp = Streams.version(context) }

    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("tesu") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle("Мой компьютер")
            val s = server
            if (s != null) {
                Text("${s.url}\n${s.username} · ${if (reachable) "на связи" else "не отвечает"}", color = Palette.subtext)
                Spacer(Modifier.height(8.dp))
                PillButton("Отключить", Icons.Default.Delete, { vm.disconnect() }, primary = false)
            } else {
                Text(
                    "Подключите TesuPlayer на ПК, чтобы слушать его музыку, синхронизировать избранное и плейлисты и видеть, где сейчас играет музыка.\n" +
                        "На ПК: Настройки → Моя музыка → «Доступ с других устройств».",
                    color = Palette.subtext,
                )
                OutlinedTextField(url, { url = it }, label = { Text("Адрес") }, placeholder = { Text("192.168.1.10:45173") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PillButton(if (busy) "Подключаюсь…" else "Подключить", Icons.Default.PlayArrow, {
                    if (!busy && url.isNotBlank()) scope.launch {
                        busy = true
                        vm.toast(runCatching { vm.connect(url, user, password) }.fold({ "Компьютер подключён" }, { "Не удалось: ${it.message}" }))
                        busy = false
                    }
                })
            }
        }
        item {
            SectionTitle("YouTube и Twitch")
            Text("yt-dlp: ${ytdlp ?: "готовится…"}", color = Palette.subtext)
            Spacer(Modifier.height(8.dp))
            PillButton(if (updating) "Обновляю…" else "Обновить yt-dlp", Icons.Default.Download, {
                if (!updating) scope.launch {
                    updating = true
                    vm.toast(runCatching { Streams.update(context) }.fold({ "yt-dlp: $it" }, { "Ошибка: ${it.message}" }))
                    ytdlp = Streams.version(context)
                    updating = false
                }
            }, primary = false)
            Text("Если YouTube перестал играть — обновите yt-dlp.", color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        }
        item {
            SectionTitle("О приложении")
            Text("TesuPlayer ${BuildConfig.VERSION_NAME}\nФорк Feishin (github.com/jeffvli/feishin), GPL-3.0", color = Palette.subtext)
        }
    }
}
