package com.tesuta.tesuplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tesuta.tesuplayer.data.Source
import com.tesuta.tesuplayer.data.Track

class TrackAction(val label: String, val onClick: () -> Unit)

@Composable
fun TrackRow(
    track: Track,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: List<TrackAction>,
    index: Int? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("$index", color = Palette.subtext, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        } else {
            Cover(track.coverUrl, 48.dp, radius = 8.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(
                when (track.source) { Source.YOUTUBE -> "YouTube"; Source.TWITCH -> "Twitch"; else -> null },
                track.artist.ifBlank { null },
                formatTime(track.duration).ifBlank { null },
            ).filterNotNull().joinToString(" · ")
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Palette.subtext)
        }
        if (downloaded) Icon(Icons.Default.DownloadDone, "Downloaded", tint = Palette.green, modifier = Modifier.size(18.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Palette.subtext) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                actions.forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = false; a.onClick() })
                }
            }
        }
    }
}

@Composable
fun PillButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        colors = if (primary) ButtonDefaults.buttonColors() else
            ButtonDefaults.buttonColors(containerColor = Palette.surface0, contentColor = Palette.text),
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun Empty(text: String, icon: ImageVector = Icons.Default.CloudOff) = Box(
    Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Palette.surface1, modifier = Modifier.size(56.dp))
        Text(text, color = Palette.subtext, textAlign = TextAlign.Center)
    }
}

@Composable
fun OfflineBanner() = Row(
    Modifier.fillMaxWidth().background(Palette.surface0).padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(Icons.Default.CloudOff, null, tint = Palette.pink, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(8.dp))
    Text("Офлайн-режим: играют только скачанные треки", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun SectionTitle(text: String) = Text(
    text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
)

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier
        .clip(RoundedCornerShape(50))
        .background(if (selected) Palette.mauve else Palette.surface0)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp),
) {
    Text(text, color = if (selected) Palette.mantle else Palette.text, fontWeight = FontWeight.SemiBold)
}
