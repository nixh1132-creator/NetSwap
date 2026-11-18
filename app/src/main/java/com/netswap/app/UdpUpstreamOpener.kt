package com.netswap.app

import java.net.DatagramSocket

/** Opens UDP sockets that are protected from the VPN and bound to the chosen upstream network. */
fun interface UdpUpstreamOpener {
    fun openUdp(): DatagramSocket
}
