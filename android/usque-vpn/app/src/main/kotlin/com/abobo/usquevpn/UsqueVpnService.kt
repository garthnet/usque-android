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

/**
 * UsqueVpnService provides a system-level VPN using Cloudflare WARP/MASQUE protocol.
 *
 * Supports two modes:
 * - Global: All traffic goes through VPN
 * - Per-App: Only selected apps go through VPN
 */
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

        // Check registration
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

            // Always exclude self to prevent VPN loop
            builder.addDisallowedApplication(packageName)

            // Apply per-app or global mode
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_PROXY_MODE, MODE_GLOBAL) ?: MODE_GLOBAL

            if (mode == MODE_PER_APP) {
                val allowedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
                Log.i(TAG, "Per-app mode: ${allowedApps.size} apps selected")

                // When using allowlist, we need to add allowed apps
                // Note: addDisallowedApplication(self) is already called above
                // For per-app mode, we use addAllowedApplication for each selected app
                // But Android VPN Builder doesn't support mixing allow/disallow
                // So we need to restructure: remove the self-exclusion and use allowlist approach

                // Re-create builder without the self-exclusion
                val perAppBuilder = Builder()
                    .setSession("Usque WARP VPN")
                    .setMtu(1280)

                perAppBuilder.addAddress(vpnIpv4, 32)
                perAppBuilder.addRoute("0.0.0.0", 0)

                if (vpnIpv6.isNotEmpty()) {
                    try {
                        perAppBuilder.addAddress(vpnIpv6, 128)
                        perAppBuilder.addRoute("::", 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add IPv6: ${e.message}")
                    }
                }

                perAppBuilder.addDnsServer("1.1.1.1")
                perAppBuilder.addDnsServer("1.0.0.1")
                perAppBuilder.addDnsServer("2606:4700:4700::1111")
                perAppBuilder.addDnsServer("2606:4700:4700::1001")

                // Add each allowed app
                for (appPackage in allowedApps) {
                    try {
                        perAppBuilder.addAllowedApplication(appPackage)
                        Log.d(TAG, "Allowed app: $appPackage")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add allowed app $appPackage: ${e.message}")
                    }
                }

                // Exclude self
                perAppBuilder.addDisallowedApplication(packageName)

                vpnInterface = perAppBuilder.establish()
            } else {
                // Global mode: all traffic through VPN
                Log.i(TAG, "Global mode: all traffic through VPN")
                vpnInterface = builder.establish()
            }

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return START_NOT_STICKY
            }

            val fd = vpnInterface!!.fd
            outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

            Log.i(TAG, "VPN interface established with fd=$fd")

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
                    Log.i(TAG, "MASQUE tunnel connected to Cloudflare!")
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

            Log.i(TAG, "VPN Service started successfully! Mode: $mode")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VPN interface", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")

        if (!isRunning) {
            Log.w(TAG, "VPN not running, nothing to disconnect")
            return
        }

        isRunning = false

        try {
            Log.i(TAG, "Stopping Go tunnel...")
            Usqueandroid.stopTunnel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Go tunnel", e)
        }

        try {
            Log.i(TAG, "Closing output stream...")
            outputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing output stream", e)
        }
        outputStream = null

        try {
            Log.i(TAG, "Closing VPN interface...")
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        Log.i(TAG, "Stopping service...")
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() called")
        if (isRunning) { disconnect() }
        instance = null
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed")
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by user")
        disconnect()
    }
}
