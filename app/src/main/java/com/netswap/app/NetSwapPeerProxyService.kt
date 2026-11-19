package com.netswap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.IBinder
import org.webrtc.PeerConnection
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the approved peer data channel. On the receiver, Android's IPv4 default
 * route is piped through Hev into the local SOCKS5 proxy and then WebRTC.
 */
class NetSwapPeerProxyService : VpnService() {
    companion object {
        const val ACTION_START = "com.netswap.app.action.START_PEER_PROXY"
        const val ACTION_STOP = "com.netswap.app.action.STOP_PEER_PROXY"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PROXY = "proxy"
        private const val CHANNEL = "netswap_peer_proxy"
        private const val NOTIFICATION_ID = 7310
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    @Volatile private var controller: WebRtcSessionController? = null
    @Volatile private var endpoint: PeerStreamSocketEndpoint? = null
    @Volatile private var proxy: ReceiverSocks5Proxy? = null
    @Volatile private var receiverTun: ParcelFileDescriptor? = null
    @Volatile private var hevRuntime: HevTunnelRuntime? = null
    @Volatile private var hevPair: HevPacketSocketPair? = null
    @Volatile private var hevRelay: HevTunPacketRelay? = null

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "NetSwap peer connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPeer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId.isNullOrBlank() || controller != null) {
            if (requestId.isNullOrBlank()) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Connecting to approved NetSwap peer…"))
        worker.execute { startPeer(requestId) }
        return START_NOT_STICKY
    }

    private fun startPeer(requestId: String) {
        try {
            val prefs = getSharedPreferences("netswap_session", MODE_PRIVATE)
            val refresh = prefs.getString("refresh_token", null) ?: error("Please log in again.")
            val access = prefs.getString("access_token", "") ?: ""
            val userId = prefs.getString("user_id", null) ?: error("Please log in again.")
            val email = prefs.getString("email", "") ?: ""
            var session = if (access.isBlank()) NetSwapApi.refresh(refresh)
                else AuthSession(access, refresh, userId, email)
            if (access.isBlank()) {
                prefs.edit().putString("access_token", session.accessToken)
                    .putString("refresh_token", session.refreshToken).apply()
            }
            val requestList = try {
                NetSwapApi.requests(session)
            } catch (_: Exception) {
                session = NetSwapApi.refresh(refresh)
                prefs.edit().putString("access_token", session.accessToken)
                    .putString("refresh_token", session.refreshToken).apply()
                NetSwapApi.requests(session)
            }
            val request = requestList.firstOrNull {
                it.id == requestId && it.status == "approved" &&
                    (it.senderId == session.userId || it.receiverId == session.userId)
            } ?: error("Approved sharing request not found. Refresh the request list.")
            check(!stopped.get()) { "Connection cancelled." }

            val peerConnected = CountDownLatch(1)
            val peerController = WebRtcSessionController(
                context = this,
                session = session,
                request = request,
                onState = { state ->
                    if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                        peerConnected.countDown()
                        publish(true, "Peer connected. Receiver proxy is available on localhost.")
                    } else if (state == PeerConnection.PeerConnectionState.FAILED ||
                        state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        publish(false, "Peer connection is not active. Check both phones' internet.")
                    }
                },
                onMessage = {},
                onError = { publish(false, it.take(180)) }
            )
            controller = peerController

            if (session.userId == request.senderId) {
                val network = CellularUpstreamNetwork.find(this)
                    ?: error("Sender phone needs an active cellular internet connection.")
                val quota = SessionTrafficQuota(request.quotaBytes)
                val senderEndpoint = PeerStreamSocketEndpoint(
                    vpnService = this,
                    upstreamNetwork = network,
                    quota = quota,
                    sendData = { id, bytes -> peerController.sendTcpData(id, bytes) },
                    sendClose = { id -> peerController.closeStream(id) },
                    sendError = { id, message -> peerController.sendStreamError(id, message) }
                )
                endpoint = senderEndpoint
                peerController.attachSocketEndpoint(senderEndpoint)
                val udpEndpoint = PeerStreamDatagramEndpoint(
                    vpnService = this,
                    upstreamNetwork = network,
                    quota = quota,
                    sendData = { id, bytes -> peerController.sendUdpData(id, bytes) },
                    sendError = { id, message -> peerController.sendStreamError(id, message) }
                )
                peerController.attachDatagramEndpoint(udpEndpoint)
            } else {
                val receiverProxy = ReceiverSocks5Proxy(
                    openTcp = { id, destination -> peerController.openTcp(id, destination) },
                    sendData = { id, bytes -> peerController.sendTcpData(id, bytes) },
                    closePeerStream = { id -> peerController.closeStream(id) },
                    openUdp = { id, destination -> peerController.openUdp(id, destination) },
                    sendUdpData = { id, bytes -> peerController.sendUdpData(id, bytes) }
                )
                proxy = receiverProxy
                peerController.attachReceiverProxy(receiverProxy)
                val info = receiverProxy.start()
                peerController.start()
                check(peerConnected.await(45, TimeUnit.SECONDS)) { "Peer did not connect within 45 seconds. VPN route was not installed." }
                val channelDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (!peerController.isDataChannelOpen() && System.nanoTime() < channelDeadline && !stopped.get()) {
                    Thread.sleep(200)
                }
                check(peerController.isDataChannelOpen()) { "Peer connected, but secure data channel did not open. VPN route was not installed." }
                // Wait for ICE gathering to finish before deciding whether TURN allocated a relay.
                // A short fixed delay can falsely report unverified on slow mobile networks.
                val iceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (!peerController.isIceGatheringComplete() && System.nanoTime() < iceDeadline && !stopped.get()) {
                    Thread.sleep(200)
                }
                startReceiverDeviceTunnel(info.port)
                val turnStatus = if (peerController.hasTurnRelayCandidate()) {
                    "TURN authentication verified; "
                } else {
                    "Direct peer path active; TURN relay not verified on this network; "
                }
                publish(true, turnStatus + "NetSwap device-wide IPv4 forwarding is active.")
            }

            if (!stopped.get()) peerController.start()
            else peerController.close()
        } catch (failure: Exception) {
            publish(false, failure.message ?: "Peer connection could not start.")
            stopPeer()
            stopSelf()
        }
    }

    /** Installs an IPv4 default route and pipes packets through Hev -> local SOCKS5 -> WebRTC. */
    private fun startReceiverDeviceTunnel(socksPort: Int) {
        check(VpnService.prepare(this) == null) { "Android VPN permission is required." }
        val tun = Builder()
            .setSession("NetSwap internet sharing")
            .setMtu(1400)
            .addAddress("198.18.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fc00::2", 128)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDisallowedApplication(packageName)
            .establish() ?: error("Android could not establish receiver VPN interface.")
        receiverTun = tun
        val pair = HevPacketSocketPair.create()
        hevPair = pair
        val runtime = HevTunnelRuntime(this)
        hevRuntime = runtime
        runtime.start(pair.hevDescriptor, "127.0.0.1", socksPort)
        val androidDevice = AndroidTunPacketDevice.from(tun)
        val relay = HevTunPacketRelay(androidDevice, pair.packetDevice) { reason ->
            publish(false, "Packet tunnel stopped: ${reason.take(120)}")
        }
        hevRelay = relay
        relay.start()
    }

    private fun publish(active: Boolean, message: String) {
        val state = Intent(NetSwapUserspaceVpnService.ACTION_STATE)
            .setPackage(packageName)
            .putExtra(NetSwapUserspaceVpnService.EXTRA_ACTIVE, active)
            .putExtra(NetSwapUserspaceVpnService.EXTRA_ERROR, message)
        sendBroadcast(state)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("NetSwap peer session")
            .setContentText(message.take(120))
            .setContentIntent(open).setOngoing(true).build()
    }

    private fun stopPeer() {
        if (!stopped.compareAndSet(false, true)) return
        try { controller?.close() } catch (_: Exception) {}
        controller = null
        try { hevRelay?.close() } catch (_: Exception) {}
        hevRelay = null
        try { hevRuntime?.close() } catch (_: Exception) {}
        hevRuntime = null
        try { hevPair?.close() } catch (_: Exception) {}
        hevPair = null
        try { receiverTun?.close() } catch (_: Exception) {}
        receiverTun = null
        try { proxy?.close() } catch (_: Exception) {}
        proxy = null
        try { endpoint?.close() } catch (_: Exception) {}
        endpoint = null
        publish(false, "NetSwap peer session stopped.")
    }

    override fun onRevoke() {
        stopPeer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopPeer()
        worker.shutdownNow()
        super.onDestroy()
    }
}
