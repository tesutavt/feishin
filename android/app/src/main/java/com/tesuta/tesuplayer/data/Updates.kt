package com.tesuta.tesuplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.time.Instant

/** "Проверять обновления": the latest release of the fork on GitHub, same as the desktop updater */
object Updates {
    private const val LATEST = "https://api.github.com/repos/tesutavt/feishin/releases/latest"

    data class Latest(val version: String, val apkUrl: String, val pageUrl: String, val newer: Boolean)

    suspend fun latest(context: Context): Latest? = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build())
                .execute().use { it.body!!.string() }
            val release = AppJson.parseToJsonElement(body).jsonObject
            val apk = release["assets"]!!.jsonArray.map { it.jsonObject }
                .first { it["name"]!!.jsonPrimitive.content.endsWith(".apk") }
            // the APK has no version in its name: it is newer when uploaded after this install
            val uploaded = Instant.parse(apk["updated_at"]!!.jsonPrimitive.content).toEpochMilli()
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            Latest(
                version = release["tag_name"]!!.jsonPrimitive.content,
                apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                pageUrl = release["html_url"]!!.jsonPrimitive.content,
                newer = uploaded > installed + 60_000,
            )
        }.getOrNull()
    }

    /** Downloads the APK in the browser; Android installs it from there */
    fun open(context: Context, latest: Latest) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
