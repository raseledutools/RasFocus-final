package com.rasel.RasFocus.selfcontrol.ultrasave

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Ultra Save Mode — Quick Settings Tile।
 *
 * Mode OFF → Tap → UltraSaveModeActivity (3 option: Normal / Self Control / Parents)
 * Mode ON  → Tap →
 *   Normal lock: সরাসরি বন্ধ হয়
 *   Self Control: সময় বাকি থাকলে UltraSaveModeActivity (exit mode) → timer দেখায়
 *   Parents: UltraSaveModeActivity (exit mode) → password চায়
 *
 * Manifest এ register করতে হবে:
 *   <service android:name=".selfcontrol.ultrasave.UltraSaveTileService"
 *            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
 *            android:exported="true"
 *            android:icon="@drawable/ic_tile_ultrasave"
 *            android:label="Ultra Save">
 *       <intent-filter>
 *           <action android:name="android.service.quicksettings.action.QS_TILE" />
 *       </intent-filter>
 *   </service>
 */
@RequiresApi(Build.VERSION_CODES.N)
class UltraSaveTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val active = UltraSaveManager.isActive(applicationContext)

        if (!active) {
            // Mode OFF → activate dialog দেখাও
            openActivity("activate")
            return
        }

        // Mode ON → lock check
        when {
            UltraSaveManager.canExitFreely(applicationContext) -> {
                // Normal বা timer expired → সরাসরি বন্ধ করো
                UltraSaveManager.deactivate(applicationContext)
                refreshTile()
            }
            else -> {
                // Self Control (timer চলছে) বা Parents → exit dialog
                openActivity("exit")
            }
        }
    }

    private fun refreshTile() {
        qsTile?.apply {
            val on = UltraSaveManager.isActive(applicationContext)
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Ultra Save"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (on) {
                    when (UltraSaveManager.getLockType(applicationContext)) {
                        UltraSaveManager.LockType.SELF_CONTROL -> {
                            val rem = UltraSaveManager.remainingMs(applicationContext)
                            if (rem > 0) UltraSaveManager.formatRemaining(rem) else "চালু"
                        }
                        UltraSaveManager.LockType.PARENTS -> "Parent Lock"
                        UltraSaveManager.LockType.NORMAL  -> "চালু"
                    }
                } else "বন্ধ"
            }
            updateTile()
        }
    }

    private fun openActivity(mode: String) {
        val intent = Intent(applicationContext, UltraSaveModeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("mode", mode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    applicationContext, 7001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
