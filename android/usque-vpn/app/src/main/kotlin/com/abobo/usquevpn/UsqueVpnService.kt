package com.abobo.usquevpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import usqueandroid.PacketFlow
import usqueandroid.Usqueandroid
import usqueandroid.VpnStateCallback
import java.io.FileOutputStream

class UsqueVpnService : VpnService() {

    companion object {
        private const val TAG = "UsqueVpnService"
        private const val CHANNEL_ID = "usque_vpn_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.abobo.usquevpn.DISCONNECT"
        const val PREFS_NAME = "UsqueVpnPrefs"
        const val KEY_PROXY_MODE = "proxy_mode"
        const val KEY_ALLOWED_APPS = "allowed_apps"
        const val MODE_GLOBAL = "global"
        const val MODE_PER_APP = "per_app"

        var isRunning = false
            private set

        private var instance: UsqueVpnService? = null

        fun stop() {
            Log.i(TAG, "Static stop() called")
            instance?.disconnect()
        }

        fun restart(context: Context) {
            Log.i(TAG, "Restarting VPN...")
            stop()
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(context, UsqueVpnService::class.java)
                context.startService(intent)
            }, 500)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastTrafficTime: Long = 0

    private val speedUpdater = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateSpeedNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "Received disconnect intent")
            disconnect()
            return START_NOT_STICKY
        }

        Log.i(TAG, "VPN Service starting...")

        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return START_STICKY
        }

        val configPath = "${filesDir.absolutePath}/config.json"

        if (!Usqueandroid.isRegistered(configPath)) {
            Log.i(TAG, "Not registered, registering now...")
            val error = Usqueandroid.register(configPath, android.os.Build.MODEL)
            if (error.isNotEmpty()) {
                Log.e(TAG, "Registration failed: $error")
                stopSelf()
                return START_NOT_STICKY
            }
            Log.i(TAG, "Registration successful")
        }

        val vpnIpv4 = Usqueandroid.getAssignedIPv4(configPath)
        val vpnIpv6 = Usqueandroid.getAssignedIPv6(configPath)

        Log.i(TAG, "Assigned IPs: v4=$vpnIpv4, v6=$vpnIpv6")

        if (vpnIpv4.isEmpty()) {
            Log.e(TAG, "No IPv4 address assigned")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_PROXY_MODE, MODE_GLOBAL) ?: MODE_GLOBAL

            val builder = Builder()
                .setSession("Usque WARP VPN")
                .setMtu(1280)

            builder.addAddress(vpnIpv4, 32)
            builder.addRoute("0.0.0.0", 0)

            if (vpnIpv6.isNotEmpty()) {
                try {
                    builder.addAddress(vpnIpv6, 128)
                    builder.addRoute("::", 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add IPv6: ${e.message}")
                }
            }

            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("1.0.0.1")
            builder.addDnsServer("2606:4700:4700::1111")
            builder.addDnsServer("2606:4700:4700::1001")

            if (mode == MODE_PER_APP) {
                val allowedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
                Log.i(TAG, "Per-app mode: ${allowedApps.size} apps")
                for (appPackage in allowedApps) {
                    try {
                        builder.addAllowedApplication(appPackage)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to allow $appPackage: ${e.message}")
                    }
                }
            } else {
                Log.i(TAG, "Global mode")
                builder.addDisallowedApplication(packageName)
            }

            // Show foreground notification BEFORE establish
            startForeground(NOTIFICATION_ID, buildNotification("0 B/s ↓", "0 B/s ↑", "连接中..."))

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            val fd = vpnInterface!!.fd
            outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

            Log.i(TAG, "VPN interface established with fd=$fd, mode=$mode")

            isRunning = true

            // Start speed monitoring
            lastRxBytes = TrafficStats.getTotalRxBytes()
            lastTxBytes = TrafficStats.getTotalTxBytes()
            lastTrafficTime = System.currentTimeMillis()
            handler.post(speedUpdater)

            val packetFlow = object : PacketFlow {
                override fun writePacket(data: ByteArray?) {
                    if (data != null && data.isNotEmpty()) {
                        try {
                            outputStream?.write(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write packet to TUN", e)
                        }
                    }
                }
            }

            val callback = object : VpnStateCallback {
                override fun onConnected() {
                    Log.i(TAG, "MASQUE tunnel connected!")
                    updateSpeedNotification()
                }
                override fun onDisconnected(reason: String?) {
                    Log.w(TAG, "MASQUE tunnel disconnected: $reason")
                    disconnect()
                }
                override fun onError(message: String?) {
                    Log.e(TAG, "MASQUE tunnel error: $message")
                }
            }

            val tunnelError = Usqueandroid.startTunnel(configPath, fd.toLong(), 1280, packetFlow, callback)
            if (tunnelError.isNotEmpty()) {
                Log.e(TAG, "Failed to start tunnel: $tunnelError")
                isRunning = false
                handler.removeCallbacks(speedUpdater)
                vpnInterface?.close()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            Log.i(TAG, "VPN started! Mode: $mode")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VPN interface", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Usque VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN status and speed"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(downloadSpeed: String, uploadSpeed: String, status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, UsqueVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("↓ $downloadSpeed  ↑ $uploadSpeed")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(Notification.Action.Builder(
                null, "断开", disconnectIntent
            ).build())
            .setOngoing(true)
            .build()
    }

    private fun updateSpeedNotification() {
        val now = System.currentTimeMillis()
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val elapsed = now - lastTrafficTime

        if (elapsed > 0) {
            val rxSpeed = (currentRx - lastRxBytes) * 1000 / elapsed
            val txSpeed = (currentTx - lastTxBytes) * 1000 / elapsed

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_PROXY_MODE, MODE_GLOBAL) ?: MODE_GLOBAL
            val modeStr = if (mode == MODE_GLOBAL) "全局代理" else "分应用代理"

            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(
                formatSpeed(rxSpeed),
                formatSpeed(txSpeed),
                "已连接 · $modeStr"
            ))

            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastTrafficTime = now
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format("%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        if (!isRunning) return
        isRunning = false

        handler.removeCallbacks(speedUpdater)

        try { Usqueandroid.stopTunnel() } catch (e: Exception) { Log.e(TAG, "Error stopping tunnel", e) }
        try { outputStream?.close() } catch (e: Exception) { Log.e(TAG, "Error closing stream", e) }
        outputStream = null
        try { vpnInterface?.close() } catch (e: Exception) { Log.e(TAG, "Error closing VPN", e) }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy()")
        if (isRunning) disconnect()
        instance = null
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke()")
        disconnect()
    }
}
