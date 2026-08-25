package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.*
import android.content.Context
import android.content.Intent
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
 * Notification: Priority.MIN → notification bar এ সবার নিচে, কোনো sound/vibration নেই।
 */
class RasGramPresenceService : Service() {

    private var mobile: String? = null
    private var connectedListener: ValueEventListener? = null

    override fun onCreate() {
        super.onCreate()
        // FIX: user requested to completely remove the persistent notification.
        // The service will now run as a normal background service.
        // Android will kill it shortly after the app goes to the background,
        // which will trigger the RTDB onDisconnect() hook to mark the user as offline.
        // This gives accurate WhatsApp-style "Online" status (only online while using the app).
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

    companion object {
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
