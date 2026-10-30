package com.tesuta.tesuplayer.data

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf

/**
 * Every desktop setting that works on the phone, by key. Values live in the same
 * SharedPreferences as [Prefs] (shared keys stay in sync) and are observable from Compose:
 * changing a setting recomposes what uses it.
 */
object S {
    private lateinit var sp: SharedPreferences
    private val state = mutableStateMapOf<String, Any>()
    private val listeners = mutableListOf<(String) -> Unit>()

    fun init(prefs: SharedPreferences) {
        sp = prefs
        prefs.all.forEach { (k, v) -> if (v != null) state[k] = v }
    }

    fun onChange(listener: (String) -> Unit) { listeners += listener }

    fun bool(key: String, default: Boolean) = state[key] as? Boolean ?: default
    fun int(key: String, default: Int) = (state[key] as? Number)?.toInt() ?: default
    fun float(key: String, default: Float) = (state[key] as? Number)?.toFloat() ?: default
    fun string(key: String, default: String) = state[key] as? String ?: default
    fun stringSet(key: String): Set<String> = (state[key] as? String)?.split('\u001f')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    fun set(key: String, value: Any?) {
        val e = sp.edit()
        when (value) {
            null -> { state.remove(key); e.remove(key) }
            is Boolean -> { state[key] = value; e.putBoolean(key, value) }
            is Int -> { state[key] = value; e.putInt(key, value) }
            is Float -> { state[key] = value; e.putFloat(key, value) }
            is Long -> { state[key] = value; e.putLong(key, value) }
            is Set<*> -> { val s = value.joinToString("\u001f"); state[key] = s; e.putString(key, s) }
            else -> { state[key] = value.toString(); e.putString(key, value.toString()) }
        }
        e.apply()
        listeners.forEach { it(key) }
    }

    // ---------------- General → Theme
    val useSystemTheme get() = bool("s_use_system_theme", false)
    val themeDark get() = string("s_theme_dark", "MACCHIATO")
    val themeLight get() = string("s_theme_light", "desktop:catppuccinLatte")
    val useThemeAccent get() = bool("s_use_theme_accent", true)
    val accentColor get() = string("s_accent_color", "#C6A0F6")
    val useThemeShade get() = bool("s_use_theme_shade", true)
    /** 0..9 like Mantine shades; 6 = as is */
    val primaryShade get() = int("s_primary_shade", 6)

    // ---------------- General → Application
    val fontType get() = string("s_font_type", "builtIn") // builtIn | custom
    val font get() = string("s_font", "default") // default | sans | serif | mono | cursive
    val customFontPath get() = string("s_custom_font", "")
    val zoom get() = int("s_zoom", 100)
    val savePlayQueue get() = bool("s_save_queue", true)
    val confirmQueueChanges get() = bool("s_confirm_queue", false)
    val homeFeature get() = bool("s_home_feature", true)
    val homeFeatureStyle get() = string("s_home_feature_style", "multiple") // multiple | single
    val albumBackground get() = bool("s_album_bg", true)
    val albumBackgroundBlur get() = int("s_album_bg_blur", 24)
    val artistBackground get() = bool("s_artist_bg", true)
    val artistBackgroundBlur get() = int("s_artist_bg_blur", 24)
    val imageAspectRatio get() = bool("s_image_aspect", false)
    val showFavorites get() = bool("s_show_favorites", true)
    val showRatings get() = bool("s_show_ratings", true)
    val playerbarOpenDrawer get() = bool("s_playerbar_open", true)
    /** minutes without touching the screen; 0 = off */
    val fullscreenAutoOpen get() = int("s_fullscreen_auto_open", 0)
    val autosave get() = bool("s_autosave", false)
    val autosaveCount get() = int("s_autosave_count", 5)

    // ---------------- General → External links
    val externalLinks get() = bool("s_ext_links", true)
    val linkLastfm get() = bool("s_ext_lastfm", true)
    val linkListenbrainz get() = bool("s_ext_listenbrainz", true)
    val linkMusicbrainz get() = bool("s_ext_musicbrainz", true)
    val linkQobuz get() = bool("s_ext_qobuz", false)
    val linkSpotify get() = bool("s_ext_spotify", true)
    val nativeSpotify get() = bool("s_ext_native_spotify", false)

    // ---------------- General → Controls
    val buttonSize get() = int("s_button_size", 36)
    val showSkipButtons get() = bool("s_skip_buttons", false)
    val skipSeconds get() = int("s_skip_seconds", 10)
    val playButtonBehavior get() = string("s_play_behavior", "play") // play | next | last
    val artistRadioCount get() = int("s_radio_count", 50)
    val microtonalPitch get() = bool("s_microtonal", false)

    // ---------------- General → Scrobbling
    /** any = whichever threshold comes first, all = both */
    val scrobbleMode get() = string("s_scrobble_mode", "any")
    val notifySongs get() = bool("s_notify_songs", false)

    // ---------------- General → Lyrics
    val preferLocalLyrics get() = bool("s_prefer_local_lyrics", true)
    val lyricProviders get() = stringSet("s_lyric_providers").ifEmpty { setOf("lrclib", "netease") }
    val neteaseTranslation get() = bool("s_netease_translation", false)

    // ---------------- Playback → Audio
    val audioDevice get() = int("s_audio_device", 0) // 0 = system default, else AudioDeviceInfo.id
    val preservePitch get() = bool("s_preserve_pitch", true)
    val audioFade get() = bool("s_audio_fade", true)
    val gapless get() = bool("s_gapless", true)

    // ---------------- Playback → Equalizer / compressor
    val eqPreamp get() = int("s_eq_preamp", 0) // millibels
    val compressor get() = bool("s_compressor", false)
    val compThreshold get() = int("s_comp_threshold", -24)
    val compRatio get() = int("s_comp_ratio", 4)
    val compAttack get() = int("s_comp_attack", 10)
    val compRelease get() = int("s_comp_release", 250)
    val compMakeup get() = int("s_comp_makeup", 0)
    val compKnee get() = int("s_comp_knee", 6)

    // ---------------- Playback → Player filters ("field:text" rules, songs that match are skipped)
    val playerFilters get() = stringSet("s_player_filters")

    // ---------------- Playback → Auto-DJ
    val autoDjCount get() = int("s_autodj_count", 10)
    val autoDjMode get() = string("s_autodj_mode", "songs") // songs | albums
    val autoDjSongStrategy get() = string("s_autodj_song", "similar") // similar | random | genre
    val autoDjAlbumStrategy get() = string("s_autodj_album", "similar") // similar | random
    /** add more when this many tracks are left */
    val autoDjTiming get() = int("s_autodj_timing", 1)
    val autoDjDuplicates get() = bool("s_autodj_duplicates", false)
    val autoDjOnlySimilar get() = bool("s_autodj_only_similar", false)

    // ---------------- Window
    val preventSleep get() = bool("s_prevent_sleep", false)
    val preventSuspend get() = bool("s_prevent_suspend", true)

    // ---------------- Advanced
    val checkUpdates get() = bool("s_check_updates", true)
    val logLevel get() = string("s_log_level", "info")
}
