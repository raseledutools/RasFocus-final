package com.rasel.RasFocus.selfcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast

/**
 * Browser / অন্য app থেকে "Add to Home screen" করলে Android এই activity কে
 * CONFIRM_PIN_SHORTCUT intent পাঠায়। এখানে request accept করে shortcut টা
 * launcher এর pinned list এ যোগ করা হয়।
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val request = la.getPinItemRequest(intent)
            val info = request?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }
                ?.shortcutInfo
            if (request != null && info != null && request.accept()) {
                val label = (info.shortLabel ?: info.longLabel ?: info.id).toString().replace("|", " ")
                val key = "$SHORTCUT_PREFIX${info.`package`}|${info.id}"
                val prefs = getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

                val saved = prefs.getStringSet(KEY_PINNED_SHORTCUTS, emptySet()).orEmpty().toMutableSet()
                saved.removeAll { it.startsWith("${info.`package`}|${info.id}|") }
                saved.add("${info.`package`}|${info.id}|$label")

                val order = prefs.getString("pinned_order", "").orEmpty()
                    .split(",").filter { it.isNotBlank() }.toMutableList()
                if (order.isEmpty()) order.addAll(prefs.getStringSet("pinned_apps", emptySet()).orEmpty())
                if (key !in order) order.add(key)

                prefs.edit()
                    .putStringSet(KEY_PINNED_SHORTCUTS, saved)
                    .putStringSet("pinned_apps", order.toSet())
                    .putString("pinned_order", order.joinToString(","))
                    .apply()

                Toast.makeText(this, "Shortcut added: $label", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LauncherActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("reload_shortcuts", true)
                })
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Shortcut add failed", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
