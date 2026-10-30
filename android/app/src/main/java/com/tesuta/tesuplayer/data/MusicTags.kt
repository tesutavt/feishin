package com.tesuta.tesuplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Real artist / album / cover for audio from YouTube (same rules as the desktop app) */
data class MusicTags(
    val artist: String,
    val title: String,
    val album: String,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val genre: String? = null,
    val coverUrl: String? = null,
)

object MusicTagsLookup {
    private val junk = Regex(
        "\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|\\bmv\\b|\\bhd\\b|\\bhq\\b|4k|upgrade|remaster|клип|премьера|premiere)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    private fun normalize(s: String) = s.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("\\b(feat|ft)\\.?\\s.*$"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun similar(a: String, b: String): Boolean {
        val x = normalize(a); val y = normalize(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    /** "Artist - Song (Official video)" -> artist + song */
    fun parse(rawTitle: String, channel: String): Pair<String, String> {
        var title = rawTitle
        var artist = ""
        Regex("^(.+?)\\s+[-–—|]\\s+(.+)$").find(title)?.let {
            val (left, right) = it.groupValues[1].trim() to it.groupValues[2].trim()
            // "Song - Artist" (common for anime / VTuber channels): the channel name tells which side is the artist
            val swapped = similar(right.replace(junk, ""), channel) && !similar(left, channel)
            artist = if (swapped) right.replace(junk, "") else left
            title = if (swapped) left else right
        }
        if (artist.isEmpty()) artist = channel.removeSuffix(" - Topic").replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "").trim()
        title = title.replace(junk, "").replace(Regex("\\s*\\|.*$"), "").trim()
        return artist.ifEmpty { "YouTube" } to title.ifEmpty { rawTitle }
    }

    /** iTunes refuses bursts: several downloads at once look up one by one */
    private val itunes = Mutex()

    private suspend fun search(term: String): List<JsonObject> = itunes.withLock {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("entity", "song")
            .addQueryParameter("media", "music")
            .addQueryParameter("limit", "15").build()
        repeat(3) { attempt ->
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    Http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) null else AppJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject.arr("results")
                    }
                }.getOrNull()
            }
            if (results != null) return@withLock results
            delay(1500L * (attempt + 1))
        }
        emptyList()
    }

    private fun best(results: List<JsonObject>, artist: String, title: String) =
        results.firstOrNull { similar(it.str("artistName").orEmpty(), artist) && normalize(it.str("trackName").orEmpty()) == normalize(title) }
            ?: results.firstOrNull { similar(it.str("artistName").orEmpty(), artist) && similar(it.str("trackName").orEmpty(), title) }

    suspend fun lookup(rawTitle: String, channel: String, fallbackCover: String?): MusicTags {
        val (artist, title) = parse(rawTitle, channel)
        return find(artist, title, fallbackCover) ?: MusicTags(artist, title, album = title, coverUrl = fallbackCover)
    }

    /** The song in the iTunes catalogue, or null when it is not there */
    suspend fun find(artist: String, title: String, fallbackCover: String? = null): MusicTags? {
        val results = search("$artist $title")
        // "Song - Artist" titles: the same results, the other way round
        val hit = best(results, artist, title) ?: best(results, title, artist) ?: return null
        return MusicTags(
            artist = hit.str("artistName") ?: artist,
            title = hit.str("trackName") ?: title,
            album = (hit.str("collectionName") ?: title).replace(Regex("\\s+-\\s+(Single|EP)$", RegexOption.IGNORE_CASE), ""),
            year = hit.str("releaseDate")?.take(4)?.toIntOrNull(),
            trackNumber = hit.int("trackNumber"),
            genre = hit.str("primaryGenreName"),
            coverUrl = hit.str("artworkUrl100")?.replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.") ?: fallbackCover,
        )
    }
}
