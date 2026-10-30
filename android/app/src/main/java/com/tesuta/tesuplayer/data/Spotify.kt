package com.tesuta.tesuplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/** A track of a Spotify playlist / album: what to look for in the library or on YouTube */
data class ImportTrack(val title: String, val artist: String, val duration: Long = 0)

/**
 * Spotify without an account or API key: the public embed page lists a playlist's tracks
 * (up to ~100), a CSV from Exportify / TuneMyMusic / Soundiiz covers longer ones.
 * The same as the desktop "Import from Spotify".
 */
object Spotify {
    private val link = Regex("(?:open\\.spotify\\.com/(?:intl-\\w+/)?|spotify:)(playlist|album|track)[/:]([A-Za-z0-9]+)")

    fun isLink(text: String) = link.containsMatchIn(text)

    suspend fun fetch(url: String): Pair<String, List<ImportTrack>> = withContext(Dispatchers.IO) {
        val (type, id) = link.find(url)?.destructured ?: error("Это не ссылка на плейлист или альбом Spotify")
        val request = Request.Builder().url("https://open.spotify.com/embed/$type/$id")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
        val html = Http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("Spotify: HTTP ${r.code}")
            r.body!!.string()
        }
        val json = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Spotify не отдал список треков (плейлист приватный?)")
        val entity = AppJson.parseToJsonElement(json).jsonObject.obj("props")?.obj("pageProps")?.obj("state")
            ?.obj("data")?.obj("entity") ?: error("Spotify не отдал список треков")
        val tracks = (entity["trackList"]?.jsonArray ?: emptyList()).mapNotNull { e ->
            val o = e.jsonObject
            val title = o.str("title") ?: return@mapNotNull null
            ImportTrack(title, o.str("subtitle").orEmpty().replace(' ', ' '), (o.long("duration") ?: 0) / 1000)
        }
        (entity.str("name") ?: entity.str("title") ?: "Spotify") to tracks
    }

    private fun cells(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && (c == ',' || c == ';') -> { out += cell.toString().trim(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString().trim()
        return out
    }

    fun parseCsv(text: String): List<ImportTrack> {
        val lines = text.lines().filter { it.isNotBlank() }
        val names = cells(lines.firstOrNull() ?: return emptyList()).map { it.lowercase() }
        // the most specific key wins: "Track Name" over "Track URI"
        fun column(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            names.indexOfFirst { it.contains(k) && !it.contains("uri") }.takeIf { it >= 0 }
        } ?: -1
        val title = column("track name", "title", "название", "track", "name")
        val artist = column("artist name", "artist", "исполнитель")
        val duration = column("duration (ms)", "duration")
        if (title < 0) error("В CSV нет колонки с названием трека")
        return lines.drop(1).map(::cells).filter { it.getOrNull(title).orEmpty().isNotBlank() }.map { c ->
            val ms = c.getOrNull(duration)?.toLongOrNull() ?: 0
            ImportTrack(c[title], c.getOrNull(artist).orEmpty(), if (ms > 10_000) ms / 1000 else ms)
        }
    }

    fun normalize(value: String) = value.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?\\]|- .*remaster.*$"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
