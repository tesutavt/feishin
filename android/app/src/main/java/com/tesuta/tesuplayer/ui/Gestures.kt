package com.tesuta.tesuplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

/** What a long press opens: the desktop context menu as a bottom sheet */
data class ActionSheet(val title: String, val subtitle: String, val cover: String?, val actions: List<TrackAction>)

object Sheets {
    val current = MutableStateFlow<ActionSheet?>(null)
    fun open(sheet: ActionSheet) { current.value = sheet }
}

/** The ViewModel and navigation for cards deep in lists (long-press menus) */
val LocalVm = staticCompositionLocalOf<AppViewModel?> { null }
val LocalNav = staticCompositionLocalOf<Nav?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheetHost() {
    val sheet by Sheets.current.collectAsState()
    val s = sheet ?: return
    ModalBottomSheet(
        onDismissRequest = { Sheets.current.value = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.mantle,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(s.cover, 52.dp, radius = 10.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(s.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (s.subtitle.isNotBlank()) {
                        Text(s.subtitle, color = Palette.subtext, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            LazyColumn {
                items(s.actions) { a ->
                    Text(
                        a.label,
                        Modifier.fillMaxWidth().clickable { Sheets.current.value = null; a.onClick() }.padding(20.dp, 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Swipe a track: right plays it next, left adds it to the end of the queue.
 * The row springs back; it never disappears from the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActions(onRight: (() -> Unit)?, onLeft: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onRight == null && onLeft == null) { content(); return }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onRight?.invoke()
                SwipeToDismissBoxValue.EndToStart -> onLeft?.invoke()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // spring back
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = onRight != null,
        enableDismissFromEndToStart = onLeft != null,
        backgroundContent = {
            val right = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(Palette.surface0).padding(horizontal = 20.dp),
                contentAlignment = if (right) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (right) Icons.Default.SkipNext else Icons.AutoMirrored.Filled.QueueMusic, null, tint = Palette.mauve)
                    Text(if (right) "Сыграть следующим" else "В конец очереди", color = Palette.mauve, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        Box(Modifier.background(Palette.base)) { content() }
    }
}
