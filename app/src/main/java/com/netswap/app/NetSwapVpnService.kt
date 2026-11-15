package com.netswap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

/**
 * NetSwap VPN foundation.
 *
 * This first safe prototype creates only a private test route (10.77.0.1/32).
 * It deliberately does NOT install a default route or claim to proxy internet.
 * Peer transport, packet forwarding, NAT and quota enforcement are later stages.
 */
class NetSwapVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.netswap.app.action.START_VPN"
        const val ACTION_STOP = "com.netswap.app.action.STOP_VPN"
        private const val CHANNEL_ID = "netswap_vpn_status"
        private const val NOTIFICATION_ID = 7301
    }

    private var vpnInterface: ParcelFileDescriptor? = null

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
        if (vpnInterface == null) {
            try {
                // A single test host route avoids capturing or interrupting normal internet.
                vpnInterface = Builder()
                    .setSession("NetSwap test tunnel")
                    .setMtu(1400)
                    .addAddress("10.77.0.2", 32)
                    .addRoute("10.77.0.1", 32)
                    .establish()
            } catch (_: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun stopTunnel() {
        try { vpnInterface?.close() } catch (_: Exception) { }
        vpnInterface = null
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("NetSwap test tunnel")
                .setContentText("Local VPN route test only — internet proxy is not active")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("NetSwap test tunnel")
                .setContentText("Local VPN route test only — internet proxy is not active")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    override fun onRevoke() {
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
