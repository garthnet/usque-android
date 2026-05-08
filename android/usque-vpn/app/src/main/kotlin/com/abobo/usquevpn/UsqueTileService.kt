package com.abobo.usquevpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class UsqueTileService : TileService() {

    companion object {
        private const val TAG = "UsqueTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening")
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "onClick — isRunning=${UsqueVpnService.isRunning}")

        if (UsqueVpnService.isRunning) {
            // 断开 VPN
            UsqueVpnService.stop()
            val intent = Intent(this, UsqueVpnService::class.java)
            intent.action = UsqueVpnService.ACTION_DISCONNECT
            startService(intent)
            // 延迟更新磁贴状态
            qsTile?.let { tile ->
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Usque"
                tile.subtitle = "已断开"
                tile.updateTile()
            }
        } else {
            // 启动 VPN — 需要先检查权限
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                // 需要用户授权，打开主界面
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(launchIntent)
            } else {
                // 已有权限，直接启动
                val intent = Intent(this, UsqueVpnService::class.java)
                startService(intent)
                qsTile?.let { tile ->
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Usque"
                    tile.subtitle = "连接中..."
                    tile.updateTile()
                }
            }
        }

        // 延迟更新最终状态
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            updateTile()
        }, 2000)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        if (UsqueVpnService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Usque"
            tile.subtitle = "已连接"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Usque"
            tile.subtitle = "已断开"
        }
        tile.updateTile()
    }
}
