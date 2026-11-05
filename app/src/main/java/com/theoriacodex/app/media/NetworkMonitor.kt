package com.theoriacodex.app.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Process-lifetime view of whether the default network has confirmed internet. It decides when to
 * retry and how to word a failure; requests are never blocked on it, so a wrong reading costs no feed.
 */
internal class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow(connectivity.getNetworkCapabilities(connectivity.activeNetwork).hasInternet())
    val online: StateFlow<Boolean> = mutableOnline.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                mutableOnline.value = capabilities.hasInternet()
            }

            override fun onLost(network: Network) {
                mutableOnline.value = false
            }
        })
    }

    private fun NetworkCapabilities?.hasInternet(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Emits once each time the network comes back, and never for the first reading. */
internal fun Flow<Boolean>.reconnections(): Flow<Unit> = flow {
    var wasOnline: Boolean? = null
    collect { online ->
        if (online && wasOnline == false) emit(Unit)
        wasOnline = online
    }
}
