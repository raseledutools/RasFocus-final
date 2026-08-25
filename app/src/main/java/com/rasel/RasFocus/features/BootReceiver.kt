package com.rasel.RasFocus.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rasel.RasFocus.selfcontrol.study_tools.ReminderStorage
import com.rasel.RasFocus.selfcontrol.study_tools.ensureReminderChannel
import com.rasel.RasFocus.selfcontrol.study_tools.scheduleReminderAlarmFull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            val prefs = context.getSharedPreferences("rasfocus_prefs", Context.MODE_PRIVATE)
            val hasAcceptedTerms = prefs.getBoolean("has_accepted_terms", false)
            
            if (hasAcceptedTerms) {
                try {
                    com.rasel.RasFocus.UsageNotificationService.start(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Reschedule all active reminders — Android clears all alarms on reboot.
            // FIX: previously dropped overdue reminders silently. Now:
            //   - Future one-time reminders → reschedule as-is.
            //   - Overdue repeat reminders → find the next valid future trigger and reschedule.
            //   - Overdue one-time reminders → skip (already missed, no repeat).
            try {
                ensureReminderChannel(context)
                val now = System.currentTimeMillis()
                val items = ReminderStorage.load(context)
                val updated = items.map { item ->
                    if (!item.isActive || item.isCompleted) return@map item
                    if (item.triggerMillis > now) {
                        scheduleReminderAlarmFull(context, item)
                        item
                    } else {
                        val interval = com.rasel.RasFocus.selfcontrol.study_tools.repeatIntervalMillis(item)
                        if (interval != null && interval > 0L) {
                            var next = item.triggerMillis
                            while (next <= now) next += interval
                            val rescheduled = item.copy(triggerMillis = next)
                            scheduleReminderAlarmFull(context, rescheduled)
                            rescheduled
                        } else {
                            // One-time overdue — mark completed so it disappears from active list
                            item.copy(isCompleted = true, isActive = false)
                        }
                    }
                }
                ReminderStorage.save(context, updated)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Auto-update disabled — user initiates updates manually from the app
            // com.rasel.RasFocus.AutoUpdater.setupBackgroundAutoUpdate(context)
        }
    }
}
