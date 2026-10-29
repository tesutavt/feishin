package com.tesuta.tesuplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
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

@Composable
fun FullPlayer(state: PlayerUi, player: PlayerController, onClose: () -> Unit) {
    var showQueue by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.surface0, Palette.base, Palette.mantle)))
            .clickable(enabled = false) {},
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Spacer(Modifier.weight(1f))
                Text("Сейчас играет", color = Palette.subtext, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = if (showQueue) Palette.mauve else Palette.text)
                }
            }

            if (showQueue) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
                    itemsIndexed(state.queue) { i, title ->
                        Text(
                            "${i + 1}. $title",
                            color = if (i == state.index) Palette.mauve else Palette.text,
                            fontWeight = if (i == state.index) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { player.skipTo(i) }.padding(vertical = 10.dp),
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(0.3f))
                Box(
                    Modifier.fillMaxWidth(0.88f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Palette.surface0),
                    contentAlignment = Alignment.Center,
                ) {
                    Cover(null, 120.dp)
                    AsyncImage(model = state.artwork, contentDescription = null, modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                Spacer(Modifier.weight(0.3f))
            }

            Text(state.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth())
            Text(state.artist, color = Palette.subtext, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            if (state.isLive) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.red))
                    Spacer(Modifier.width(8.dp))
                    Text("Прямой эфир", color = Palette.red, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = player::goLive) {
                        Text("● Вернуться в эфир", color = Palette.red)
                    }
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
                    Text(formatTime(state.duration / 1000), color = Palette.subtext, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (state.shuffle) Palette.mauve else Palette.subtext)
                }
                IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Palette.accent).clickable(onClick = player::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading || state.isBuffering) {
                        CircularProgressIndicator(color = Palette.mantle, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play",
                            tint = Palette.mantle, modifier = Modifier.size(42.dp))
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
            Spacer(Modifier.height(12.dp))
        }
    }
}
