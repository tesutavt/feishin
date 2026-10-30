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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** The colours every screen uses; a theme fills them all */
data class ThemeColors(
    val base: Color,
    val mantle: Color,
    val surface0: Color,
    val surface1: Color,
    val text: Color,
    val subtext: Color,
    val mauve: Color,
    val lavender: Color,
    val blue: Color,
    val pink: Color = Color(0xFFF5BDE6),
    val red: Color = Color(0xFFED8796),
    val green: Color = Color(0xFFA6DA95),
)

/** A custom theme from three colours: background, accent, text */
fun customTheme(background: Color, accent: Color, text: Color) = ThemeColors(
    base = background,
    mantle = lerp(background, Color.Black, 0.25f),
    surface0 = lerp(background, text, 0.1f),
    surface1 = lerp(background, text, 0.2f),
    text = text,
    subtext = lerp(text, background, 0.3f),
    mauve = accent,
    lavender = lerp(accent, text, 0.25f),
    blue = lerp(accent, text, 0.45f),
)

enum class AppTheme(val label: String, val colors: ThemeColors?) {
    // the palette of the app icon
    MACCHIATO("Catppuccin Macchiato", ThemeColors(
        Color(0xFF24273A), Color(0xFF1E2030), Color(0xFF363A4F), Color(0xFF494D64),
        Color(0xFFCAD3F5), Color(0xFFA5ADCB), Color(0xFFC6A0F6), Color(0xFFB7BDF8), Color(0xFF8AADF4),
    )),
    MOCHA("Catppuccin Mocha", ThemeColors(
        Color(0xFF1E1E2E), Color(0xFF181825), Color(0xFF313244), Color(0xFF45475A),
        Color(0xFFCDD6F4), Color(0xFFA6ADC8), Color(0xFFCBA6F7), Color(0xFFB4BEFE), Color(0xFF89B4FA),
        Color(0xFFF5C2E7), Color(0xFFF38BA8), Color(0xFFA6E3A1),
    )),
    AMOLED("Чёрная (AMOLED)", ThemeColors(
        Color(0xFF000000), Color(0xFF000000), Color(0xFF161616), Color(0xFF2A2A2A),
        Color(0xFFEDEDED), Color(0xFF9E9E9E), Color(0xFFC6A0F6), Color(0xFFB7BDF8), Color(0xFF8AADF4),
    )),
    NORD("Nord", ThemeColors(
        Color(0xFF2E3440), Color(0xFF272C36), Color(0xFF3B4252), Color(0xFF4C566A),
        Color(0xFFECEFF4), Color(0xFFD8DEE9), Color(0xFF88C0D0), Color(0xFF81A1C1), Color(0xFF5E81AC),
        Color(0xFFB48EAD), Color(0xFFBF616A), Color(0xFFA3BE8C),
    )),
    DRACULA("Dracula", ThemeColors(
        Color(0xFF282A36), Color(0xFF21222C), Color(0xFF343746), Color(0xFF44475A),
        Color(0xFFF8F8F2), Color(0xFFBFBFBF), Color(0xFFBD93F9), Color(0xFFFF79C6), Color(0xFF8BE9FD),
        Color(0xFFFF79C6), Color(0xFFFF5555), Color(0xFF50FA7B),
    )),
    GRUVBOX("Gruvbox", ThemeColors(
        Color(0xFF282828), Color(0xFF1D2021), Color(0xFF3C3836), Color(0xFF504945),
        Color(0xFFEBDBB2), Color(0xFFBDAE93), Color(0xFFFE8019), Color(0xFFFABD2F), Color(0xFF83A598),
        Color(0xFFD3869B), Color(0xFFFB4934), Color(0xFFB8BB26),
    )),
    CUSTOM("Своя", null),
}

/** Current theme colours; changing [colors] recolours the whole app */
object Palette {
    var colors by mutableStateOf(AppTheme.MACCHIATO.colors!!)
    val base get() = colors.base
    val mantle get() = colors.mantle
    val surface0 get() = colors.surface0
    val surface1 get() = colors.surface1
    val text get() = colors.text
    val subtext get() = colors.subtext
    val mauve get() = colors.mauve
    val lavender get() = colors.lavender
    val blue get() = colors.blue
    val pink get() = colors.pink
    val red get() = colors.red
    val green get() = colors.green
    val accent get() = Brush.linearGradient(listOf(mauve, lavender, blue))

    /** From the settings: a preset or the custom colours ("#RRGGBB,#RRGGBB,#RRGGBB") */
    fun apply(theme: String?, custom: String?) {
        val preset = AppTheme.entries.firstOrNull { it.name == theme } ?: AppTheme.MACCHIATO
        colors = preset.colors ?: parseCustom(custom) ?: AppTheme.MACCHIATO.colors!!
    }

    fun parseCustom(custom: String?): ThemeColors? = runCatching {
        val (bg, accent, text) = custom!!.split(',').map { Color(android.graphics.Color.parseColor(it.trim())) }
        customTheme(bg, accent, text)
    }.getOrNull()
}

private fun scheme() = darkColorScheme(
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
fun TesuTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme(), content = content)

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
