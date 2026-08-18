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

            // Reschedule all active reminders — Android clears all alarms on reboot
            try {
                ensureReminderChannel(context)
                val now = System.currentTimeMillis()
                ReminderStorage.load(context)
                    .filter { it.isActive && !it.isCompleted && it.triggerMillis > now }
                    .forEach { scheduleReminderAlarmFull(context, it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Auto-update disabled — user initiates updates manually from the app
            // com.rasel.RasFocus.AutoUpdater.setupBackgroundAutoUpdate(context)
        }
    }
}
