package com.tesuta.tesuplayer.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.tesuta.tesuplayer.data.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The desktop's themes on the phone: its 32 built-in themes (assets/themes.json, made by
 * scripts/export-android-themes.mjs) and the same custom theme .json files the desktop reads
 * from its Themes folder ({ "mode", "extends", "colors": { background, primary, ... } }).
 */
object DesktopThemes {
    data class Entry(val id: String, val label: String, val colors: ThemeColors)

    /** id -> desktop color keys, for `extends` */
    private var raw: Map<String, Pair<Boolean, Map<String, String>>> = emptyMap()
    var builtIn: List<Entry> = emptyList()
        private set

    fun load(context: Context) {
        runCatching {
            val list = AppJson.parseToJsonElement(context.assets.open("themes.json").bufferedReader().use { it.readText() }).jsonArray
            val parsed = list.map { it.jsonObject }.mapNotNull { o ->
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val label = (o["label"] as? JsonPrimitive)?.contentOrNull ?: id
                val light = (o["mode"] as? JsonPrimitive)?.contentOrNull == "light"
                val colors = (o["colors"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
                Triple(id, label, light to colors)
            }
            raw = parsed.associate { it.first to it.third }
            builtIn = parsed.mapNotNull { (id, label, value) -> build(value.second, value.first)?.let { Entry(id, label, it) } }
        }
    }

    /**
     * A custom theme file of the desktop app. Returns null when it has no usable colours.
     * [name] is the file name without .json, like on the desktop.
     */
    fun fromFile(name: String, json: String): Entry? = runCatching {
        val o = AppJson.parseToJsonElement(json).jsonObject
        val base = (o["extends"] as? JsonPrimitive)?.contentOrNull?.let { raw[it] }
        val light = ((o["mode"] as? JsonPrimitive)?.contentOrNull ?: if (base?.first == true) "light" else "dark") == "light"
        val own = (o["colors"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val colors = (base?.second ?: raw["defaultDark"]?.second.orEmpty()) + own
        build(colors, light)?.let { Entry("file:$name", label(name), it) }
    }.getOrNull()

    fun label(name: String) = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").split('-', '_', ' ')
        .filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** Desktop colour keys -> the colours every phone screen uses */
    private fun build(c: Map<String, String>, light: Boolean): ThemeColors? {
        val background = css(c["background"]) ?: return null
        val text = css(c["foreground"]) ?: if (light) Color(0xFF1E1E1E) else Color(0xFFEDEDED)
        val primary = css(c["primary"]) ?: Color(0xFFC6A0F6)
        val alternate = css(c["background-alternate"])?.takeIf { it != background }
        val surface = css(c["surface"]) ?: lerp(background, text, 0.08f)
        return ThemeColors(
            base = background,
            mantle = alternate ?: lerp(background, if (light) Color.White else Color.Black, 0.25f),
            surface0 = surface,
            surface1 = lerp(surface, text, 0.14f),
            text = text,
            subtext = css(c["foreground-muted"]) ?: lerp(text, background, 0.35f),
            mauve = primary,
            lavender = lerp(primary, text, 0.25f),
            blue = css(c["state-info"]) ?: lerp(primary, text, 0.45f),
            pink = lerp(primary, css(c["state-error"]) ?: primary, 0.35f),
            red = css(c["state-error"]) ?: Color(0xFFED8796),
            green = css(c["state-success"]) ?: Color(0xFFA6DA95),
            light = light,
        )
    }

    /** CSS colours the desktop themes use: #rgb, #rrggbb, #rrggbbaa, rgb(), rgba(), hsl(), hsla() */
    fun css(value: String?): Color? {
        val v = value?.trim()?.lowercase() ?: return null
        runCatching {
            if (v.startsWith("#")) {
                val hex = v.drop(1)
                val full = when (hex.length) {
                    3 -> hex.map { "$it$it" }.joinToString("") + "ff"
                    6 -> hex + "ff"
                    8 -> hex
                    else -> return null
                }
                val n = full.toLong(16)
                return Color(((n shr 24) and 0xff).toInt(), ((n shr 16) and 0xff).toInt(), ((n shr 8) and 0xff).toInt(), (n and 0xff).toInt())
            }
            val parts = Regex("^(rgba?|hsla?)\\((.*)\\)$").find(v) ?: return null
            val nums = parts.groupValues[2].split(',', ' ', '/').filter { it.isNotBlank() }
            fun num(i: Int, scale: Float) = nums.getOrNull(i)?.let { s ->
                if (s.endsWith("%")) s.dropLast(1).toFloat() / 100f * scale else s.toFloat()
            }
            val alpha = num(3, 1f) ?: 1f
            return if (parts.groupValues[1].startsWith("rgb")) {
                Color(num(0, 255f)!! / 255f, num(1, 255f)!! / 255f, num(2, 255f)!! / 255f, alpha)
            } else {
                Color.hsl(num(0, 360f)!! % 360f, num(1, 1f)!!.coerceIn(0f, 1f), num(2, 1f)!!.coerceIn(0f, 1f), alpha)
            }
        }
        return null
    }
}
