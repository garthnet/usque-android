package com.abobo.usquevpn

import android.content.Intent
import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import usqueandroid.PacketFlow
import usqueandroid.Usqueandroid
import usqueandroid.VpnStateCallback
import java.io.FileOutputStream

class UsqueVpnService : VpnService() {

    companion object {
        private const val TAG = "UsqueVpnService"
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

        /** Restart VPN to apply new settings */
        fun restart(context: Context) {
            Log.i(TAG, "Restarting VPN to apply new settings...")
            stop()
            // Wait a bit then restart
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val intent = Intent(context, UsqueVpnService::class.java)
                context.startService(intent)
            }, 500)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
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
                    Log.i(TAG, "IPv6 configured: $vpnIpv6")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add IPv6: ${e.message}")
                }
            }

            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("1.0.0.1")
            builder.addDnsServer("2606:4700:4700::1111")
            builder.addDnsServer("2606:4700:4700::1001")

            if (mode == MODE_PER_APP) {
                // Per-app mode: only selected apps go through VPN
                // IMPORTANT: use ONLY addAllowedApplication, do NOT mix with addDisallowedApplication
                val allowedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
                Log.i(TAG, "Per-app mode: ${allowedApps.size} apps selected")

                for (appPackage in allowedApps) {
                    try {
                        builder.addAllowedApplication(appPackage)
                        Log.d(TAG, "  Allowed: $appPackage")
                    } catch (e: Exception) {
                        Log.w(TAG, "  Failed to allow $appPackage: ${e.message}")
                    }
                }
                // Self is NOT in allowlist → self traffic bypasses VPN → no loop
            } else {
                // Global mode: all traffic through VPN, exclude self to prevent loop
                Log.i(TAG, "Global mode: all traffic through VPN")
                builder.addDisallowedApplication(packageName)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return START_NOT_STICKY
            }

            val fd = vpnInterface!!.fd
            outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

            Log.i(TAG, "VPN interface established with fd=$fd, mode=$mode")

            isRunning = true

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
                vpnInterface?.close()
                stopSelf()
                return START_NOT_STICKY
            }

            Log.i(TAG, "VPN started! Mode: $mode, Allowed apps: ${if (mode == MODE_PER_APP) (prefs.getStringSet(KEY_ALLOWED_APPS, emptySet())?.size ?: 0) else "all"}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VPN interface", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        if (!isRunning) return
        isRunning = false

        try { Usqueandroid.stopTunnel() } catch (e: Exception) { Log.e(TAG, "Error stopping tunnel", e) }
        try { outputStream?.close() } catch (e: Exception) { Log.e(TAG, "Error closing stream", e) }
        outputStream = null
        try { vpnInterface?.close() } catch (e: Exception) { Log.e(TAG, "Error closing VPN", e) }
        vpnInterface = null

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
