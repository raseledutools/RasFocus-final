package com.rasel.RasFocus.selfcontrol.study_tools

import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * Quick Settings Tile — notification shade এর action centre এ একটা "Grayscale" বাটন যোগ করে।
 * Tap করলে পুরো ফোন Black & White (grayscale/monochrome) mode এ চলে যাবে।
 * আবার tap করলে colour ফিরে আসবে।
 *
 * কিভাবে কাজ করে:
 *  Android এর built-in daltonizer (colour correction) API ব্যবহার করে।
 *  Settings.Secure.accessibility_display_daltonizer = 0  → grayscale/monochromacy
 *  Settings.Secure.accessibility_display_daltonizer_enabled = 1/0  → on/off
 *
 * ⚠️  WRITE_SECURE_SETTINGS permission দরকার (system-level)।
 *     একবার ADB দিয়ে grant করতে হবে, পরে আর লাগবে না:
 *
 *     adb shell pm grant com.rasel.RasFocus android.permission.WRITE_SECURE_SETTINGS
 *
 * Manifest এ register করতে হবে:
 *   <service android:name=".selfcontrol.study_tools.GrayscaleTileService"
 *            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
 *            android:exported="true"
 *            android:icon="@drawable/ic_tile_grayscale"
 *            android:label="Grayscale">
 *       <intent-filter>
 *           <action android:name="android.service.quicksettings.action.QS_TILE" />
 *       </intent-filter>
 *   </service>
 */
@RequiresApi(Build.VERSION_CODES.N)
class GrayscaleTileService : TileService() {

    // DALTONIZER_GRAYSCALE = 0 (Android internal constant for monochromacy/grayscale)
    companion object {
        private const val DALTONIZER_SETTING = "accessibility_display_daltonizer"
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        private const val GRAYSCALE_MODE = 0
    }

    /** বর্তমানে grayscale চালু আছে কিনা পড়ে আনে। */
    private val isGrayscaleActive: Boolean
        get() {
            val enabled = Settings.Secure.getInt(
                contentResolver, DALTONIZER_ENABLED, 0
            ) == 1
            val mode = Settings.Secure.getInt(
                contentResolver, DALTONIZER_SETTING, -1
            )
            return enabled && mode == GRAYSCALE_MODE
        }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val turnOn = !isGrayscaleActive
        try {
            if (turnOn) {
                // প্রথমে mode set করো, তারপর enable করো
                Settings.Secure.putInt(contentResolver, DALTONIZER_SETTING, GRAYSCALE_MODE)
                Settings.Secure.putInt(contentResolver, DALTONIZER_ENABLED, 1)
            } else {
                Settings.Secure.putInt(contentResolver, DALTONIZER_ENABLED, 0)
            }
            refreshTile()
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS এখনো grant হয়নি
            showPermissionGuide()
        }
    }

    /** Tile এর state, label, subtitle আপডেট করে। */
    private fun refreshTile() {
        qsTile?.apply {
            val on = isGrayscaleActive
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Grayscale"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (on) "চালু" else "বন্ধ"
            }
            updateTile()
        }
    }

    /**
     * WRITE_SECURE_SETTINGS permission না থাকলে user কে guide করে।
     * Toast দেখায় + ADB command দিয়ে কোথায় grant করতে হবে বলে দেয়।
     */
    private fun showPermissionGuide() {
        Toast.makeText(
            applicationContext,
            "একবার ADB দিয়ে permission দিন:\n" +
                    "adb shell pm grant com.rasel.RasFocus android.permission.WRITE_SECURE_SETTINGS",
            Toast.LENGTH_LONG
        ).show()

        // Optionally developer options খুলে দেওয়া যায়, কিন্তু ADB guide-ই সেরা
        refreshTile()
    }
}
