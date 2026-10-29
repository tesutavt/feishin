package com.tesuta.tesuplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

// Catppuccin Macchiato, same palette as the app icon
object Palette {
    val base = Color(0xFF24273A)
    val mantle = Color(0xFF1E2030)
    val surface0 = Color(0xFF363A4F)
    val surface1 = Color(0xFF494D64)
    val text = Color(0xFFCAD3F5)
    val subtext = Color(0xFFA5ADCB)
    val mauve = Color(0xFFC6A0F6)
    val lavender = Color(0xFFB7BDF8)
    val blue = Color(0xFF8AADF4)
    val pink = Color(0xFFF5BDE6)
    val red = Color(0xFFED8796)
    val green = Color(0xFFA6DA95)
    val accent = Brush.linearGradient(listOf(mauve, lavender, blue))
}

private val colors = darkColorScheme(
    primary = Palette.mauve,
    onPrimary = Palette.mantle,
    secondary = Palette.lavender,
    onSecondary = Palette.mantle,
    tertiary = Palette.pink,
    background = Palette.base,
    onBackground = Palette.text,
    surface = Palette.base,
    onSurface = Palette.text,
    surfaceVariant = Palette.surface0,
    onSurfaceVariant = Palette.subtext,
    surfaceContainer = Palette.mantle,
    surfaceContainerHigh = Palette.surface0,
    surfaceContainerHighest = Palette.surface1,
    secondaryContainer = Palette.surface1,
    onSecondaryContainer = Palette.text,
    outline = Palette.surface1,
    error = Palette.red,
)

@Composable
fun TesuTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

@Composable
fun Cover(url: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 10.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(Palette.surface0),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = Palette.surface1, modifier = Modifier.size(size / 2.5f))
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

fun formatTime(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
