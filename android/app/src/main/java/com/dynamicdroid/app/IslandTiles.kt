package com.dynamicdroid.app

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings: pause the island for 15 minutes (for a bank or game app that dislikes
 * overlays), or tap again to bring it straight back.
 */
class IslandPauseTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        val pausing = !IslandFeatures.paused(this)
        IslandFeatures.setPausedUntil(this, if (pausing) System.currentTimeMillis() + IslandOverlayService.PAUSE_MS else 0L)
        // Let the running island hide (or come back) now.
        runCatching {
            startService(Intent(this, IslandOverlayService::class.java)
                .setAction(IslandOverlayService.ACTION_QUICK).putExtra(IslandOverlayService.EXTRA_WHAT, "pause"))
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val paused = IslandFeatures.paused(this)
        tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (paused) R.string.tile_paused else R.string.tile_pause)
        tile.updateTile()
    }
}

/** Quick Settings: a screenshot with the island hidden. */
class IslandShotTile : TileService() {
    override fun onClick() {
        startService(Intent(this, IslandOverlayService::class.java)
            .setAction(IslandOverlayService.ACTION_QUICK).putExtra(IslandOverlayService.EXTRA_WHAT, "shot-tile"))
    }
}
