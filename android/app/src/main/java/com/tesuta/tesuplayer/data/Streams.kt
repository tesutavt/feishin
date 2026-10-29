package com.tesuta.tesuplayer.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class ResolvedStream(
    val url: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val isLive: Boolean,
    val ext: String?,
    val duration: Long,
)

/** YouTube and Twitch through yt-dlp (youtubedl-android). */
object Streams {
    private const val AUDIO_FORMAT = "bestaudio[protocol^=http][acodec!=none]/audio_only/bestaudio/best"

    /** m4a (AAC) is an audio-only track on YouTube: a music file, no video, no ffmpeg */
    private const val DOWNLOAD_FORMAT = "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio"

    private val ready = CompletableDeferred<Result<Unit>>()

    fun sourceOf(url: String): Source? {
        val host = runCatching { android.net.Uri.parse(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "youtu.be" || host.endsWith("youtube.com") -> Source.YOUTUBE
            host.endsWith("twitch.tv") -> Source.TWITCH
            else -> null
        }
    }

    fun twitchUrl(channel: String): String {
        val c = channel.trim()
        return if (c.startsWith("http")) c else "https://www.twitch.tv/${c.removePrefix("@")}"
    }

    /** Unpacks Python + yt-dlp on first run; call once from Application. */
    fun init(context: Context) {
        ready.complete(runCatching { YoutubeDL.getInstance().init(context.applicationContext) })
    }

    private suspend fun awaitReady() = ready.await().getOrThrow()

    private suspend fun runJson(vararg args: String, url: String): JsonObject = withContext(Dispatchers.IO) {
        awaitReady()
        val request = YoutubeDLRequest(url)
        var i = 0
        while (i < args.size) {
            if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                request.addOption(args[i], args[i + 1]); i += 2
            } else {
                request.addOption(args[i]); i += 1
            }
        }
        val response = YoutubeDL.getInstance().execute(request)
        AppJson.parseToJsonElement(response.out).jsonObject
    }

    private fun thumbnail(o: JsonObject): String? =
        o.str("thumbnail") ?: o.arr("thumbnails").lastOrNull()?.str("url")

    suspend fun resolve(pageUrl: String, forDownload: Boolean = false): ResolvedStream {
        val format = if (forDownload) DOWNLOAD_FORMAT else AUDIO_FORMAT
        val info = runJson("-J", "--no-playlist", "--no-warnings", "-f", format, url = pageUrl.trim())
        val url = info.str("url") ?: info.arr("requested_formats").firstOrNull()?.str("url")
            ?: error("yt-dlp returned no stream")
        val isTwitch = sourceOf(pageUrl) == Source.TWITCH
        return ResolvedStream(
            url = url,
            title = (if (isTwitch) info.str("description") else null) ?: info.str("title").orEmpty(),
            artist = info.str("uploader") ?: info.str("channel").orEmpty(),
            thumbnail = thumbnail(info),
            isLive = info["is_live"]?.toString() == "true",
            ext = info.str("ext"),
            duration = info.long("duration") ?: (info["duration"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0),
        )
    }

    suspend fun searchYoutube(query: String, limit: Int = 25): List<Track> {
        val result = runJson("-J", "--flat-playlist", "--no-warnings", url = "ytsearch$limit:$query")
        return result.arr("entries").mapNotNull { e ->
            val id = e.str("id") ?: return@mapNotNull null
            val page = e.str("url")?.takeIf { it.startsWith("http") } ?: "https://www.youtube.com/watch?v=$id"
            Track(
                id = "yt:$id",
                title = e.str("title") ?: id,
                artist = e.str("channel") ?: e.str("uploader").orEmpty(),
                duration = e["duration"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0,
                coverUrl = thumbnail(e) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                source = Source.YOUTUBE,
                pageUrl = page,
            )
        }
    }

    suspend fun version(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching { awaitReady(); YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()
    }

    /** YouTube changes often; yt-dlp updates itself without an app update. */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        awaitReady()
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        status?.name ?: "UNKNOWN"
    }
}
