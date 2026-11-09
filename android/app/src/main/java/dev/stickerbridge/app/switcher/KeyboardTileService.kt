package dev.stickerbridge.app.switcher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.stickerbridge.app.R

/**
 * Quick Settings tile "StickerBridge": opens the system keyboard picker through
 * [KeyboardPickerActivity] (the picker needs a focused app window).
 *
 * The tile is a plain action tile, so it is always shown as INACTIVE; on One UI an
 * inactive tile is drawn gray. That is expected and does not mean it is disabled.
 */
class KeyboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(R.string.tile_subtitle)
        tile.contentDescription = getString(R.string.shortcut_switch_long)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        SwitchLog.d(this, "tile click (api=${Build.VERSION.SDK_INT}, locked=$isLocked)")
        if (isLocked) {
            // Ask to unlock first; the picker is useless on the lock screen.
            unlockAndRun { openPicker() }
        } else {
            openPicker()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openPicker() {
        val intent = KeyboardPickerActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: startActivityAndCollapse(Intent) is not allowed; use an immutable PendingIntent.
            val pending = PendingIntent.getActivity(
                this, REQUEST_PICKER, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            SwitchLog.d(this, "tile startActivityAndCollapse(PendingIntent)")
            startActivityAndCollapse(pending)
        } else {
            SwitchLog.d(this, "tile startActivityAndCollapse(Intent)")
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val REQUEST_PICKER = 1
    }
}
