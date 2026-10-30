package com.tesuta.tesuplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** One line of lyrics; [timeMs] is null for plain (unsynced) lyrics */
data class LyricLine(val timeMs: Long?, val text: String)

/** Lyrics like the desktop: embedded tags first, then lrclib.net */
object Lyrics {
    private val lrcLine = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?](.*)$")

    fun parse(text: String): List<LyricLine> {
        val lines = text.lines()
        val synced = lines.flatMap { raw ->
            // a line may carry several time tags: [00:12.00][01:30.50]text
            var rest = raw.trim()
            val times = mutableListOf<Long>()
            while (true) {
                val m = lrcLine.find(rest) ?: break
                val (min, sec, frac, tail) = m.destructured
                val ms = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0
                times += min.toLong() * 60_000 + sec.toLong() * 1000 + ms
                rest = tail
            }
            times.map { LyricLine(it, rest.trim()) }
        }.sortedBy { it.timeMs }
        return synced.ifEmpty { lines.map { LyricLine(null, it) } }
    }

    private fun get(url: okhttp3.HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", "LRCGET v0.2.0 (https://github.com/tesutavt/feishin)").build()
        return Http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else if (r.code == 503) "503" else null }
    }

    private suspend fun getRetry(url: okhttp3.HttpUrl): String? {
        val first = get(url)
        if (first != "503") return first
        delay(800)
        return get(url)?.takeIf { it != "503" }
    }

    private fun pick(o: JsonObject) = o.str("syncedLyrics")?.takeIf { it.isNotBlank() } ?: o.str("plainLyrics")?.takeIf { it.isNotBlank() }

    suspend fun lrclib(track: Track): String? = withContext(Dispatchers.IO) {
        runCatching {
            val base = "https://lrclib.net/api".toHttpUrl()
            val exact = base.newBuilder().addPathSegment("get")
                .addQueryParameter("artist_name", track.artist)
                .addQueryParameter("track_name", track.title)
                .apply { if (track.album.isNotBlank() && track.album != "YouTube") addQueryParameter("album_name", track.album) }
                .apply { if (track.duration > 0) addQueryParameter("duration", "${track.duration}") }
                .build()
            getRetry(exact)?.let { pick(AppJson.parseToJsonElement(it).jsonObject) }?.let { return@runCatching it }

            val search = base.newBuilder().addPathSegment("search")
                .addQueryParameter("artist_name", track.artist)
                .addQueryParameter("track_name", track.title).build()
            val results = (getRetry(search)?.let { AppJson.parseToJsonElement(it) } as? JsonArray)
                ?.mapNotNull { it as? JsonObject }.orEmpty()
            val close = { o: JsonObject -> track.duration <= 0 || kotlin.math.abs((o.double("duration") ?: 0.0) - track.duration) <= 6 }
            val best = results.firstOrNull { it.str("syncedLyrics") != null && close(it) }
                ?: results.firstOrNull { pick(it) != null && close(it) }
                ?: results.firstOrNull { pick(it) != null }
            best?.let(::pick)
        }.getOrNull()
    }

    /** NetEase, like the desktop provider: search, then the lyric with an optional translation */
    suspend fun netease(track: Track, translation: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val search = "https://music.163.com/api/search/get".toHttpUrl().newBuilder()
                .addQueryParameter("s", "${track.artist} ${track.title}".trim())
                .addQueryParameter("type", "1").addQueryParameter("limit", "5").addQueryParameter("offset", "0").build()
            val songs = (get(search)?.let { AppJson.parseToJsonElement(it) } as? JsonObject)
                ?.get("result")?.let { it as? JsonObject }?.get("songs") as? JsonArray ?: return@runCatching null
            val title = track.title.lowercase()
            val song = songs.mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name")?.lowercase()?.let { n -> n.contains(title) || title.contains(n) } == true }
                ?: return@runCatching null
            val id = song["id"]?.toString() ?: return@runCatching null
            val lyric = "https://music.163.com/api/song/lyric".toHttpUrl().newBuilder()
                .addQueryParameter("id", id).addQueryParameter("lv", "-1").addQueryParameter("kv", "-1").addQueryParameter("tv", "-1").build()
            val o = get(lyric)?.let { AppJson.parseToJsonElement(it) } as? JsonObject ?: return@runCatching null
            val original = (o["lrc"] as? JsonObject)?.str("lyric")?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val translated = (o["tlyric"] as? JsonObject)?.str("lyric")
            if (translation && !translated.isNullOrBlank()) merge(original, translated) else original
        }.getOrNull()
    }

    /** Puts the translation under each original line with the same time tag */
    private fun merge(original: String, translated: String): String {
        val tag = Regex("^\\[(\\d{2}:\\d{2}\\.\\d{2,3})](.*)$")
        val map = translated.lines().mapNotNull { tag.find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2].trim() }.filterValues { it.isNotEmpty() }
        return original.lines().joinToString("\n") { line ->
            val m = tag.find(line.trim()) ?: return@joinToString line
            val t = map[m.groupValues[1]]
            if (t != null && m.groupValues[2].isNotBlank()) "${line.trim()} / $t" else line
        }
    }
}
