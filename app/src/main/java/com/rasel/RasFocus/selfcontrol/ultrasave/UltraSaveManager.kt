package com.rasel.RasFocus.selfcontrol.ultrasave

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * UltraSaveManager — Ultra Save Mode এর সমস্ত state ও logic।
 *
 * Ultra Save Mode চালু হলে:
 *  ① পুরো স্ক্রিন grayscale (B&W) হয়
 *  ② Background apps kill হয় → ফোন super fast হয়
 *  ③ Phone + SMS ছাড়া বাকি সব app block হয় (UnifiedBlockerService এর মাধ্যমে)
 *
 * তিনটি lock type:
 *  NORMAL      — যেকোনো সময় বন্ধ করা যাবে
 *  SELF_CONTROL — নির্দিষ্ট সময়ের আগে বের হওয়া যাবে না
 *  PARENTS     — পাসওয়ার্ড ছাড়া বের হওয়া যাবে না
 */
object UltraSaveManager {

    enum class LockType { NORMAL, SELF_CONTROL, PARENTS }

    private const val PREFS         = "ultra_save_prefs"
    private const val KEY_ACTIVE    = "active"
    private const val KEY_LOCK      = "lock_type"
    private const val KEY_UNLOCK_AT = "unlock_at_ms"
    private const val KEY_PASS_HASH = "pass_hash"

    // ─── Read ────────────────────────────────────────────────────────────

    fun isActive(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)

    fun getLockType(ctx: Context): LockType =
        try {
            LockType.valueOf(
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LOCK, "NORMAL") ?: "NORMAL"
            )
        } catch (_: Exception) { LockType.NORMAL }

    fun getUnlockAtMs(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UNLOCK_AT, 0L)

    fun remainingMs(ctx: Context): Long =
        maxOf(0L, getUnlockAtMs(ctx) - System.currentTimeMillis())

    /**
     * true হলে tile tap করলেই mode বন্ধ হবে।
     * SELF_CONTROL: timer শেষ হলে; PARENTS: কখনো না (password লাগবে)
     */
    fun canExitFreely(ctx: Context): Boolean = when (getLockType(ctx)) {
        LockType.NORMAL       -> true
        LockType.SELF_CONTROL -> remainingMs(ctx) <= 0L
        LockType.PARENTS      -> false
    }

    fun checkPassword(ctx: Context, input: String): Boolean {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PASS_HASH, "") ?: ""
        return stored.isNotEmpty() && stored == sha256(input)
    }

    // ─── Write ───────────────────────────────────────────────────────────

    /**
     * Ultra Save Mode চালু করো।
     * @param lockType  কোন lock ব্যবহার হবে
     * @param durationMs SELF_CONTROL এর জন্য কতক্ষণ (ms)
     * @param password  PARENTS এর জন্য পাসওয়ার্ড (plain text — hashed করে store)
     */
    fun activate(
        ctx: Context,
        lockType: LockType,
        durationMs: Long = 0L,
        password: String = ""
    ) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_LOCK, lockType.name)
            if (lockType == LockType.SELF_CONTROL && durationMs > 0) {
                putLong(KEY_UNLOCK_AT, System.currentTimeMillis() + durationMs)
            }
            if (lockType == LockType.PARENTS && password.isNotEmpty()) {
                putString(KEY_PASS_HASH, sha256(password))
            }
            apply()
        }
        applyGrayscale(ctx, true)
        setAnimations(ctx, 0f)
        setBackgroundProcessLimit(ctx, 2)
        killBackgroundApps(ctx)
        UltraSaveBlockerService.start(ctx)
    }

    /** Ultra Save Mode বন্ধ করো — grayscale off, animation ও process limit restore হবে। */
    fun deactivate(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false)
            .apply()
        applyGrayscale(ctx, false)
        setAnimations(ctx, 1f)
        setBackgroundProcessLimit(ctx, -1) // -1 = standard limit (system default)
        UltraSaveBlockerService.stop(ctx)
    }

    // ─── System actions ──────────────────────────────────────────────────

    /** Settings.Secure daltonizer দিয়ে পুরো স্ক্রিন grayscale on/off। */
    fun applyGrayscale(ctx: Context, on: Boolean) {
        try {
            if (on) {
                Settings.Secure.putInt(ctx.contentResolver,
                    "accessibility_display_daltonizer", 0)          // 0 = monochromacy
                Settings.Secure.putInt(ctx.contentResolver,
                    "accessibility_display_daltonizer_enabled", 1)
            } else {
                Settings.Secure.putInt(ctx.contentResolver,
                    "accessibility_display_daltonizer_enabled", 0)
            }
        } catch (_: SecurityException) { /* ADB permission দরকার */ }
    }

    /**
     * সব animation scale set করো।
     * 0f = সব animation বন্ধ (ultra fast feel)
     * 1f = normal
     * WRITE_SECURE_SETTINGS permission দরকার (ADB দিয়ে grant করা থাকলেই হবে)
     */
    fun setAnimations(ctx: Context, scale: Float) {
        try {
            Settings.Global.putFloat(ctx.contentResolver,
                Settings.Global.WINDOW_ANIMATION_SCALE, scale)
            Settings.Global.putFloat(ctx.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
            Settings.Global.putFloat(ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        } catch (_: SecurityException) { /* WRITE_SECURE_SETTINGS না থাকলে silent fail */ }
    }

    /**
     * Background process limit set করো।
     * 2  = At most 2 background processes (battery ও RAM save)
     * -1 = System default (restore করতে)
     * WRITE_SECURE_SETTINGS permission দরকার
     */
    fun setBackgroundProcessLimit(ctx: Context, limit: Int) {
        try {
            Settings.Global.putInt(ctx.contentResolver,
                "background_process_limit", limit)
        } catch (_: SecurityException) { /* silent fail */ }
    }

    /** Background processes kill — ফোন instantly fast হয়। */
    fun killBackgroundApps(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.forEach { proc ->
                if (proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                    am.killBackgroundProcesses(proc.processName)
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * Ultra Save Mode এ কোন package চলতে পারবে।
     *
     * ✅ Allow: Phone, Dialer, SMS/MMS, System UI, Launcher, Keyboard, RasFocus নিজে
     * ❌ Block: বাকি সব
     */
    fun isAllowedPkg(pkg: String): Boolean =
        pkg == "com.rasel.RasFocus"     ||
        pkg == "android"                 ||
        pkg == "com.whatsapp"            ||
        pkg == "com.whatsapp.w4b"        ||
        pkg.contains("dialer")           ||
        pkg.contains("incallui")         ||
        pkg.contains(".phone")           ||
        pkg.contains("phone.")           ||
        pkg.contains("mms")              ||
        pkg.contains("messaging")        ||
        pkg.contains("sms")              ||
        pkg.contains("systemui")         ||
        pkg.contains("launcher")         ||
        pkg.contains("inputmethod")      ||
        pkg.contains("keyboard")         ||
        pkg.contains("gboard")           ||
        pkg.startsWith("com.android.server")

    // ─── Utility ─────────────────────────────────────────────────────────

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** মিলিসেকেন্ড থেকে মানুষ-পাঠযোগ্য সময় (যেমন: "১ ঘণ্টা ২৩ মিনিট") */
    fun formatRemaining(ms: Long): String {
        if (ms <= 0) return "শেষ"
        val totalSec = ms / 1000
        val hrs = totalSec / 3600
        val min = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return when {
            hrs > 0 && min > 0 -> "${hrs}h ${min}m"
            hrs > 0             -> "${hrs}h"
            min > 0             -> "${min}m ${sec}s"
            else                -> "${sec}s"
        }
    }
}
