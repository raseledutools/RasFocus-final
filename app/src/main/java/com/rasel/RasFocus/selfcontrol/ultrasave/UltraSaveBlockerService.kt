package com.rasel.RasFocus.selfcontrol.ultrasave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.rasel.RasFocus.R

/**
 * UltraSaveBlockerService
 *
 * Ultra Save Mode চালু থাকলে এই Service প্রতি 500ms এ UsageStatsManager দিয়ে
 * foreground app check করে। Phone, SMS, WhatsApp ছাড়া অন্য কোনো app সামনে
 * এলে সরাসরি Home-এ পাঠিয়ে দেয় এবং একটা overlay দেখায়।
 *
 * Accessibility Permission লাগে না — শুধু PACKAGE_USAGE_STATS এবং
 * SYSTEM_ALERT_WINDOW দরকার।
 *
 * start/stop: UltraSaveManager.activate()/deactivate() থেকে call করো।
 */
class UltraSaveBlockerService : Service() {

    companion object {
        private const val NOTIF_CHANNEL = "ultra_save_blocker"
        private const val NOTIF_ID = 3001
        private const val POLL_INTERVAL_MS = 500L

        fun start(ctx: Context) {
            val intent = Intent(ctx, UltraSaveBlockerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, UltraSaveBlockerService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var lastBlockedPkg: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!UltraSaveManager.isActive(applicationContext)) {
                // Mode বন্ধ হয়ে গেছে — service নিজেই থামুক
                stopSelf()
                return
            }
            checkForeground()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        hideOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Core Logic ────────────────────────────────────────────────────

    private fun checkForeground() {
        val pkg = getForegroundPackage() ?: return

        if (UltraSaveManager.isAllowedPkg(pkg)) {
            // allowed app — overlay লুকাও
            if (overlayView != null) hideOverlay()
            return
        }

        // blocked app — home এ পাঠাও + overlay দেখাও
        sendHome()
        if (lastBlockedPkg != pkg) {
            lastBlockedPkg = pkg
            showOverlay()
        }
    }

    private fun getForegroundPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 3_000, end)
            val event = UsageEvents.Event()
            var lastPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastPkg = event.packageName
                }
            }
            lastPkg
        } catch (_: Exception) { null }
    }

    private fun sendHome() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(homeIntent)
        } catch (_: Exception) {}
    }

    // ── Overlay ───────────────────────────────────────────────────────

    private fun showOverlay() {
        if (!canDrawOverlay()) return
        hideOverlay()

        val dp = resources.displayMetrics.density

        val frame = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
        }

        val text = TextView(this).apply {
            text = "⚡ Ultra Save Mode\nPhone, SMS ও WhatsApp ছাড়া\nঅন্য app বন্ধ আছে।"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(
                (24 * dp).toInt(), (24 * dp).toInt(),
                (24 * dp).toInt(), (24 * dp).toInt()
            )
        }
        frame.addView(text)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (120 * dp).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        try {
            windowManager?.addView(frame, params)
            overlayView = frame
            // 1.5s পর নিজে থেকে সরে যাবে
            handler.postDelayed({ hideOverlay() }, 1500L)
        } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        lastBlockedPkg = null
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.provider.Settings.canDrawOverlays(this)
        else true

    // ── Notification ──────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "Ultra Save Blocker",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("⚡ Ultra Save Mode চালু")
            .setContentText("Phone, SMS ও WhatsApp ছাড়া সব block।")
            .setSmallIcon(R.drawable.ic_notif_lock_locked)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}
