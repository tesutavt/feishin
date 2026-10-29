package com.tesuta.tesuplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            artist = it.groupValues[1].trim(); title = it.groupValues[2].trim()
        }
        if (artist.isEmpty()) artist = channel.removeSuffix(" - Topic").replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "").trim()
        title = title.replace(junk, "").replace(Regex("\\s*\\|.*$"), "").trim()
        return artist.ifEmpty { "YouTube" } to title.ifEmpty { rawTitle }
    }

    suspend fun lookup(rawTitle: String, channel: String, fallbackCover: String?): MusicTags {
        val (artist, title) = parse(rawTitle, channel)
        val single = MusicTags(artist, title, album = title, coverUrl = fallbackCover)
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                    .addQueryParameter("term", "$artist $title")
                    .addQueryParameter("entity", "song")
                    .addQueryParameter("media", "music")
                    .addQueryParameter("limit", "15").build()
                val body = Http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
                val results = AppJson.parseToJsonElement(body).jsonObject.arr("results")
                val hit = results.firstOrNull { similar(it.str("artistName").orEmpty(), artist) && normalize(it.str("trackName").orEmpty()) == normalize(title) }
                    ?: results.firstOrNull { similar(it.str("artistName").orEmpty(), artist) && similar(it.str("trackName").orEmpty(), title) }
                    ?: return@runCatching single
                MusicTags(
                    artist = hit.str("artistName") ?: artist,
                    title = hit.str("trackName") ?: title,
                    album = (hit.str("collectionName") ?: title).replace(Regex("\\s+-\\s+(Single|EP)$", RegexOption.IGNORE_CASE), ""),
                    year = hit.str("releaseDate")?.take(4)?.toIntOrNull(),
                    trackNumber = hit.int("trackNumber"),
                    genre = hit.str("primaryGenreName"),
                    coverUrl = hit.str("artworkUrl100")?.replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.") ?: fallbackCover,
                )
            }.getOrDefault(single)
        }
    }
}
