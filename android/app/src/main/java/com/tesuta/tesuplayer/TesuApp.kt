package com.tesuta.tesuplayer

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tesuta.tesuplayer.data.Downloads
import com.tesuta.tesuplayer.data.Prefs
import com.tesuta.tesuplayer.data.Streams
import com.tesuta.tesuplayer.player.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

class TesuApp : Application() {
    lateinit var prefs: Prefs
    lateinit var downloads: Downloads
    lateinit var player: PlayerController

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        downloads = Downloads(this)
        player = PlayerController(this)
        // Unpacking Python takes a few seconds on first launch
        thread(name = "yt-dlp-init") { Streams.init(this) }
        watchNetwork()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        fun check() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        check()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check()
            override fun onLost(network: Network) { _online.value = false }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = check()
        })
    }
}
