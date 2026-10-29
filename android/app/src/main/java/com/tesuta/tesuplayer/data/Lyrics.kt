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
        val request = Request.Builder().url(url).header("User-Agent", "LRCGET v0.2.0 (https://github.com/tesutavt/TesuPlayer)").build()
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
}
