package com.tesuta.tesuplayer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.tesuta.tesuplayer.data.S
import com.tesuta.tesuplayer.data.Updates
import kotlinx.coroutines.delay
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tesuta.tesuplayer.R
import com.tesuta.tesuplayer.data.UiPart
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // music on the phone + playback notification
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.permissionsChanged() }
            .launch(permissions)
        handleShare(intent)
        setContent { TesuTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.value?.let(vm::playLink)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val part: UiPart? = null)

private val tabs = listOf(
    Tab("home", "Главная", Icons.Default.Album),
    Tab("library", "Библиотека", Icons.AutoMirrored.Filled.QueueMusic),
    Tab("search", "Поиск", Icons.Default.Search),
    Tab("devices", "Где играет", Icons.Default.Devices, UiPart.TAB_DEVICES),
    Tab("downloads", "Загрузки", Icons.Default.DownloadForOffline, UiPart.TAB_DOWNLOADS),
)

private val titles = mapOf(
    "settings" to "Настройки", "settings/{section}" to "Настройки", "equalizer" to "Эквалайзер", "albums" to "Альбомы", "artists" to "Исполнители",
    "songs" to "Треки", "genres" to "Жанры", "folders" to "Папки", "favorites" to "Избранное",
    "playlists" to "Плейлисты", "radio" to "Радиостанции", "album/{id}" to "Альбом", "artist/{id}" to "Исполнитель",
    "artist_songs/{id}" to "Все треки", "folder/{id}" to "Папка",
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val online by vm.online.collectAsState()
    val permissionsVersion by vm.permissionsVersion.collectAsState()

    val nav = rememberNavController()
    // offline at launch: open straight on downloads
    val startRoute = remember { if (online) "home" else "downloads" }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTab = tabs.any { it.route == route }
    val playerState by vm.player.state.collectAsState()
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val hidden by vm.hidden.collectAsState()

    val context = LocalContext.current
    val view = LocalView.current
    val go = remember(nav) { Nav { r -> nav.navigate(r) } }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.toast(null) } }
    // "Предотвращать спящий режим экрана"
    val keepOn = S.preventSleep && playerState.isPlaying
    LaunchedEffect(keepOn) { view.keepScreenOn = keepOn }
    // "Проверять обновления"
    LaunchedEffect(Unit) {
        if (!S.checkUpdates) return@LaunchedEffect
        val latest = Updates.latest(context)?.takeIf { it.newer } ?: return@LaunchedEffect
        if (snackbar.showSnackbar("Доступна новая версия ${latest.version}", "Скачать", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            Updates.open(context, latest)
        }
    }
    LaunchedEffect(playerState.error) { playerState.error?.let { snackbar.showSnackbar("Ошибка: $it"); vm.player.clearError() } }

    CompositionLocalProvider(LocalVm provides vm, LocalNav provides go) {
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        // any touch resets "Время ожидания открытия полноэкранного плеера"
        awaitPointerEventScope {
            while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch = System.currentTimeMillis() }
        }
    }) {
        // the full player is a sheet that follows the finger: 0 = open, height = closed
        val height = constraints.maxHeight.toFloat()
        val scope = rememberCoroutineScope()
        val sheet = remember { Animatable(if (fullPlayer) 0f else height) }
        val sheetVisible by remember { derivedStateOf { sheet.value < height } }
        LaunchedEffect(height) { sheet.updateBounds(0f, height); sheet.snapTo(if (fullPlayer) 0f else height) }
        fun settle(open: Boolean, velocity: Float = 0f) {
            fullPlayer = open
            scope.launch { sheet.animateTo(if (open) 0f else height, initialVelocity = velocity) }
        }
        fun drag(delta: Float) { scope.launch { sheet.snapTo(sheet.value + delta) } }
        fun release(velocity: Float) = settle(
            when {
                velocity > 1500f -> false
                velocity < -1500f -> true
                else -> sheet.value < height / 2
            },
            velocity,
        )
        LaunchedEffect(Unit) {
            while (true) {
                delay(15_000)
                val minutes = S.fullscreenAutoOpen
                if (minutes > 0 && !fullPlayer && vm.player.state.value.isPlaying &&
                    System.currentTimeMillis() - lastTouch > minutes * 60_000L) settle(true)
            }
        }
        val dragState = rememberDraggableState(::drag)
        val sheetDrag = Modifier.draggable(dragState, Orientation.Vertical, onDragStopped = { release(it) })
        // lyrics / queue / similar lists: pulling down at their top drags the player
        val sheetScroll = remember(height) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0 || sheet.value <= 0f) return Offset.Zero
                    drag(available.y)
                    return Offset(0f, available.y)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                    drag(available.y)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (sheet.value <= 0f) return Velocity.Zero
                    release(available.y)
                    return available
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (route) {
                                null -> ""
                                else -> tabs.find { it.route == route }?.label ?: titles[route]
                                    ?: entry?.arguments?.getString("name") ?: ""
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        if (!isTab) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        else Image(painterResource(R.mipmap.ic_launcher), null,
                            Modifier.padding(start = 12.dp, end = 4.dp).size(32.dp).clip(RoundedCornerShape(8.dp)))
                    },
                    actions = {
                        if (route != "settings") IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.base),
                )
            },
            bottomBar = {
                Column {
                    if (playerState.hasMedia || playerState.loading) {
                        Box(sheetDrag) { MiniPlayer(playerState, vm.player, UiPart.MINI_PROGRESS !in hidden) { settle(true) } }
                    }
                    NavigationBar(containerColor = Palette.mantle) {
                        tabs.filter { it.part == null || it.part !in hidden }.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { nav.navigateTab(tab.route) },
                                icon = { Icon(tab.icon, tab.label) },
                                label = { Text(tab.label, fontSize = 11.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Palette.mantle, indicatorColor = Palette.mauve,
                                    selectedTextColor = Palette.mauve,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (!online) OfflineBanner()
                // Navigation already decodes Uri.encode-d arguments
                fun arg(e: androidx.navigation.NavBackStackEntry, key: String) = e.arguments?.getString(key).orEmpty()
                NavHost(nav, startDestination = startRoute, Modifier.weight(1f)) {
                    composable("home") { androidx.compose.runtime.key(permissionsVersion) { HomeScreen(vm, go) } }
                    composable("library") { LibraryScreen(vm, go) }
                    composable("search") { SearchScreen(vm, go) }
                    composable("devices") { DevicesScreen(vm) }
                    composable("downloads") { DownloadsScreen(vm, go) }
                    composable("settings") { SettingsScreen(vm, go) }
                    composable("settings/{section}") { SettingsSectionScreen(vm, go, arg(it, "section")) }
                    composable("equalizer") { EqualizerScreen(vm) }
                    composable("albums") { AlbumsScreen(vm, go) }
                    composable("artists") { ArtistsScreen(vm, go) }
                    composable("songs") { SongsScreen(vm, go) }
                    composable("genres") { GenresScreen(vm, go) }
                    composable("folders") { FoldersScreen(vm, go, null) }
                    composable("favorites") { FavoritesScreen(vm, go) }
                    composable("playlists") { PlaylistsScreen(vm, go) }
                    composable("radio") { RadioScreen(vm) }
                    composable("album/{id}") { AlbumScreen(vm, go, arg(it, "id")) }
                    composable("artist/{id}") { ArtistScreen(vm, go, arg(it, "id")) }
                    composable("artist_songs/{id}") { ArtistSongsScreen(vm, go, arg(it, "id")) }
                    composable("genre/{name}") { GenreScreen(vm, go, arg(it, "name")) }
                    composable("folder/{id}") { FoldersScreen(vm, go, arg(it, "id")) }
                    composable("playlist/{id}/{name}") { PlaylistScreen(vm, go, arg(it, "id"), arg(it, "name")) }
                }
            }
        }

        AddToPlaylistDialog(vm)
        TrackInfoDialog(vm)
        ActionSheetHost()
        val confirm by vm.confirmQueue.collectAsState()
        confirm?.let { yes ->
            AlertDialog(
                onDismissRequest = { vm.confirmQueue.value = null },
                title = { Text("Заменить очередь?") },
                text = { Text("Сейчас в очереди ${playerState.queue.size} треков. Они будут заменены.") },
                confirmButton = { TextButton(onClick = yes) { Text("Заменить") } },
                dismissButton = { TextButton(onClick = { vm.confirmQueue.value = null }) { Text("Отмена") } },
            )
        }

        if (sheetVisible) {
            FullPlayer(
                playerState, vm, Nav { r -> settle(false); nav.navigate(r) },
                Modifier.offset { IntOffset(0, sheet.value.toInt()) }.nestedScroll(sheetScroll).then(sheetDrag),
            ) { settle(false) }
        }
        BackHandler(fullPlayer) { settle(false) }
    }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
