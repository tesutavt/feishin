package com.tesuta.tesuplayer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tesuta.tesuplayer.BuildConfig
import com.tesuta.tesuplayer.data.HomeSection
import com.tesuta.tesuplayer.data.S
import com.tesuta.tesuplayer.data.Streams
import com.tesuta.tesuplayer.data.UiPart
import com.tesuta.tesuplayer.data.Updates
import kotlinx.coroutines.launch

// The desktop settings menu on the phone: the same tabs, sections and items
// (src/renderer/features/settings/components/*). Items the phone has no counterpart for stay
// in their place, marked "на компьютере", so both menus read the same.

sealed interface Item {
    val visible: () -> Boolean
}

data class Toggle(val title: String, val desc: String?, val key: String, val default: Boolean,
                  override val visible: () -> Boolean = { true }, val onSet: ((Boolean) -> Unit)? = null) : Item
data class Slide(val title: String, val desc: String?, val key: String, val default: Int, val range: IntRange, val step: Int, val unit: String,
                 override val visible: () -> Boolean = { true }) : Item
data class Choice(val title: String, val desc: String?, val key: String, val default: String, val options: () -> List<Pair<String, String>>,
                  override val visible: () -> Boolean = { true }, val onSet: ((String) -> Unit)? = null) : Item
data class Custom(override val visible: () -> Boolean = { true }, val content: @Composable () -> Unit) : Item
data class PcOnly(val title: String, val why: String, override val visible: () -> Boolean = { true }) : Item
data class Section(val title: String, val items: List<Item>)

enum class PcTab(val title: String, val icon: ImageVector) {
    LIBRARY("Моя музыка", Icons.Default.Computer),
    GENERAL("Общие", Icons.Default.Settings),
    PLAYBACK("Воспроизведение", Icons.Default.Equalizer),
    HOTKEYS("Горячие клавиши", Icons.Default.Keyboard),
    WINDOW("Окно", Icons.Default.WebAsset),
    ADVANCED("Расширенные настройки", Icons.Default.Tune),
}

private const val ON_PC_WINDOW = "У приложения на телефоне нет окна"
private const val ON_PC_SIDEBAR = "На телефоне нет боковой панели: разделы — во вкладке «Библиотека»"

/** Themes to choose from: the phone presets, the desktop themes and imported theme files */
private fun themeChoices(): List<Pair<String, String>> =
    AppTheme.entries.filter { it != AppTheme.CUSTOM }.map { it.name to it.label } +
        listOf(AppTheme.CUSTOM.name to "Своя") +
        DesktopThemes.builtIn.map { "desktop:${it.id}" to it.label } +
        Palette.imported.keys.map { "file:$it" to DesktopThemes.label(it) }

private fun sections(tab: PcTab, vm: AppViewModel, nav: Nav): List<Section> = when (tab) {
    PcTab.LIBRARY -> listOf(Section("Моя музыка", listOf(Custom { LibrarySettings(vm) })))

    PcTab.GENERAL -> listOf(
        Section("Тема", listOf(
            Toggle("Использовать системную тему", "Тёмная или светлая тема — как в системе Android", "s_use_system_theme", false) { vm.refreshTheme() },
            Choice("Тема", null, "theme", AppTheme.MACCHIATO.name, ::themeChoices, visible = { !S.useSystemTheme }) { vm.setThemeId(it) },
            Choice("Тема (темная)", null, "s_theme_dark", "MACCHIATO", ::themeChoices, visible = { S.useSystemTheme }) { vm.refreshTheme() },
            Choice("Тема (светлая)", null, "s_theme_light", "desktop:catppuccinLatte", ::themeChoices, visible = { S.useSystemTheme }) { vm.refreshTheme() },
            Toggle("Использовать цвет акцента темы", null, "s_use_theme_accent", true) { vm.refreshTheme() },
            Custom(visible = { !S.useThemeAccent }) { ColorField("Акцентный цвет", "s_accent_color", "#C6A0F6") { vm.refreshTheme() } },
            Toggle("Использовать основной цвет темы", null, "s_use_theme_shade", true) { vm.refreshTheme() },
            Custom(visible = { !S.useThemeShade }) { ShadeSlider(vm) },
            Custom { ThemeFiles(vm) },
        )),
        Section("Приложение", listOf(
            PcOnly("Язык интерфейса", "Интерфейс телефона пока только на русском"),
            Choice("Тип шрифта", null, "s_font_type", "builtIn", { listOf("builtIn" to "Встроенный", "custom" to "Свой файл шрифта") }),
            Choice("Шрифт", null, "s_font", "default", {
                listOf("default" to "Системный", "sans" to "Без засечек", "serif" to "С засечками", "mono" to "Моноширинный", "cursive" to "Рукописный")
            }, visible = { S.fontType == "builtIn" }),
            Custom(visible = { S.fontType == "custom" }) { FontFilePicker() },
            Slide("Процент масштабирования", "Размер текста и элементов", "s_zoom", 100, 70..150, 5, "%"),
            Toggle("Сохранять очередь воспроизведения", "Очередь и позиция восстанавливаются после перезапуска", "s_save_queue", true),
            Toggle("Подтверждать изменение очереди", "Спрашивать перед заменой текущей очереди", "s_confirm_queue", false),
            Toggle("Карусель рекомендаций на главной", null, "s_home_feature", true),
            Choice("Стиль карусели рекомендаций на главной", null, "s_home_feature_style", "multiple",
                { listOf("multiple" to "Несколько альбомов", "single" to "Один большой") }, visible = { S.homeFeature }),
            Toggle("Фоновое изображение альбома", "Размытая обложка за страницей альбома", "s_album_bg", true),
            Slide("Размер размытия фонового изображения альбома", null, "s_album_bg_blur", 24, 0..60, 2, "px", visible = { S.albumBackground }),
            Toggle("Фоновое изображение исполнителя", null, "s_artist_bg", true),
            Slide("Размер размытия фонового изображения исполнителя", null, "s_artist_bg_blur", 24, 0..60, 2, "px", visible = { S.artistBackground }),
            Toggle("Использовать исходное соотношение сторон обложки", "Не обрезать обложки до квадрата", "s_image_aspect", false),
            PcOnly("Стиль боковой панели очереди воспроизведения", ON_PC_SIDEBAR),
            PcOnly("Боковая панель очереди воспроизведения", ON_PC_SIDEBAR),
            Toggle("Отображать избранное", "Сердечки у треков и в плеере", "s_show_favorites", true),
            Toggle("Показывать оценки в виде звёзд", null, "s_show_ratings", true),
            PcOnly("Размыть откровенные изображения", "В локальной библиотеке нет пометки «откровенное»"),
            PcOnly("Приоритет заполнителя изображения", "Обложки на телефоне грузятся сразу"),
            PcOnly("Включить множественный выбор в сетке", "На телефоне: зажмите альбом — меню действий"),
            Toggle("Переключение полноэкранного плеера по панели плеера", "Касание мини-плеера открывает полноэкранный плеер", "s_playerbar_open", true),
            Slide("Время ожидания открытия полноэкранного плеера (минуты)", "Открыть плеер, если экран не трогали; 0 — выключено", "s_fullscreen_auto_open", 0, 0..30, 1, "мин"),
            Toggle("Автоматически сохранять очередь воспроизведения", "Сохранять очередь на компьютере, чтобы продолжить с другого устройства", "s_autosave", false),
            Slide("Частота автоматического сохранения очереди воспроизведения", "Каждые N треков", "s_autosave_count", 5, 1..20, 1, "", visible = { S.autosave }),
        )),
        Section("Внешние ссылки", listOf(
            Toggle("Показывать внешние ссылки", "На страницах альбома и исполнителя", "s_ext_links", true),
            Toggle("Показывать ссылки на Last.fm", null, "s_ext_lastfm", true, visible = { S.externalLinks }),
            Toggle("Показывать ссылки на ListenBrainz", null, "s_ext_listenbrainz", true, visible = { S.externalLinks }),
            Toggle("Показывать ссылки на MusicBrainz", null, "s_ext_musicbrainz", true, visible = { S.externalLinks }),
            Toggle("Показывать ссылки на Qobuz", null, "s_ext_qobuz", false, visible = { S.externalLinks }),
            Toggle("Показывать ссылки на Spotify", null, "s_ext_spotify", true, visible = { S.externalLinks }),
            Toggle("Использовать приложение Spotify", "Открывать ссылки в приложении Spotify", "s_ext_native_spotify", false, visible = { S.externalLinks && S.linkSpotify }),
        )),
        Section("Управление", listOf(
            Slide("Размер кнопки панели проигрывателя", null, "s_button_size", 36, 24..56, 2, "dp"),
            Toggle("Отображать кнопки перехода", "Перемотка назад / вперёд в плеере", "s_skip_buttons", false),
            Slide("Длительность перемотки", null, "s_skip_seconds", 10, 5..60, 5, "с", visible = { S.showSkipButtons }),
            Choice("Поведение кнопки «Воспроизвести»", "Что делает «Играть» у альбома и плейлиста", "s_play_behavior", "play",
                { listOf("play" to "Играть сейчас", "next" to "Играть далее", "last" to "В конец очереди") }),
            PcOnly("Up next queue", "На телефоне «Играть далее» всегда ставит трек сразу после текущего"),
            PcOnly("Воспроизводить следующую песню в плейлисте", "На телефоне очередь всегда продолжается"),
            PcOnly("Кнопка разворачивания обложки альбома", ON_PC_SIDEBAR),
            Slide("Количество радиостанций для исполнителя/трека", "Сколько треков подбирать для «Радио»", "s_radio_count", 50, 10..200, 10, ""),
            PcOnly("Шаг изменения громкости", "Громкость на телефоне — кнопками громкости"),
            PcOnly("Ширина ползунка громкости", "Громкость на телефоне — кнопками громкости"),
            PcOnly("Слайдер панели проигрывателя", "Волновая форма есть только на компьютере"),
            Toggle("Микротональные настройки высоты тона", "Скорость шагом 0.01× вместо 0.05×", "s_microtonal", false),
        )),
        Section("Боковая панель", listOf(
            PcOnly("Список плейлистов в боковой панели", ON_PC_SIDEBAR),
            PcOnly("Сортировка плейлиста в боковой панели", ON_PC_SIDEBAR),
            PcOnly("Включить папки", ON_PC_SIDEBAR),
            Custom { HiddenToggle(vm, "Отображать очередь воспроизведения в боковой панели плеера", "Вкладка «Очередь» в плеере", UiPart.PLAYER_QUEUE) },
            Custom { HiddenToggle(vm, "Показывать текст песни в боковой панели плеера", "Вкладка «Текст» в плеере", UiPart.PLAYER_LYRICS) },
            Custom { VisualizerToggle(vm) },
            Custom { InterfaceParts(vm) },
        )),
        Section("Скробблинг", listOf(
            Toggle("Скробблинг", "Счётчики, «часто слушаемые», «недавно игранные». С компьютером — общие на все устройства", "scrobble", true),
            Choice("Установить минимальный режим скробблинга", null, "s_scrobble_mode", "any",
                { listOf("any" to "Что наступит раньше", "all" to "Оба условия") }, visible = { S.bool("scrobble", true) }),
            Slide("Минимальная продолжительность для скробблинга (проценты)", null, "scrobble_percent", 50, 10..100, 5, "%", visible = { S.bool("scrobble", true) }),
            Slide("Минимальная продолжительность для скробблинга (секунды)", null, "scrobble_seconds", 240, 10..600, 10, "с", visible = { S.bool("scrobble", true) }),
            Toggle("Включить уведомления о песнях", "Показывать, какая песня началась", "s_notify_songs", false),
        )),
        Section("Текст песни", listOf(
            Toggle("Следовать за текущим текстом песни", "Прокручивать текст до текущей позиции воспроизведения", "lyrics_follow", true),
            Toggle("Предпочитать локальные тексты песен", "Сначала тексты из тегов файлов", "s_prefer_local_lyrics", true),
            Toggle("Получать тексты песен из интернета", null, "lyrics", true),
            Custom(visible = { S.bool("lyrics", true) }) { LyricProviders() },
            PcOnly("Включить генерацию фуриганы", "Японский словарь (40 МБ) есть только в версии для компьютера"),
            PcOnly("Включить генерацию ромадзи", "Японский словарь (40 МБ) есть только в версии для компьютера"),
            Toggle("Включить переводы NetEase", "Показывать перевод под строками, если NetEase его знает", "s_netease_translation", false,
                visible = { "netease" in S.lyricProviders }),
            Slide("Смещение текста песни (мс)", "Положительное — позже, отрицательное — раньше", "lyrics_offset", 0, -3000..3000, 100, "мс"),
        )),
    )

    PcTab.PLAYBACK -> listOf(
        Section("Аудио", listOf(
            PcOnly("Аудиоплеер", "На телефоне всегда системный плеер Android (ExoPlayer)"),
            Custom { AudioDeviceChoice() },
            PcOnly("Использовать веб-аудио", "Только в версии для компьютера"),
            Toggle("Сохранять тональность", "При изменении скорости голос не становится выше или ниже", "s_preserve_pitch", true) { vm.player.applyAudioSettings() },
            Toggle("Плавное изменение звука", "Плавная пауза и старт", "s_audio_fade", true),
        )),
        Section("mpv", listOf(
            PcOnly("Путь к исполняемому файлу mpv", "mpv — плеер для компьютера"),
            PcOnly("дополнительные параметры mpv", "mpv — плеер для компьютера"),
            PcOnly("Бесшовное аудио", "На телефоне треки всегда идут без пауз"),
            PcOnly("Частота дискретизации", "Android выбирает её сам"),
            PcOnly("Режим воспроизведения только аудио", "Android не даёт эксклюзивный доступ к звуку"),
            PcOnly("Режим ReplayGain", "Только в mpv на компьютере"),
        )),
        Section("Эквалайзер", listOf(
            Toggle("Эквалайзер", null, "eq", false),
            Custom { SettingRow("Предустановка, группы, предварительный усилитель", "Открыть эквалайзер",
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.subtext) }) { nav.to("equalizer") } },
            Toggle("Компрессор", "Выравнивает громкость тихих и громких мест (Android 9+)", "s_compressor", false),
            Slide("Порог", null, "s_comp_threshold", -24, -60..0, 1, "дБ", visible = { S.compressor }),
            Slide("Соотношение", null, "s_comp_ratio", 4, 1..20, 1, ":1", visible = { S.compressor }),
            Slide("Атака", null, "s_comp_attack", 10, 1..200, 1, "мс", visible = { S.compressor }),
            Slide("Выпуск", null, "s_comp_release", 250, 10..1000, 10, "мс", visible = { S.compressor }),
            Slide("Компенсация усиления", null, "s_comp_makeup", 0, 0..24, 1, "дБ", visible = { S.compressor }),
            Slide("Колено", null, "s_comp_knee", 6, 0..30, 1, "дБ", visible = { S.compressor }),
        )),
        Section("Фильтры плеера", listOf(Custom { PlayerFilters() })),
        Section("Auto DJ", listOf(
            Toggle("Auto DJ", "Когда очередь заканчивается — добавлять похожие треки (для YouTube — YouTube Mix)", "auto_dj", false),
            Slide("Количество элементов", null, "s_autodj_count", 10, 1..50, 1, "", visible = { S.bool("auto_dj", false) }),
            Choice("Режим", null, "s_autodj_mode", "songs", { listOf("songs" to "Песни", "albums" to "Альбомы") }, visible = { S.bool("auto_dj", false) }),
            Choice("Режим выбора песен", null, "s_autodj_song", "similar",
                { listOf("similar" to "Похожие", "genre" to "Тот же жанр", "random" to "Случайные") }, visible = { S.bool("auto_dj", false) && S.autoDjMode == "songs" }),
            Choice("Режим выбора альбома", null, "s_autodj_album", "similar",
                { listOf("similar" to "Того же исполнителя", "random" to "Случайные") }, visible = { S.bool("auto_dj", false) && S.autoDjMode == "albums" }),
            Slide("Время", "Добавлять, когда в очереди осталось треков", "s_autodj_timing", 1, 1..10, 1, "", visible = { S.bool("auto_dj", false) }),
            Toggle("Разрешить дубликаты", "Добавлять треки, которые уже были в очереди", "s_autodj_duplicates", false, visible = { S.bool("auto_dj", false) }),
            Toggle("Только похожие", "Не добавлять случайные, если похожих не нашлось", "s_autodj_only_similar", false, visible = { S.bool("auto_dj", false) }),
            Toggle("Скачивать то, что слушаю", "YouTube-треки сохраняются на телефон в фоне, пока играют", "auto_download", false),
        )),
    )

    PcTab.HOTKEYS -> listOf(
        Section("Горячие клавиши", listOf(
            PcOnly("Глобальные мультимедийные горячие клавиши", "На телефоне работают кнопки гарнитуры, Bluetooth и экрана блокировки"),
            PcOnly("Включить медиа-сессию", "На телефоне медиа-сессия всегда включена: без неё нет кнопок в шторке"),
            PcOnly("Горячие клавиши приложения", "У телефона нет клавиатурных сочетаний. Жесты: зажмите трек — меню, смахните вправо — играть далее, влево — в очередь; в плеере смахните обложку — следующий / предыдущий, двойное касание — в избранное"),
        )),
    )

    PcTab.WINDOW -> listOf(
        Section("Окно", listOf(
            PcOnly("Стиль панели окна", ON_PC_WINDOW),
            PcOnly("Информация о треке в заголовке окна", ON_PC_WINDOW),
            PcOnly("Показывать в трее", ON_PC_WINDOW),
            PcOnly("Свернуть в системный трей", ON_PC_WINDOW),
            PcOnly("Запускать в свернутом виде", ON_PC_WINDOW),
            Toggle("Предотвращать спящий режим экрана", "Экран не гаснет, пока играет музыка", "s_prevent_sleep", false),
            Toggle("Предотвращать спящий режим системы", "Телефон не засыпает при воспроизведении по сети", "s_prevent_suspend", true) { vm.player.applyAudioSettings() },
        )),
        Section("Discord", listOf(PcOnly("Отображать расширенный статус в Discord", "Discord не даёт приложениям на телефоне показывать статус"))),
        Section("Удалённый", listOf(PcOnly("Включить сервер удалённого управления", "Управлять телефоном с компьютера — «Где играет»"))),
    )

    PcTab.ADVANCED -> listOf(
        Section("Обновления", listOf(
            Toggle("Проверять обновления", "При запуске искать новую версию на GitHub", "s_check_updates", true),
            Custom { UpdateCheck() },
        )),
        Section("Импорт/экспорт", listOf(Custom { ExportImport(vm) })),
        Section("Отладка", listOf(
            Choice("Уровень логирования", null, "s_log_level", "info",
                { listOf("debug" to "Отладка", "info" to "Информация", "warn" to "Предупреждения", "error" to "Ошибки") }),
        )),
        Section("Кэш", listOf(Custom {
            SettingRow("Очистить кэш", "Обложки и сохранённые списки библиотеки загрузятся заново",
                trailing = { Icon(Icons.Default.Delete, null, tint = Palette.subtext) }) { vm.clearCache() }
        })),
        Section("YouTube и Twitch", listOf(Custom { StreamsSettings(vm) })),
        Section("Свой CSS", listOf(PcOnly("Свой CSS", "Стили CSS есть только в версии для компьютера"))),
        Section("О приложении", listOf(Custom {
            Text("TesuPlayer ${BuildConfig.VERSION_NAME}\nФорк Feishin (github.com/jeffvli/feishin), GPL-3.0", color = Palette.subtext)
        })),
    )
}

// ---------------------------------------------------------------- screens

@Composable
fun SettingsScreen(vm: AppViewModel, nav: Nav) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(PcTab.entries) { t ->
            Row(Modifier.fillMaxWidth().clickable { nav.to("settings/${t.name}") }.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface0), contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, tint = Palette.mauve)
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(t.title, fontWeight = FontWeight.SemiBold)
                    Text(sections(t, vm, nav).joinToString(" · ") { it.title }, color = Palette.subtext, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.subtext)
            }
        }
    }
}

@Composable
fun SettingsSectionScreen(vm: AppViewModel, nav: Nav, section: String) {
    val tab = PcTab.entries.firstOrNull { it.name == section } ?: PcTab.GENERAL
    val list = sections(tab, vm, nav)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        list.forEach { s ->
            item(key = "title:${s.title}") { SectionTitle(s.title) }
            items(s.items.size, key = { "${s.title}:$it" }) { i ->
                val item = s.items[i]
                if (item.visible()) RenderItem(item)
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun RenderItem(item: Item) {
    when (item) {
        is Toggle -> SettingRow(item.title, item.desc, trailing = {
            Switch(checked = S.bool(item.key, item.default), onCheckedChange = { S.set(item.key, it); item.onSet?.invoke(it) })
        }) { val v = !S.bool(item.key, item.default); S.set(item.key, v); item.onSet?.invoke(v) }
        is Slide -> {
            var value by remember(item.key) { mutableFloatStateOf(S.int(item.key, item.default).toFloat()) }
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("${item.title}: ${value.toInt()} ${item.unit}".trim(), fontWeight = FontWeight.SemiBold)
                if (item.desc != null) Text(item.desc, color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
                Slider(value = value, valueRange = item.range.first.toFloat()..item.range.last.toFloat(),
                    onValueChange = { value = (Math.round(it / item.step) * item.step).toFloat() },
                    onValueChangeFinished = { S.set(item.key, value.toInt()) })
            }
        }
        is Choice -> {
            var open by remember { mutableStateOf(false) }
            val options = item.options()
            val current = S.string(item.key, item.default)
            SettingRow(item.title, options.firstOrNull { it.first == current }?.second ?: current,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.subtext) }) { open = true }
            if (open) {
                AlertDialog(
                    onDismissRequest = { open = false },
                    title = { Text(item.title) },
                    text = {
                        LazyColumn {
                            items(options) { (value, label) ->
                                Row(Modifier.fillMaxWidth().clickable { S.set(item.key, value); item.onSet?.invoke(value); open = false }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = value == current, onClick = { S.set(item.key, value); item.onSet?.invoke(value); open = false })
                                    Text(label)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } },
                )
            }
        }
        is Custom -> item.content()
        is PcOnly -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, color = Palette.subtext)
                Text("На компьютере. ${item.why}", color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.Computer, null, tint = Palette.surface1)
        }
    }
}

@Composable
fun SettingRow(title: String, description: String? = null, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (description != null) Text(description, color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

// ---------------------------------------------------------------- custom items

@Composable
private fun ColorField(title: String, key: String, default: String, onSet: () -> Unit) {
    var text by remember { mutableStateOf(S.string(key, default)) }
    val color = DesktopThemes.css(text)
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color ?: Palette.surface0))
        OutlinedTextField(text, { text = it; if (DesktopThemes.css(it) != null) { S.set(key, it.trim()); onSet() } },
            label = { Text(title) }, singleLine = true, isError = color == null, modifier = Modifier.padding(start = 12.dp).fillMaxWidth())
    }
}

@Composable
private fun ShadeSlider(vm: AppViewModel) {
    var shade by remember { mutableFloatStateOf(S.primaryShade.toFloat()) }
    Column {
        Text("Основной оттенок: ${shade.toInt()}", fontWeight = FontWeight.SemiBold)
        Text("0 — светлее, 9 — темнее, 6 — как в теме", color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        Slider(value = shade, valueRange = 0f..9f, steps = 8, onValueChange = { shade = it },
            onValueChangeFinished = { S.set("s_primary_shade", shade.toInt()); vm.refreshTheme() })
    }
}

@Composable
private fun ThemeSwatch(label: String, colors: ThemeColors?, selected: Boolean, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = colors ?: Palette.colors
    Column(Modifier.width(96.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(88.dp, 60.dp).clip(RoundedCornerShape(12.dp)).background(c.base)
                .border(if (selected) 3.dp else 1.dp, if (selected) Palette.mauve else Palette.surface1, RoundedCornerShape(12.dp)),
        ) {
            Box(Modifier.padding(8.dp).size(40.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(c.text))
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(28.dp, 14.dp).clip(RoundedCornerShape(7.dp)).background(c.mauve))
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(20.dp, 14.dp).clip(RoundedCornerShape(4.dp)).background(c.surface0))
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                    Icon(Icons.Default.Delete, "Удалить", tint = c.subtext, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = if (selected) Palette.mauve else Palette.text)
    }
}

/** Theme previews, the desktop .json import and the custom three colours */
@Composable
private fun ThemeFiles(vm: AppViewModel) {
    val prefs = vm.prefs
    val context = LocalContext.current
    val themeId by vm.themeId.collectAsState()
    val imported by vm.importedThemes.collectAsState()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "theme"
        if (text == null) vm.toast("Не удалось прочитать файл") else vm.importTheme(name, text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Text("Темы компьютера", fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AppTheme.entries.filter { it != AppTheme.CUSTOM }) { t -> ThemeSwatch(t.label, t.colors, themeId == t.name) { vm.setTheme(t) } }
            items(DesktopThemes.builtIn) { t -> ThemeSwatch(t.label, t.colors, themeId == "desktop:${t.id}") { vm.setThemeId("desktop:${t.id}") } }
        }
        Text("Свои темы", fontWeight = FontWeight.SemiBold)
        if (imported.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(imported.keys.toList()) { name ->
                    ThemeSwatch(DesktopThemes.label(name), DesktopThemes.fromFile(name, imported[name]!!)?.colors, themeId == "file:$name",
                        onDelete = { vm.removeTheme(name) }) { vm.setThemeId("file:$name") }
                }
            }
        }
        PillButton("Импорт темы (.json)", Icons.Default.Upload, { importFile.launch(arrayOf("application/json", "text/*", "*/*")) }, primary = false)
        Text("Тот же файл, что в папке Themes на компьютере (Настройки → Общие → Тема → Открыть папку).",
            color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        Text("Своя из трёх цветов", fontWeight = FontWeight.SemiBold)
        val start = (prefs.customTheme ?: "#24273A,#C6A0F6,#CAD3F5").split(',')
        val fields = remember { mutableStateListOf(*start.toTypedArray()) }
        listOf("Фон", "Акцент", "Текст").forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = DesktopThemes.css(fields[i])
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color ?: Palette.surface0))
                OutlinedTextField(fields[i], { fields[i] = it }, label = { Text(label) }, singleLine = true,
                    isError = color == null, modifier = Modifier.padding(start = 12.dp).fillMaxWidth())
            }
        }
        PillButton("Применить свою", Icons.Default.Add, {
            val custom = fields.joinToString(",") { it.trim() }
            if (Palette.parseCustom(custom) != null) vm.setTheme(AppTheme.CUSTOM, custom) else vm.toast("Неверный цвет")
        }, primary = false)
    }
}

@Composable
private fun FontFilePicker() {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val target = java.io.File(context.filesDir, "custom-font.ttf")
            context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            S.set("s_custom_font", target.absolutePath + "?" + System.currentTimeMillis())
        }
    }
    SettingRow("Пользовательский путь к шрифту", if (S.customFontPath.isBlank()) "Файл .ttf или .otf" else "Шрифт загружен",
        trailing = { Icon(Icons.Default.Upload, null, tint = Palette.subtext) }) { pick.launch(arrayOf("font/*", "application/octet-stream", "*/*")) }
}

@Composable
private fun HiddenToggle(vm: AppViewModel, title: String, desc: String, part: UiPart) {
    val hidden by vm.hidden.collectAsState()
    SettingRow(title, desc, trailing = { Switch(checked = part !in hidden, onCheckedChange = { vm.setHidden(part, !it) }) }) {
        vm.setHidden(part, part !in hidden)
    }
}

@Composable
private fun VisualizerToggle(vm: AppViewModel) {
    val context = LocalContext.current
    val visualizer by vm.visualizer.collectAsState()
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setVisualizer(true) else vm.toast("Без доступа к микрофону Android не даёт читать звук")
    }
    SettingRow("Отображать визуализатор в боковой панели плеера", "Спектр поверх обложки. Android просит доступ к микрофону: приложение слушает только свой звук", trailing = {
        Switch(checked = visualizer, onCheckedChange = { on ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!on || granted) vm.setVisualizer(on) else microphone.launch(Manifest.permission.RECORD_AUDIO)
        })
    })
}

/** The rest of the phone's own interface switches and the home sections */
@Composable
private fun InterfaceParts(vm: AppViewModel) {
    val hidden by vm.hidden.collectAsState()
    Column {
        UiPart.entries.filter { it != UiPart.PLAYER_QUEUE && it != UiPart.PLAYER_LYRICS }.forEach { part ->
            SettingRow(part.label, trailing = { Switch(checked = part !in hidden, onCheckedChange = { vm.setHidden(part, !it) }) }) {
                vm.setHidden(part, part !in hidden)
            }
        }
        Text("Разделы главной", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        val sections = remember { mutableStateListOf<HomeSection>().apply { addAll(vm.prefs.homeSections) } }
        HomeSection.entries.forEach { s ->
            SettingRow(s.label, trailing = {
                Switch(checked = s in sections, onCheckedChange = { on ->
                    if (on) sections.add(s) else sections.remove(s)
                    vm.prefs.homeSections = HomeSection.entries.filter { it in sections }
                })
            })
        }
    }
}

@Composable
private fun LyricProviders() {
    val providers = S.lyricProviders
    Column {
        Text("Провайдеры для получения текстов песен", fontWeight = FontWeight.SemiBold)
        listOf("lrclib" to "lrclib.net", "netease" to "NetEase").forEach { (id, label) ->
            SettingRow(label, trailing = {
                Switch(checked = id in providers, onCheckedChange = { on -> S.set("s_lyric_providers", if (on) providers + id else providers - id) })
            })
        }
    }
}

@Composable
private fun AudioDeviceChoice() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val devices = remember(open) {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
    fun label(d: AudioDeviceInfo) = "${d.productName}".ifBlank { "Устройство ${d.id}" } + when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> " (динамик)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> " (наушники)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> " (Bluetooth)"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> " (USB)"
        else -> ""
    }
    val current = S.audioDevice
    SettingRow("Аудиоустройство", devices.firstOrNull { it.id == current }?.let(::label) ?: "Как в системе",
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.subtext) }) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Аудиоустройство") },
            text = {
                LazyColumn {
                    item {
                        Row(Modifier.fillMaxWidth().clickable { S.set("s_audio_device", 0); open = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = current == 0, onClick = { S.set("s_audio_device", 0); open = false }); Text("Как в системе")
                        }
                    }
                    items(devices) { d ->
                        Row(Modifier.fillMaxWidth().clickable { S.set("s_audio_device", d.id); open = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = current == d.id, onClick = { S.set("s_audio_device", d.id); open = false }); Text(label(d))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } },
        )
    }
}

/** Desktop "player filters": songs whose field contains the text are not added to the queue */
@Composable
private fun PlayerFilters() {
    val rules = S.playerFilters.toList()
    var field by remember { mutableStateOf("title") }
    var text by remember { mutableStateOf("") }
    val fields = listOf("title" to "Название", "artist" to "Исполнитель", "album" to "Альбом", "genre" to "Жанр")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Фильтр песен в очереди воспроизведения: такие треки пропускаются при добавлении в очередь", color = Palette.subtext,
            style = MaterialTheme.typography.bodySmall)
        rules.forEach { rule ->
            val (f, t) = rule.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fields.firstOrNull { it.first == f }?.second ?: f} содержит «$t»", Modifier.weight(1f))
                IconButton(onClick = { S.set("s_player_filters", S.playerFilters - rule) }) { Icon(Icons.Default.Delete, "Удалить", tint = Palette.subtext) }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(fields) { (id, label) -> Chip(label, field == id) { field = id } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, label = { Text("Содержит") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (text.isNotBlank()) { S.set("s_player_filters", S.playerFilters + "$field:${text.trim()}"); text = "" } }) {
                Icon(Icons.Default.Add, "Добавить", tint = Palette.mauve)
            }
        }
    }
}

@Composable
private fun UpdateCheck() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    SettingRow("Проверить обновления", status ?: "Версия ${BuildConfig.VERSION_NAME}",
        trailing = { Icon(Icons.Default.Download, null, tint = Palette.subtext) }) {
        scope.launch {
            status = "Проверяю…"
            val latest = Updates.latest(context)
            status = when {
                latest == null -> "Не удалось проверить"
                !latest.newer -> "Установлена последняя версия"
                else -> { Updates.open(context, latest); "Доступна ${latest.version}: открываю загрузку" }
            }
        }
    }
}

@Composable
private fun ExportImport(vm: AppViewModel) {
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(vm.exportSettings()) } }
            .onSuccess { vm.toast("Настройки сохранены") }.onFailure { vm.toast("Не удалось сохранить файл") }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            .onSuccess(vm::importSettings).onFailure { vm.toast("Не удалось прочитать файл") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Импорт/экспорт настроек: файл для переноса на другой телефон (подключение к компьютеру не сохраняется).",
            color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Экспорт", Icons.Default.Download, { export.launch("tesuplayer-settings.json") }, primary = false)
            PillButton("Импорт", Icons.Default.Upload, { import.launch(arrayOf("application/json", "*/*")) }, primary = false)
        }
    }
}

// ---------------------------------------------------------------- My music

@Composable
private fun LibrarySettings(vm: AppViewModel) {
    val server by vm.server.collectAsState()
    val reachable by vm.pcReachable.collectAsState()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("tesu") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Мой компьютер", fontWeight = FontWeight.SemiBold)
        val s = server
        if (s != null) {
            Text("${s.url}\n${s.username} · ${if (reachable) "на связи" else "не отвечает"}", color = Palette.subtext)
            PillButton("Отключить", Icons.Default.Delete, { vm.disconnect() }, primary = false)
            Text("«Отправить на компьютер» в меню трека: файл с тегами и обложкой попадёт в «TesuPlayer/С телефона». Треки, которые на компьютере уже есть, не дублируются.",
                color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Подключите TesuPlayer на ПК: его музыка, общее избранное и плейлисты, «Где играет». На ПК: Настройки → Моя музыка → «Доступ с других устройств».",
                color = Palette.subtext)
            OutlinedTextField(url, { url = it }, label = { Text("Адрес") }, placeholder = { Text("192.168.1.10:45173") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            PillButton(if (busy) "Подключаюсь…" else "Подключить", Icons.Default.PlayArrow, {
                if (!busy && url.isNotBlank()) scope.launch {
                    busy = true
                    vm.toast(runCatching { vm.connect(url, user, password) }.fold({ "Компьютер подключён" }, { "Не удалось: ${it.message}" }))
                    busy = false
                }
            })
        }
        SpotifySettings(vm)
        Text("Скачанное с YouTube", fontWeight = FontWeight.SemiBold)
        PillButton("Исправить теги скачанного", Icons.Default.Sell, { vm.retagDownloads() }, primary = false)
        Text("Заново ищет исполнителя, альбом и обложку в каталоге iTunes.", color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StreamsSettings(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ytdlp by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ytdlp = Streams.version(context) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("yt-dlp: ${ytdlp ?: "готовится…"}", color = Palette.subtext)
        PillButton(if (updating) "Обновляю…" else "Обновить yt-dlp", Icons.Default.Download, {
            if (!updating) scope.launch {
                updating = true
                vm.toast(runCatching { Streams.update(context) }.fold({ "yt-dlp: $it" }, { "Ошибка: ${it.message}" }))
                ytdlp = Streams.version(context)
                updating = false
            }
        }, primary = false)
        Text("Если YouTube перестал играть — обновите yt-dlp. Если не ищет — включите «Частный DNS» (dns.google) в настройках Android.",
            color = Palette.subtext, style = MaterialTheme.typography.bodySmall)
    }
}
