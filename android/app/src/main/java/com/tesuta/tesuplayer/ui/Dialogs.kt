package com.tesuta.tesuplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tesuta.tesuplayer.data.Playlist
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.StarType
import com.tesuta.tesuplayer.data.Track
import com.tesuta.tesuplayer.player.PlayerController

/** 1-5 stars like the desktop rating; tapping the current value clears it */
@Composable
fun RatingBar(rating: Int, size: Int = 28, onRate: (Int) -> Unit) {
    Row {
        for (i in 1..5) {
            Icon(
                if (i <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                "$i",
                tint = if (i <= rating) Palette.pink else Palette.surface1,
                modifier = Modifier.size(size.dp).clickable { onRate(if (i == rating) 0 else i) },
            )
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onDone(name.trim()) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    text = { Text(text) },
    confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Да") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Нет") } },
)

/** "Добавить в плейлист": existing playlists of the track's library, or a new one */
@Composable
fun AddToPlaylistDialog(vm: AppViewModel) {
    val tracks by vm.addToPlaylist.collectAsState()
    val list = tracks ?: return
    var creating by remember { mutableStateOf(false) }
    val playlists = rememberLoad(list.firstOrNull()?.id) { vm.playlistsFor(list) }
    if (creating) {
        NameDialog("Новый плейлист", onDismiss = { creating = false }) { name ->
            vm.createPlaylist(name, list); creating = false; vm.addToPlaylist.value = null
        }
        return
    }
    AlertDialog(
        onDismissRequest = { vm.addToPlaylist.value = null },
        title = { Text(if (list.size == 1) "В плейлист: ${list.first().title}" else "В плейлист: ${list.size} треков") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = Palette.mauve)
                        Text("Новый плейлист", Modifier.padding(start = 12.dp), color = Palette.mauve, fontWeight = FontWeight.SemiBold)
                    }
                }
                when (playlists) {
                    is Load.Ok -> items(playlists.value, key = { it.id }) { p: Playlist ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.addTracksToPlaylist(p, list); vm.addToPlaylist.value = null }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = Palette.subtext)
                            Text(p.name, Modifier.padding(start = 12.dp).weight(1f))
                            Text("${p.count}", color = Palette.subtext)
                        }
                    }
                    is Load.Fail -> item { Text(playlists.message, color = Palette.red) }
                    Load.Loading -> item { Text("Загружаю…", color = Palette.subtext) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.addToPlaylist.value = null }) { Text("Закрыть") } },
    )
}

/** "Информация" with the rating and favourite, like the desktop Get Info */
@Composable
fun TrackInfoDialog(vm: AppViewModel) {
    val track by vm.trackInfo.collectAsState()
    val t = track ?: return
    var rating by remember(t.id) { mutableStateOf(t.rating) }
    var starred by remember(t.id) { mutableStateOf(t.starred) }
    AlertDialog(
        onDismissRequest = { vm.trackInfo.value = null },
        title = { Text(t.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val rows = listOf(
                    "Исполнитель" to t.artist, "Альбом" to t.album, "Жанр" to t.genre.orEmpty(),
                    "Год" to (t.year?.toString() ?: ""), "Номер" to (t.trackNumber?.toString() ?: ""),
                    "Длительность" to formatTime(t.duration), "Битрейт" to (t.bitRate?.let { "$it kbps" } ?: ""),
                    "Прослушиваний" to (if (t.playCount > 0) "${t.playCount}" else ""),
                    "Источник" to when { t.source == Source.YOUTUBE -> "YouTube"; t.source == Source.TWITCH -> "Twitch"; t.id.startsWith("local") -> "Телефон"; else -> "Компьютер" },
                    "Файл" to (t.path ?: t.pageUrl ?: ""),
                ).filter { it.second.isNotBlank() }
                rows.forEach { (k, v) ->
                    Row { Text("$k: ", color = Palette.subtext); Text(v, style = MaterialTheme.typography.bodyMedium) }
                }
                if (t.source == Source.SERVER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RatingBar(rating) { rating = it; vm.rate(t, it) }
                        TextButton(onClick = { starred = !starred; vm.star(StarType.SONG, t.id, starred) }) {
                            Text(if (starred) "★ В избранном" else "☆ В избранное")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.trackInfo.value = null }) { Text("Закрыть") } },
    )
}

@Composable
fun SleepTimerDialog(player: PlayerController, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Таймер сна") },
    text = {
        Column {
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                Text("Через $m мин", Modifier.fillMaxWidth().clickable { player.sleepIn(m); onDismiss() }.padding(vertical = 10.dp))
            }
            Text("В конце трека", Modifier.fillMaxWidth().clickable { player.sleepIn(-1); onDismiss() }.padding(vertical = 10.dp))
            Text("Выключить", Modifier.fillMaxWidth().clickable { player.sleepIn(null); onDismiss() }.padding(vertical = 10.dp), color = Palette.red)
        }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
)

@Composable
fun SpeedDialog(player: PlayerController, current: Float, onDismiss: () -> Unit) {
    var speed by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скорость: ${"%.2f".format(speed)}×") },
        text = {
            Column {
                // "Микротональные настройки высоты тона": 0.01× steps instead of 0.05×
                val steps = if (com.tesuta.tesuplayer.data.S.microtonalPitch) 100 else 20
                Slider(value = speed, onValueChange = { speed = Math.round(it * steps) / steps.toFloat() }, valueRange = 0.5f..2f)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f).forEach { s -> TextButton(onClick = { speed = s }) { Text("${s}×") } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { player.setSpeed(speed); onDismiss() }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
