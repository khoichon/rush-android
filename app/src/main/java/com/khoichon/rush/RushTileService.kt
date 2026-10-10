package com.khoichon.rush

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** Quick Settings tile: tap to call Rush out right now (starts RUSH first if needed). */
class RushTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (RushService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (!Settings.canDrawOverlays(this)) {
            openApp()
            return
        }
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RushService::class.java).setAction(RushService.ACTION_SPAWN)
            )
        } catch (e: Exception) {
            openApp() // the system wouldn't let a tile start the service: fall back to the app
        }
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
