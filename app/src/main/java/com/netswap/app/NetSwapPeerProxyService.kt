package com.netswap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import org.webrtc.PeerConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the peer data channel for an approved request and exposes a loopback
 * SOCKS5 proxy on the receiver. This is app-level TCP proxying, not device-wide
 * VPN forwarding. No default route is installed.
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

            val peerController = WebRtcSessionController(
                context = this,
                session = session,
                request = request,
                onState = { state ->
                    if (state == PeerConnection.PeerConnectionState.CONNECTED) {
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
                    closePeerStream = { id -> peerController.closeStream(id) }
                )
                proxy = receiverProxy
                peerController.attachReceiverProxy(receiverProxy)
                val info = receiverProxy.start()
                publish(false, "Receiver proxy: socks5://${info.host}:${info.port}")
            }

            if (!stopped.get()) peerController.start()
            else peerController.close()
        } catch (failure: Exception) {
            publish(false, failure.message ?: "Peer connection could not start.")
            stopPeer()
            stopSelf()
        }
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
