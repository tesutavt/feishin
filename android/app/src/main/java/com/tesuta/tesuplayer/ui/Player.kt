package com.tesuta.tesuplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.StarType
import com.tesuta.tesuplayer.player.PlayerController
import com.tesuta.tesuplayer.player.PlayerUi

@Composable
fun MiniPlayer(state: PlayerUi, player: PlayerController, onOpen: () -> Unit) {
    Surface(color = Palette.mantle, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            if (state.duration > 0 && !state.isLive) {
                LinearProgressIndicator(
                    progress = { (state.position.toFloat() / state.duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Palette.mauve,
                    trackColor = Palette.surface0,
                )
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(state.artwork?.toString(), 44.dp, radius = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(state.title.ifBlank { "..." }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.isLive) "LIVE · ${state.artist}" else state.artist,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Palette.subtext,
                    )
                }
                if (state.loading || state.isBuffering) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = player::toggle) {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play")
                    }
                }
                IconButton(onClick = player::next, enabled = state.hasNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

private enum class PlayerTab(val label: String) { COVER("Обложка"), LYRICS("Текст"), QUEUE("Очередь"), SIMILAR("Похожие") }

@Composable
fun FullPlayer(state: PlayerUi, vm: AppViewModel, nav: Nav, onClose: () -> Unit) {
    val player = vm.player
    var tab by rememberSaveable { mutableStateOf(PlayerTab.COVER) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var timerDialog by remember { mutableStateOf(false) }
    var speedDialog by remember { mutableStateOf(false) }
    val track = state.track
    val libraryTrack = track != null && track.source == Source.SERVER && !track.id.startsWith("radio:")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.surface0, Palette.base, Palette.mantle)))
            .clickable(enabled = false) {},
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Spacer(Modifier.weight(1f))
                PlayerTab.entries.forEach { t ->
                    Text(
                        t.label,
                        color = if (tab == t) Palette.mauve else Palette.subtext,
                        fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { tab = t }.padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                when (tab) {
                    PlayerTab.COVER -> Box(
                        Modifier.fillMaxWidth(0.92f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Palette.surface0),
                        contentAlignment = Alignment.Center,
                    ) {
                        Cover(null, 120.dp)
                        AsyncImage(model = state.artwork, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    PlayerTab.LYRICS -> LyricsView(vm, state)
                    PlayerTab.QUEUE -> QueueView(state, player)
                    PlayerTab.SIMILAR -> SimilarView(vm, nav, state)
                }
            }

            Text(state.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth())
            Text(state.artist, color = Palette.subtext, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(enabled = track?.artistId != null && libraryTrack) {
                    track?.artistId?.let { onClose(); nav.artist(it) }
                })

            // favourite, rating, playlist, timer, speed, info — the desktop player bar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (libraryTrack && track != null) {
                    IconButton(onClick = { vm.star(StarType.SONG, track.id, !track.starred) }) {
                        Icon(if (track.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Избранное",
                            tint = if (track.starred) Palette.pink else Palette.subtext)
                    }
                    RatingBar(track.rating, 20) { vm.rate(track, it) }
                    IconButton(onClick = { vm.addToPlaylist.value = listOf(track) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "В плейлист", tint = Palette.subtext)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { timerDialog = true }) {
                    Icon(Icons.Default.Bedtime, "Таймер сна", tint = if (state.sleepAt != null) Palette.mauve else Palette.subtext)
                }
                if (!state.isLive) {
                    IconButton(onClick = { speedDialog = true }) {
                        Icon(Icons.Default.Speed, "Скорость", tint = if (state.speed != 1f) Palette.mauve else Palette.subtext)
                    }
                }
                if (track != null) IconButton(onClick = { vm.trackInfo.value = track }) { Icon(Icons.Default.Info, "Информация", tint = Palette.subtext) }
            }

            if (state.isLive) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.red))
                    Spacer(Modifier.width(8.dp))
                    Text("Прямой эфир", color = Palette.red, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = player::goLive) { Text("● Вернуться в эфир", color = Palette.red) }
                }
            } else {
                val progress = if (state.duration > 0) state.position.toFloat() / state.duration else 0f
                Slider(
                    value = if (dragging) dragValue else progress.coerceIn(0f, 1f),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { player.seekTo((dragValue * state.duration).toLong()); dragging = false },
                    colors = SliderDefaults.colors(thumbColor = Palette.mauve, activeTrackColor = Palette.mauve, inactiveTrackColor = Palette.surface1),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(state.position / 1000).ifEmpty { "0:00" }, color = Palette.subtext, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (state.speed != 1f) Text("${state.speed}×", color = Palette.mauve, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(state.duration / 1000), color = Palette.subtext, fontSize = 12.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (state.shuffle) Palette.mauve else Palette.subtext)
                }
                IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Palette.accent).clickable(onClick = player::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading || state.isBuffering) {
                        CircularProgressIndicator(color = Palette.mantle, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play",
                            tint = Palette.mantle, modifier = Modifier.size(40.dp))
                    }
                }
                IconButton(onClick = player::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp))
                }
                IconButton(onClick = player::cycleRepeat) {
                    Icon(
                        if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                        tint = if (state.repeat != Player.REPEAT_MODE_OFF) Palette.mauve else Palette.subtext,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (timerDialog) SleepTimerDialog(player) { timerDialog = false }
    if (speedDialog) SpeedDialog(player, state.speed) { speedDialog = false }
}

/** Synced lyrics: the current line is highlighted and followed, a tap seeks there */
@Composable
private fun LyricsView(vm: AppViewModel, state: PlayerUi) {
    val track = state.track
    if (track == null || track.source == Source.TWITCH) {
        Text("Для этого трека нет текста", color = Palette.subtext)
        return
    }
    val lyrics = rememberLoad("lyrics", track.id) { vm.lyrics(track) }
    when (lyrics) {
        Load.Loading -> CircularProgressIndicator()
        is Load.Fail -> Text("Текст не найден", color = Palette.subtext)
        is Load.Ok -> {
            val lines = lyrics.value
            if (lines == null) {
                Text(if (vm.prefs.lyricsEnabled) "Текст не найден" else "Тексты выключены в настройках", color = Palette.subtext)
                return
            }
            val synced = lines.first().timeMs != null
            val current = if (synced) lines.indexOfLast { (it.timeMs ?: 0) <= state.position + 300 } else -1
            val listState = rememberLazyListState()
            LaunchedEffect(current) {
                if (synced && current >= 0 && vm.prefs.lyricsFollow) listState.animateScrollToItem((current - 3).coerceAtLeast(0))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 24.dp)) {
                itemsIndexed(lines) { i, line ->
                    Text(
                        line.text.ifBlank { "♪" },
                        fontSize = if (i == current) 22.sp else 19.sp,
                        fontWeight = if (i == current) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            !synced -> Palette.text
                            i == current -> Palette.text
                            i < current -> Palette.subtext
                            else -> Palette.surface1.copy(alpha = 1f).let { Palette.subtext.copy(alpha = 0.6f) }
                        },
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = synced) { line.timeMs?.let(vm.player::seekTo) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** The queue: jump, move up / down, remove, shuffle the rest, clear */
@Composable
private fun QueueView(state: PlayerUi, player: PlayerController) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("В очереди: ${state.queue.size}", color = Palette.subtext, modifier = Modifier.weight(1f))
            TextButton(onClick = player::shuffleUpcoming) { Text("Перемешать") }
            TextButton(onClick = player::clearUpcoming) { Text("Очистить") }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(Unit) { listState.scrollToItem(state.index.coerceAtLeast(0)) }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(state.queue, key = { i, t -> "$i:${t.id}" }) { i, t ->
                val current = i == state.index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (current) Palette.surface0 else Palette.base.copy(alpha = 0f))
                        .clickable { player.skipTo(i) }.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(t.title, color = if (current) Palette.mauve else Palette.text, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = Palette.subtext, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    IconButton(onClick = { player.move(i, i - 1) }, enabled = i > 0, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Выше", tint = Palette.subtext)
                    }
                    IconButton(onClick = { player.move(i, i + 1) }, enabled = i < state.queue.size - 1, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Ниже", tint = Palette.subtext)
                    }
                    IconButton(onClick = { player.remove(i) }, enabled = !current, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Убрать", tint = Palette.subtext)
                    }
                }
            }
        }
    }
}

/** Similar songs for the current track (desktop full-screen "Similar") */
@Composable
private fun SimilarView(vm: AppViewModel, nav: Nav, state: PlayerUi) {
    val track = state.track
    if (track == null || track.source == Source.TWITCH) {
        Text("Похожих нет", color = Palette.subtext)
        return
    }
    val similar = rememberLoad("similar", track.id) { vm.similar(track) }
    LoadContent(similar, "Похожих треков не нашлось") { list ->
        TrackList(vm, nav, list) {
            Row(Modifier.padding(8.dp)) { PillButton("Играть всё", Icons.Default.PlayArrow, { vm.play(list) }) }
        }
    }
}
