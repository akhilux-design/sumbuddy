package com.expenses.app.groups.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone can reach the internet now, for Home's offline banner ("You're offline", design
 * 02-everyday/03-home-offline: own expenses still work, splits sync when back online) and for saying "check the
 * connection" before an online-only action. Reads the system's default network only; it sends nothing.
 */
interface NetworkStatus {
    /** True while the default network has internet; starts from the system's current answer. */
    val online: StateFlow<Boolean>
}

/** [NetworkStatus] from Android's default network callback. Made once per process by the container. */
class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyOnline())

    override val online: StateFlow<Boolean> = state.asStateFlow()

    init {
        manager?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    state.value = false
                }
            },
        )
    }

    private fun currentlyOnline(): Boolean {
        val active = manager?.activeNetwork ?: return false
        return manager.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
