package com.rasel.RasFocus.selfcontrol.study_tools

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.io.File
import java.net.InetAddress

/**
 * Quick Settings Tile — Grayscale Fast Mode
 *
 * চালু করলে:
 *  ✅ Grayscale (Black & White)
 *  ✅ সব Animation বন্ধ (Window / Transition / Animator scale = 0)
 *  ✅ App cache + WebView cache মুছে যায়
 *  ✅ Force GPU Rendering চালু
 *  ✅ Background Process Limit = 2
 *  ✅ Background apps kill
 *  ✅ Fast DNS (1.1.1.1 / 8.8.8.8) — private DNS mode
 *
 * বন্ধ করলে:
 *  ✅ Color ফিরে আসে
 *  ✅ Animation স্বাভাবিক (1x)
 *  ✅ GPU rendering default
 *  ✅ Background process limit উঠে যায়
 *  ✅ DNS default এ ফিরে আসে
 *
 * ⚠️  একবার ADB grant করতে হবে:
 *     adb shell pm grant com.rasel.RasFocus android.permission.WRITE_SECURE_SETTINGS
 */
@RequiresApi(Build.VERSION_CODES.N)
class GrayscaleTileService : TileService() {

    companion object {
        // Grayscale
        private const val DALTONIZER_SETTING = "accessibility_display_daltonizer"
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        private const val GRAYSCALE_MODE     = 0

        // Animation
        private const val WINDOW_ANIMATION_SCALE     = "window_animation_scale"
        private const val TRANSITION_ANIMATION_SCALE = "transition_animation_scale"
        private const val ANIMATOR_DURATION_SCALE    = "animator_duration_scale"

        // GPU Rendering
        private const val HARDWARE_RENDERING = "hardware_rendering_enabled"

        // Background process limit
        // 0 = standard, 1 = no bg, 2 = at most 2, 3 = at most 3, 4 = at most 4
        private const val BG_PROCESS_LIMIT = "background_app_limit"

        // DNS (Private DNS)
        private const val PRIVATE_DNS_MODE      = "private_dns_mode"         // "off" | "opportunistic" | "hostname"
        private const val PRIVATE_DNS_SPECIFIER = "private_dns_specifier"    // hostname when mode=hostname

        // Fast DNS hostnames (DoT — DNS-over-TLS)
        private const val FAST_DNS_PRIMARY   = "one.one.one.one"   // Cloudflare 1.1.1.1
        private const val FAST_DNS_SECONDARY = "dns.google"        // Google 8.8.8.8
    }

    // ─────────────────────────────────────────────────────────────
    // State check
    // ─────────────────────────────────────────────────────────────

    private val isGrayscaleActive: Boolean
        get() {
            val enabled = Settings.Secure.getInt(contentResolver, DALTONIZER_ENABLED, 0) == 1
            val mode    = Settings.Secure.getInt(contentResolver, DALTONIZER_SETTING, -1)
            return enabled && mode == GRAYSCALE_MODE
        }

    // ─────────────────────────────────────────────────────────────
    // Tile lifecycle
    // ─────────────────────────────────────────────────────────────

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
                enableGpuRendering()
                limitBackgroundProcesses()
                setFastDns()
                clearAllCache()
                killBackgroundApps()
            } else {
                disableGrayscale()
                enableAnimations()
                resetGpuRendering()
                resetBackgroundProcessLimit()
                resetDns()
            }
            refreshTile()
        } catch (e: SecurityException) {
            showPermissionGuide()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Grayscale
    // ─────────────────────────────────────────────────────────────

    private fun enableGrayscale() {
        Settings.Secure.putInt(contentResolver, DALTONIZER_SETTING, GRAYSCALE_MODE)
        Settings.Secure.putInt(contentResolver, DALTONIZER_ENABLED, 1)
    }

    private fun disableGrayscale() {
        Settings.Secure.putInt(contentResolver, DALTONIZER_ENABLED, 0)
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Animation
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
    // 3. GPU Rendering
    // ─────────────────────────────────────────────────────────────

    private fun enableGpuRendering() {
        // Force hardware-accelerated rendering for all apps
        Settings.Global.putInt(contentResolver, HARDWARE_RENDERING, 1)

        // Disable GPU debug layers overhead (পুরো rendering stack lightweight হয়)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Global.putInt(contentResolver, "enable_gpu_debug_layers", 0)
        }

        // Disable show GPU overdraw debug (শুধু debugging-এর জন্য — production-এ off রাখা ভালো)
        Settings.Global.putInt(contentResolver, "show_gpu_overdraw", 0)
    }

    private fun resetGpuRendering() {
        Settings.Global.putInt(contentResolver, HARDWARE_RENDERING, 1) // always keep on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Global.putInt(contentResolver, "enable_gpu_debug_layers", 0)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Background Process Limit
    // ─────────────────────────────────────────────────────────────

    private fun limitBackgroundProcesses() {
        // 2 = সর্বোচ্চ ২টি background app চলবে → RAM free থাকবে
        Settings.Global.putInt(contentResolver, BG_PROCESS_LIMIT, 2)
    }

    private fun resetBackgroundProcessLimit() {
        // 0 = standard (OS decides)
        Settings.Global.putInt(contentResolver, BG_PROCESS_LIMIT, 0)
    }

    // ─────────────────────────────────────────────────────────────
    // 4b. Kill background apps (KILL_BACKGROUND_PROCESSES — normal permission)
    // ─────────────────────────────────────────────────────────────

    private fun killBackgroundApps() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val ownPackage = packageName

            // Running app packages পাই
            val runningApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.runningAppProcesses
                    ?.filter { it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND }
                    ?.flatMap { it.pkgList.toList() }
                    ?: emptyList()
            } else emptyList()

            runningApps.forEach { pkg ->
                if (pkg != ownPackage) {
                    try {
                        am.killBackgroundProcesses(pkg)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // 5. Fast DNS (Private DNS — DNS-over-TLS)
    // ─────────────────────────────────────────────────────────────

    @SuppressLint("WifiManagerPotentialLeak")
    private fun setFastDns() {
        try {
            // Android 9+ Private DNS (DNS-over-TLS) — সবচেয়ে effective
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Settings.Global.putString(contentResolver, PRIVATE_DNS_MODE, "hostname")
                Settings.Global.putString(contentResolver, PRIVATE_DNS_SPECIFIER, FAST_DNS_PRIMARY)
            }
        } catch (_: Exception) {}
    }

    private fun resetDns() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // "opportunistic" = automatic DNS-over-TLS if available
                Settings.Global.putString(contentResolver, PRIVATE_DNS_MODE, "opportunistic")
                Settings.Global.putString(contentResolver, PRIVATE_DNS_SPECIFIER, "")
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // Cache clear
    // ─────────────────────────────────────────────────────────────

    @SuppressLint("WorldReadableFiles")
    private fun clearAllCache() {
        try {
            deleteRecursive(cacheDir)
            externalCacheDir?.let { deleteRecursive(it) }

            val webViewCache = File(cacheDir.parent, "app_webview/Default/Cache")
            if (webViewCache.exists()) deleteRecursive(webViewCache)

            val codeCache = File(cacheDir.parent, "code_cache")
            if (codeCache.exists()) deleteRecursive(codeCache)

            val dbDir = File(cacheDir.parent, "databases")
            dbDir.listFiles()
                ?.filter { it.name.endsWith(".tmp") || it.name.endsWith("-journal") || it.name.endsWith("-wal") }
                ?.forEach { it.delete() }

        } catch (_: Exception) {}
    }

    private fun deleteRecursive(fileOrDir: File) {
        if (fileOrDir.isDirectory) fileOrDir.listFiles()?.forEach { deleteRecursive(it) }
        fileOrDir.delete()
    }

    // ─────────────────────────────────────────────────────────────
    // Tile UI
    // ─────────────────────────────────────────────────────────────

    private fun refreshTile() {
        qsTile?.apply {
            val on  = isGrayscaleActive
            state   = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label   = "Fast Mode"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (on) "⚡ চালু" else "বন্ধ"
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
