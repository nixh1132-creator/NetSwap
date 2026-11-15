package com.netswap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated userspace TUN lifecycle scaffold.
 *
 * It installs only a single host route for the reserved NetSwap test peer
 * (10.255.255.2/32). It does NOT install a default route, forward packets,
 * connect to a gateway, or provide internet access.
 */
class NetSwapUserspaceVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.netswap.app.action.START_USERSPACE_VPN"
        const val ACTION_STOP = "com.netswap.app.action.STOP_USERSPACE_VPN"
        const val ACTION_STATE = "com.netswap.app.action.USERSPACE_VPN_STATE"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_PACKETS_READ = "packets_read"

        private const val CHANNEL_ID = "netswap_userspace_vpn"
        private const val NOTIFICATION_ID = 7302
        private const val TEST_INTERFACE_ADDRESS = "10.255.255.1"
        private const val TEST_PEER_ADDRESS = "10.255.255.2"
    }

    private var tun: ParcelFileDescriptor? = null
    private var packetDevice: AndroidTunPacketDevice? = null
    private var packetPump: TunPacketPump? = null
    private val packetsRead = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopUserspaceVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startUserspaceVpn()
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startUserspaceVpn() {
        if (packetPump != null) return
        if (VpnService.prepare(this) != null) {
            publishState(false)
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            val established = Builder()
                .setSession("NetSwap isolated packet test")
                .setMtu(1280)
                .addAddress(TEST_INTERFACE_ADDRESS, 32)
                .addRoute(TEST_PEER_ADDRESS, 32)
                .establish() ?: error("Android could not establish the test TUN interface")

            tun = established
            val device = AndroidTunPacketDevice.from(established)
            packetDevice = device
            val pump = TunPacketPump(
                device = device,
                onPacket = {
                    // Deliberately fail closed: packet forwarding is not connected yet.
                    packetsRead.incrementAndGet()
                },
                onFailure = {
                    publishState(false)
                    stopSelf()
                },
                onWorkerExit = {
                    publishState(false)
                    stopSelf()
                }
            )
            packetPump = pump
            pump.start()
            publishState(true)
        } catch (_: Exception) {
            stopUserspaceVpn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopUserspaceVpn() {
        packetPump?.close()
        packetPump = null
        packetDevice = null
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        publishState(false)
    }

    private fun publishState(active: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_PACKETS_READ, packetsRead.get())
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetSwap test VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, NetSwapUserspaceVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NetSwap test VPN")
            .setContentText("Isolated packet interface active — no internet forwarding")
            .setContentIntent(openApp)
            .addAction(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Notification.Action.Builder(null, "Stop", stopIntent).build()
                else @Suppress("DEPRECATION") Notification.Action(0, "Stop", stopIntent)
            )
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopUserspaceVpn()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopUserspaceVpn()
        super.onDestroy()
    }
}
