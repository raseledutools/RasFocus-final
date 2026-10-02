package com.rasel.RasFocus.selfcontrol.study_tools

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Quick Settings Tile — notification shade এর action centre এ একটা "Grayscale" বাটন।
 *
 * চালু করলে:
 *  ✅ পুরো ফোন Black & White (grayscale) হয়
 *  ✅ সব Animation বন্ধ হয় (Window / Transition / Animator scale = 0)
 *  ✅ App cache মুছে যায় (internal cache dir + WebView cache)
 *  ✅ Phone superfest mode!
 *
 * বন্ধ করলে:
 *  ✅ Color ফিরে আসে
 *  ✅ Animation স্বাভাবিক হয় (scale = 1)
 *
 * ⚠️  একবার ADB দিয়ে grant করতে হবে:
 *     adb shell pm grant com.rasel.RasFocus android.permission.WRITE_SECURE_SETTINGS
 */
@RequiresApi(Build.VERSION_CODES.N)
class GrayscaleTileService : TileService() {

    companion object {
        private const val DALTONIZER_SETTING = "accessibility_display_daltonizer"
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        private const val GRAYSCALE_MODE = 0

        // Animation scale settings
        private const val WINDOW_ANIMATION_SCALE     = "window_animation_scale"
        private const val TRANSITION_ANIMATION_SCALE = "transition_animation_scale"
        private const val ANIMATOR_DURATION_SCALE    = "animator_duration_scale"
    }

    private val isGrayscaleActive: Boolean
        get() {
            val enabled = Settings.Secure.getInt(contentResolver, DALTONIZER_ENABLED, 0) == 1
            val mode    = Settings.Secure.getInt(contentResolver, DALTONIZER_SETTING, -1)
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
                enableGrayscale()
                disableAnimations()
                clearAllCache()
            } else {
                disableGrayscale()
                enableAnimations()
            }
            refreshTile()
        } catch (e: SecurityException) {
            showPermissionGuide()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Grayscale on / off
    // ─────────────────────────────────────────────────────────────

    private fun enableGrayscale() {
        Settings.Secure.putInt(contentResolver, DALTONIZER_SETTING, GRAYSCALE_MODE)
        Settings.Secure.putInt(contentResolver, DALTONIZER_ENABLED, 1)
    }

    private fun disableGrayscale() {
        Settings.Secure.putInt(contentResolver, DALTONIZER_ENABLED, 0)
    }

    // ─────────────────────────────────────────────────────────────
    // Animation control — needs WRITE_SECURE_SETTINGS
    // ─────────────────────────────────────────────────────────────

    private fun disableAnimations() {
        Settings.Global.putFloat(contentResolver, WINDOW_ANIMATION_SCALE,     0f)
        Settings.Global.putFloat(contentResolver, TRANSITION_ANIMATION_SCALE, 0f)
        Settings.Global.putFloat(contentResolver, ANIMATOR_DURATION_SCALE,    0f)
    }

    private fun enableAnimations() {
        Settings.Global.putFloat(contentResolver, WINDOW_ANIMATION_SCALE,     1f)
        Settings.Global.putFloat(contentResolver, TRANSITION_ANIMATION_SCALE, 1f)
        Settings.Global.putFloat(contentResolver, ANIMATOR_DURATION_SCALE,    1f)
    }

    // ─────────────────────────────────────────────────────────────
    // Cache clear — app cache + WebView cache + temp files
    // ─────────────────────────────────────────────────────────────

    @SuppressLint("WorldReadableFiles")
    private fun clearAllCache() {
        try {
            // 1) App internal cache
            deleteRecursive(cacheDir)

            // 2) External cache (if present)
            externalCacheDir?.let { deleteRecursive(it) }

            // 3) WebView cache folder
            val webViewCache = File(cacheDir.parent, "app_webview/Default/Cache")
            if (webViewCache.exists()) deleteRecursive(webViewCache)

            // 4) Temp / code_cache
            val codeCache = File(cacheDir.parent, "code_cache")
            if (codeCache.exists()) deleteRecursive(codeCache)

            // 5) databases cache (query cache etc.)
            // Only touch recognisable temp DB files, don't delete real databases
            val dbDir = File(cacheDir.parent, "databases")
            dbDir.listFiles()?.filter {
                it.name.endsWith(".tmp") || it.name.endsWith("-journal") || it.name.endsWith("-wal")
            }?.forEach { it.delete() }

        } catch (_: Exception) {
            // cache clear silent fail — no crash
        }
    }

    private fun deleteRecursive(fileOrDir: File) {
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { deleteRecursive(it) }
        }
        fileOrDir.delete()
    }

    // ─────────────────────────────────────────────────────────────
    // Tile UI refresh
    // ─────────────────────────────────────────────────────────────

    private fun refreshTile() {
        qsTile?.apply {
            val on  = isGrayscaleActive
            state   = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label   = "Grayscale"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (on) "Fast Mode ON" else "বন্ধ"
            }
            updateTile()
        }
    }

    private fun showPermissionGuide() {
        Toast.makeText(
            applicationContext,
            "একবার ADB দিয়ে permission দিন:\n" +
                "adb shell pm grant com.rasel.RasFocus android.permission.WRITE_SECURE_SETTINGS",
            Toast.LENGTH_LONG
        ).show()
        refreshTile()
    }
}
