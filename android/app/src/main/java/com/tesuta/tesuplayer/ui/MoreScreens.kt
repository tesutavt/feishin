package com.tesuta.tesuplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.tesuta.tesuplayer.data.Spotify
import com.tesuta.tesuplayer.data.UiPart
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.tesuta.tesuplayer.BuildConfig
import com.tesuta.tesuplayer.data.DownloadState
import com.tesuta.tesuplayer.data.HomeSection
import com.tesuta.tesuplayer.data.RemoteDevice
import com.tesuta.tesuplayer.data.SearchResult
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.Streams
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Search: phone / computer / YouTube + Twitch

private enum class Where(val label: String) { ONLINE("YouTube + Twitch"), PHONE("Телефон"), PC("Компьютер") }

private val TWITCH_LOGIN = Regex("^[a-zA-Z0-9_]{3,25}$")

@Composable
fun SearchScreen(vm: AppViewModel, nav: Nav) {
    val hasPc = vm.server.collectAsState().value != null
    var query by rememberSaveable { mutableStateOf("") }
    var where by rememberSaveable { mutableStateOf(if (hasPc) Where.PC else Where.PHONE) }
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
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Where.entries.filter { it != Where.PC || hasPc }) { w ->
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
        when (val s = rememberLibraryLoad(vm, q, w) {
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
            is Load.Ok -> TrackList(vm, nav, s.value.tracks) {
                if (w == Where.ONLINE && TWITCH_LOGIN.matches(q.removePrefix("@"))) {
                    TwitchChannelCard(vm, q.removePrefix("@")) { favoritesVersion++ }
                }
                if (s.value.artists.isNotEmpty()) {
                    SectionTitle("Исполнители")
                    s.value.artists.take(5).forEach { a -> ArtistRow(a) { nav.artist(a.id) } }
                }
                if (s.value.albums.isNotEmpty()) {
                    SectionTitle("Альбомы")
                    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                        items(s.value.albums, key = { it.id }) { AlbumCard(it, 140) { nav.album(it.id) } }
                    }
                }
                if (s.value.tracks.isNotEmpty()) SectionTitle(if (w == Where.ONLINE) "YouTube" else "Треки")
                if (s.value.tracks.isEmpty() && s.value.albums.isEmpty() && s.value.artists.isEmpty() && w != Where.ONLINE) {
                    Text("Ничего не нашлось", Modifier.padding(16.dp), color = Palette.subtext)
                }
            }
        }
    }
}

/** Checks the channel with yt-dlp: live or not, and what is streaming */
@Composable
private fun TwitchChannelCard(vm: AppViewModel, login: String, onSaved: () -> Unit) {
    val info = rememberLoad("tw", login.lowercase()) { Streams.resolve(Streams.twitchUrl(login)) }
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
            IconButton(onClick = { vm.addTwitch(login); vm.addFavorite("Twitch: $login", Streams.twitchUrl(login)); onSaved() }) {
                Icon(Icons.Default.StarBorder, "Save")
            }
        }
    }
}

/** Saved channels and videos: radio stations of the library (shared with the desktop) */
@Composable
private fun OnlineFavorites(vm: AppViewModel, version: Int) {
    val local by vm.twitchChannels.collectAsState()
    val api = vm.pc ?: vm.phone
    val remote = rememberLibraryLoad(vm, "favs", version) { api.radioStations().filter { Streams.sourceOf(it.url) != null } }
    val remoteList = (remote as? Load.Ok)?.value.orEmpty()

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("★ Избранное") }
        if (remoteList.isEmpty() && local.isEmpty()) {
            item {
                Text("Найдите Twitch-канал или видео и нажмите ☆ — они появятся здесь и в «Радиостанциях».",
                    color = Palette.subtext, modifier = Modifier.padding(16.dp, 0.dp))
            }
        }
        items(remoteList, key = { "r" + it.id }) { f ->
            FavoriteRow(f.name, Streams.sourceOf(f.url) == Source.TWITCH, onPlay = { vm.playLink(f.url) }) { vm.deleteRadioStation(f.id) }
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
                        Text(if (t.id.isBlank()) "${t.artist} · прямой эфир" else "${t.artist} · ${formatTime(d.position.toLong()).ifEmpty { "0:00" }}",
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
                if (t != null && t.id.isNotBlank()) TextButton(onClick = { vm.playHere(d) }) { Text("Слушать здесь") }
            }
        }
    }
}

// ---------------------------------------------------------------- Downloads

@Composable
fun DownloadsScreen(vm: AppViewModel, nav: Nav) {
    val items by vm.downloads.items.collectAsState()
    LaunchedEffect(Unit) { vm.downloads.refresh() }
    if (items.isEmpty()) {
        Empty("Здесь будут скачанные треки — они играют без интернета.\nМузыка с YouTube сохраняется в «Музыка/TesuPlayer/Исполнитель/Альбом».", Icons.Default.Download)
        return
    }
    val ready = items.filter { vm.downloads.state(it) == DownloadState.DONE }
    TrackList(vm, nav, items) {
        Column(Modifier.padding(16.dp)) {
            Text("${ready.size} из ${items.size} готово", color = Palette.subtext)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Играть всё", Icons.Default.PlayArrow, { vm.player.play(ready) })
                RoundIconButton(Icons.Default.Shuffle, "Вперемешку") { vm.player.play(ready, shuffle = true) }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingSwitch(title: String, description: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().clickable { on = !on; onChange(on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, nav: Nav) {
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
    val prefs = vm.prefs

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            SectionTitle("Воспроизведение")
            SettingSwitch("Auto-DJ", "Когда очередь заканчивается — добавлять похожие треки (для YouTube — YouTube Mix)", prefs.autoDj) { prefs.autoDj = it }
            SettingSwitch("Скачивать то, что слушаю", "YouTube-треки сохраняются на телефон в фоне, пока играют: в следующий раз - без интернета", prefs.autoDownload) { prefs.autoDownload = it }
            SettingSwitch("Учитывать прослушивания", "Считать прослушивания и «недавно игранные» (на ПК — синхронно)", prefs.scrobble) { prefs.scrobble = it }
            Row(Modifier.fillMaxWidth().clickable { nav.to("equalizer") }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Equalizer, null, tint = Palette.mauve)
                Text("Эквалайзер", Modifier.padding(start = 12.dp).weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (prefs.equalizerEnabled) "вкл" else "выкл", color = Palette.subtext)
            }
        }
        item { LookSettings(vm) }
        item { SpotifySettings(vm) }
        item {
            SectionTitle("Тексты песен")
            SettingSwitch("Получать тексты песен", "Из тегов файлов и с lrclib.net", prefs.lyricsEnabled) { prefs.lyricsEnabled = it }
            SettingSwitch("Следовать за текстом", "Прокручивать текст до текущей строки", prefs.lyricsFollow) { prefs.lyricsFollow = it }
        }
        item {
            SectionTitle("Главная")
            val sections = remember { mutableStateListOf<HomeSection>().apply { addAll(prefs.homeSections) } }
            HomeSection.entries.forEach { s ->
                SettingSwitch(s.label, "Показывать на главной", s in sections) { on ->
                    if (on) sections.add(s) else sections.remove(s)
                    prefs.homeSections = HomeSection.entries.filter { it in sections }
                }
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
            Text("Если YouTube перестал играть — обновите yt-dlp. Если не ищет — включите «Частный DNS» (dns.google) в настройках Android.",
                color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        }
        item {
            SectionTitle("О приложении")
            Text("TesuPlayer ${BuildConfig.VERSION_NAME}\nФорк Feishin (github.com/jeffvli/feishin), GPL-3.0", color = Palette.subtext)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Theme, hidden parts of the interface and the visualizer, like the desktop settings */
@Composable
private fun LookSettings(vm: AppViewModel) {
    val prefs = vm.prefs
    val context = LocalContext.current
    var theme by remember { mutableStateOf(AppTheme.entries.firstOrNull { it.name == prefs.theme } ?: AppTheme.MACCHIATO) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Оформление")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AppTheme.entries) { t ->
                Chip(t.label, theme == t) { theme = t; if (t != AppTheme.CUSTOM || prefs.customTheme != null) vm.setTheme(t) }
            }
        }
        if (theme == AppTheme.CUSTOM) {
            val start = (prefs.customTheme ?: "#24273A,#C6A0F6,#CAD3F5").split(',')
            val fields = remember { mutableStateListOf(*start.toTypedArray()) }
            Text("Своя тема из трёх цветов (#RRGGBB): остальные оттенки подбираются сами", color = Palette.subtext,
                style = MaterialTheme.typography.bodySmall)
            listOf("Фон", "Акцент", "Текст").forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val color = runCatching { Color(android.graphics.Color.parseColor(fields[i].trim())) }.getOrNull()
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color ?: Palette.surface0))
                    OutlinedTextField(fields[i], { fields[i] = it }, label = { Text(label) }, singleLine = true,
                        isError = color == null, modifier = Modifier.padding(start = 12.dp).fillMaxWidth())
                }
            }
            PillButton("Применить", Icons.Default.Palette, {
                val custom = fields.joinToString(",") { it.trim() }
                if (Palette.parseCustom(custom) != null) vm.setTheme(AppTheme.CUSTOM, custom) else vm.toast("Неверный цвет")
            })
        }

        SectionTitle("Визуализация")
        val visualizer by vm.visualizer.collectAsState()
        val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.setVisualizer(true) else vm.toast("Без доступа к микрофону Android не даёт читать звук")
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Спектр в плеере", fontWeight = FontWeight.SemiBold)
                Text("Полосы поверх обложки. Android просит доступ к микрофону: приложение слушает только свой звук",
                    color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = visualizer, onCheckedChange = { on ->
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!on || granted) vm.setVisualizer(on) else microphone.launch(Manifest.permission.RECORD_AUDIO)
            })
        }

        SectionTitle("Интерфейс")
        val hidden by vm.hidden.collectAsState()
        UiPart.entries.forEach { part ->
            Row(Modifier.fillMaxWidth().clickable { vm.setHidden(part, part !in hidden) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(part.label, Modifier.weight(1f))
                Switch(checked = part !in hidden, onCheckedChange = { vm.setHidden(part, !it) })
            }
        }
    }
}

/** Spotify playlist / album by link or CSV, no Spotify account */
@Composable
private fun SpotifySettings(vm: AppViewModel) {
    val context = LocalContext.current
    val progress by vm.importProgress.collectAsState()
    val server by vm.server.collectAsState()
    var link by rememberSaveable { mutableStateOf("") }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
        if (text == null) vm.toast("Не удалось прочитать файл") else vm.importSpotify(csv = text, name = name)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Импорт из Spotify")
        Text(
            "Без аккаунта Spotify: ссылка на публичный плейлист или альбом. Треки ищутся в вашей музыке, потом на YouTube. " +
                (if (server != null) "Плейлист появится на компьютере и здесь. " else "") +
                "Больше 100 треков - загрузите CSV из Exportify, TuneMyMusic или Soundiiz.",
            color = Palette.subtext, style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(link, { link = it }, label = { Text("Ссылка Spotify") }, singleLine = true,
            placeholder = { Text("https://open.spotify.com/playlist/…") }, modifier = Modifier.fillMaxWidth())
        val p = progress
        if (p != null) {
            Text(if (p.second > 0) "Ищу треки: ${p.first} из ${p.second}" else "Импортирую…", color = Palette.mauve)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Импортировать", Icons.Default.Download, {
                    if (Spotify.isLink(link)) { vm.importSpotify(url = link.trim()); link = "" } else vm.toast("Это не ссылка Spotify")
                })
                PillButton("Из CSV", Icons.Default.Description, { csv.launch("*/*") }, primary = false)
            }
        }
    }
}

/** System equalizer bands, applied by the playback service */
@Composable
fun EqualizerScreen(vm: AppViewModel) {
    val prefs = vm.prefs
    val info = prefs.equalizerInfo?.split(';')
    if (info == null || info.size < 4) {
        Empty("Эквалайзер появится после первого запуска воспроизведения", Icons.Default.Equalizer)
        return
    }
    val count = info[0].toInt()
    val min = info[1].toInt()
    val max = info[2].toInt()
    val freqs = info[3].split(',')
    val bands = remember { mutableStateListOf<Int>().apply { addAll(List(count) { prefs.equalizerBands.getOrNull(it) ?: 0 }) } }
    var enabled by remember { mutableStateOf(prefs.equalizerEnabled) }
    val presets = mapOf(
        "Ровно" to List(count) { 0 },
        "Бас" to List(count) { i -> if (i < count / 3) max / 2 else 0 },
        "Голос" to List(count) { i -> if (i in count / 3..count * 2 / 3) max / 3 else -max / 6 },
        "Высокие" to List(count) { i -> if (i > count * 2 / 3) max / 2 else 0 },
    )
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Эквалайзер", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.equalizerEnabled = it })
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(presets.keys.toList()) { name ->
                    Chip(name, false) { presets[name]!!.forEachIndexed { i, v -> bands[i] = v }; prefs.equalizerBands = bands.toList() }
                }
            }
        }
        items(count) { i ->
            Column {
                val hz = freqs.getOrNull(i)?.toIntOrNull() ?: 0
                Text("${if (hz >= 1000) "${hz / 1000} кГц" else "$hz Гц"}: ${"%+.1f".format(bands[i] / 100f)} дБ", color = Palette.subtext)
                Slider(
                    value = bands[i].toFloat(), valueRange = min.toFloat()..max.toFloat(),
                    onValueChange = { bands[i] = it.toInt() },
                    onValueChangeFinished = { prefs.equalizerBands = bands.toList() },
                    enabled = enabled,
                )
            }
        }
        item { Spacer(Modifier.width(1.dp)) }
    }
}
