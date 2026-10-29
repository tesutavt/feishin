package com.tesuta.tesuplayer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

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

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Музыка", Icons.Default.Album),
    Tab("playlists", "Плейлисты", Icons.AutoMirrored.Filled.QueueMusic),
    Tab("search", "Поиск", Icons.Default.Search),
    Tab("devices", "Где играет", Icons.Default.Devices),
    Tab("downloads", "Загрузки", Icons.Default.DownloadForOffline),
)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

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

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.toast(null) } }
    LaunchedEffect(playerState.error) { playerState.error?.let { snackbar.showSnackbar("Ошибка: $it"); vm.player.clearError() } }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (route) {
                                "settings" -> "Настройки"
                                null -> ""
                                else -> tabs.find { it.route == route }?.label ?: entry?.arguments?.getString("name")?.let(::dec) ?: ""
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
                    if (playerState.hasMedia || playerState.loading) MiniPlayer(playerState, vm.player) { fullPlayer = true }
                    NavigationBar(containerColor = Palette.mantle) {
                        tabs.forEach { tab ->
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
                NavHost(nav, startDestination = startRoute, Modifier.weight(1f)) {
                    composable("home") { androidx.compose.runtime.key(permissionsVersion) { HomeScreen(vm) { id -> nav.navigate("album/$id") } } }
                    composable("playlists") { PlaylistsScreen(vm) { id, name -> nav.navigate("playlist/$id/${enc(name)}") } }
                    composable("search") { SearchScreen(vm) { id -> nav.navigate("album/$id") } }
                    composable("devices") { DevicesScreen(vm) }
                    composable("downloads") { DownloadsScreen(vm) }
                    composable("settings") { SettingsScreen(vm) }
                    composable("album/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                        AlbumScreen(vm, it.arguments?.getString("id").orEmpty())
                    }
                    composable("playlist/{id}/{name}") {
                        PlaylistScreen(vm, it.arguments?.getString("id").orEmpty(), dec(it.arguments?.getString("name").orEmpty()))
                    }
                }
            }
        }

        AnimatedVisibility(fullPlayer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            FullPlayer(playerState, vm.player) { fullPlayer = false }
        }
        BackHandler(fullPlayer) { fullPlayer = false }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
