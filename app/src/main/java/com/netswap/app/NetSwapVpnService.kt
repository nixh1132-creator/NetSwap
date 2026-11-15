package com.netswap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File
import java.io.FileInputStream

/**
 * Runs an imported WireGuard client profile through the official GoBackend.
 * The config stays in this app's private files directory and is never logged.
 */
class NetSwapVpnService : Service() {
    companion object {
        const val ACTION_START = "com.netswap.app.action.START_VPN"
        const val ACTION_STOP = "com.netswap.app.action.STOP_VPN"
        const val ACTION_STATE = "com.netswap.app.action.VPN_STATE"
        const val EXTRA_ACTIVE = "active"
        @Volatile var isTunnelActive: Boolean = false
            private set
        private const val CHANNEL_ID = "netswap_vpn_status"
        private const val NOTIFICATION_ID = 7301
    }

    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName(): String = "NetSwap"
        override fun onStateChange(newState: Tunnel.State) {
            publishState(newState == Tunnel.State.UP)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        Thread {
            try {
                val configFile = File(filesDir, "netswap-wg.conf")
                check(configFile.isFile && configFile.length() in 1..65536) {
                    "Import a valid WireGuard configuration first."
                }
                val config = FileInputStream(configFile).use { Config.parse(it) }
                val wg = backend ?: GoBackend(applicationContext).also { backend = it }
                wg.setState(tunnel, Tunnel.State.UP, config)
                publishState(true)
            } catch (e: Exception) {
                publishState(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun stopTunnel() {
        try { backend?.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) { }
        backend = null
        publishState(false)
    }

    private fun publishState(active: Boolean) {
        isTunnelActive = active
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_ACTIVE, active))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "NetSwap VPN status", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1, Intent(this, NetSwapVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("NetSwap WireGuard")
                .setContentText("Secure tunnel service is running")
                .setContentIntent(openApp)
                .addAction(Notification.Action.Builder(null, "Disconnect VPN", disconnectIntent).build())
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("NetSwap WireGuard")
                .setContentText("Secure tunnel service is running")
                .setContentIntent(openApp)
                .addAction(0, "Disconnect VPN", disconnectIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
