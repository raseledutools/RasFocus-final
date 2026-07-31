package com.rasel.RasFocus.selfcontrol.study_tools

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings Tile — appears in notification shade (action centre) alongside
 * WiFi / Data / Bluetooth toggles.
 *
 * When tapped, opens QuickReminderActivity as a dialog popup so the user can
 * set a 5 / 10 / 15 / 30 / 60 min or custom reminder within 24 hours.
 *
 * Register in AndroidManifest under:
 *   <service android:name=".selfcontrol.study_tools.QuickReminderTileService"
 *            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
 *            android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.service.quicksettings.action.QS_TILE"/>
 *       </intent-filter>
 *       <meta-data android:name="android.service.quicksettings.ACTIVE_TILE"
 *                  android:value="false" />
 *   </service>
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickReminderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Quick Reminder"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = "Tap to set"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Collapse QS panel and open the quick reminder dialog
        val intent = Intent(applicationContext, QuickReminderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    applicationContext, 9999, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
