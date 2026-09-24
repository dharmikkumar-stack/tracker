package com.kidsafe.beacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the listener automatically after the phone boots, after a power-on,
 * and after the app itself is updated — but only if the parent had monitoring on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return

        val prefs = Prefs(context)
        if (prefs.monitoringEnabled && prefs.isConfigured()) {
            CommandService.start(context)
        }
    }
}
