package com.netswap.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Finds a validated upstream Internet network for the sender.
 * Prefer cellular, but allow Wi-Fi when mobile data is unavailable.
 */
object CellularUpstreamNetwork {
    fun find(context: Context): Network? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null

        return manager.allNetworks
            .mapNotNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return@mapNotNull null
                }
                network to caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            .sortedByDescending { it.second }
            .firstOrNull()?.first
    }
}
