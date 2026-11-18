package com.netswap.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Finds the phone's currently available cellular Internet network.
 *
 * Sender-side upstream sockets must be explicitly bound to this Network so
 * they leave through mobile data instead of being captured by NetSwap's TUN
 * or accidentally using Wi-Fi. A null result is fail-closed: do not start
 * sender forwarding until cellular Internet is available.
 */
object CellularUpstreamNetwork {
    fun find(context: Context): Network? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null

        return manager.allNetworks
            .mapNotNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return@mapNotNull null
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return@mapNotNull null
                }
                network
            }
            .firstOrNull()
    }
}
