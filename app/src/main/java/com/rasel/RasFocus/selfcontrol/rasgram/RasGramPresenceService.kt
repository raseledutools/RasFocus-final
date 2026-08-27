package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.rasel.RasFocus.R

/**
 * RasGramPresenceService
 *
 * Data/WiFi চালু থাকলেই online দেখাবে — এমনকি app background এ থাকলেও।
 *
 * কীভাবে কাজ করে:
 *   • Foreground Service হিসেবে চলে → Android তাকে kill করে না
 *   • Firebase RTDB ".info/connected" watch করে
 *   • Network আসলে → presence/{mobile}: {online:true, lastActive: ServerTimestamp}
 *   • onDisconnect → presence/{mobile}: {online:false, lastActive: ServerTimestamp}
 *     (Firebase server নিজেই এটা করে — phone বন্ধ/data গেলেও কাজ করে)
 *   • Firestore lastActive ও update করে (contact list "last seen" এর জন্য)
 *
 * ব্যাটারি impact: negligible — RTDB long-poll connection মাত্র, কোনো polling নেই।
 * Notification: IMPORTANCE_NONE channel + PRIORITY_MIN → notification shade এ দেখায় না।
 */
class RasGramPresenceService : Service() {

    private var mobile: String? = null
    private var connectedListener: ValueEventListener? = null

    override fun onCreate() {
        super.onCreate()
        // Android 14+ (UPSIDE_DOWN_CAKE): startForeground এ serviceType দিতে হয়,
        // specialUse = generic background work, IMPORTANCE_NONE channel এ notification আসে না।
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                PRESENCE_NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(PRESENCE_NOTIF_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mobile number: intent থেকে আসে অথবা SharedPrefs থেকে পড়া হয়
        val mob = intent?.getStringExtra(EXTRA_MOBILE)
            ?: getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_MOBILE, null)

        if (mob.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (mobile == mob) return START_STICKY   // already running for this user

        mobile = mob
        setupPresence(mob)

        return START_STICKY   // OS restarts this service if killed
    }

    private fun setupPresence(mob: String) {
        val rtdb = FirebaseDatabase.getInstance()
        val presenceRef = rtdb.getReference("presence").child(mob)
        val connectedRef = rtdb.getReference(".info/connected")

        // Remove old listener if re-entering
        connectedListener?.let { connectedRef.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    // ── Online ──────────────────────────────────────────────────
                    // onDisconnect: phone বন্ধ/data গেলে Firebase server নিজেই এটা লিখবে
                    presenceRef.onDisconnect().setValue(
                        mapOf("online" to false, "lastActive" to ServerValue.TIMESTAMP)
                    )
                    // এখন online mark করো
                    presenceRef.setValue(
                        mapOf("online" to true, "lastActive" to ServerValue.TIMESTAMP)
                    )
                    // Firestore lastActive ও update করো (contact list "last seen" এর জন্য)
                    FirebaseFirestore.getInstance()
                        .collection("chat_users")
                        .document(mob)
                        .update("lastActive", System.currentTimeMillis())
                }
                // connected=false: onDisconnect ইতিমধ্যে scheduled → server handle করবে
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        connectedRef.addValueEventListener(listener)
        connectedListener = listener
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service বন্ধ হচ্ছে = user logged out বা force stop
        // Firestore এ offline mark করো
        val mob = mobile ?: return
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val presenceRef = rtdb.getReference("presence").child(mob)
            presenceRef.setValue(
                mapOf("online" to false, "lastActive" to ServerValue.TIMESTAMP)
            )
            FirebaseFirestore.getInstance()
                .collection("chat_users")
                .document(mob)
                .update("lastActive", System.currentTimeMillis())
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_NONE = channel এ কোনো notification দেখাবে না,
            // কিন্তু foreground service চালু রাখার জন্য technically তৈরি থাকবে।
            if (nm.getNotificationChannel(PRESENCE_CHANNEL) == null) {
                NotificationChannel(
                    PRESENCE_CHANNEL,
                    "RasGram Online Status",
                    NotificationManager.IMPORTANCE_NONE   // ← NONE = invisible
                ).apply {
                    description      = "Keeps your online status active"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }.also { nm.createNotificationChannel(it) }
            }
        }

        val builder = NotificationCompat.Builder(this, PRESENCE_CHANNEL)
            .setSmallIcon(R.drawable.ic_rasgram_notif)
            .setContentTitle("")
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_MIN)   // সবচেয়ে কম priority
            .setSilent(true)
            .setOngoing(false)          // ← false = user চাইলে swipe করে dismiss করতে পারবে
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // lock screen এও লুকানো

        // Android 12+ (S): system কে বলছি — notification shade এ show করো না,
        // শুধু 10 সেকেন্ড পরে দেখাও (বেশিরভাগ সময় দেখাই যায় না)।
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_DEFERRED
            )
        }

        return builder.build()
    }

    companion object {
        const val PRESENCE_NOTIF_ID = 7776
        const val PRESENCE_CHANNEL  = "RASGRAM_PRESENCE"
        const val EXTRA_MOBILE      = "mobile"
        const val PREF_NAME         = "rasgram_prefs"
        const val PREF_MOBILE       = "saved_mobile"

        /** App open হলে বা login হলে call করো */
        fun start(context: Context, mobile: String) {
            val intent = Intent(context, RasGramPresenceService::class.java)
                .putExtra(EXTRA_MOBILE, mobile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        /** Logout হলে call করো */
        fun stop(context: Context) {
            context.stopService(Intent(context, RasGramPresenceService::class.java))
        }
    }
}
