package io.github.ringvibe.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pushes the saved settings to the system_server hook on boot, so custom configuration applies
 * without the user having to open the app first. (Runs after unlock, when private prefs are
 * available.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        context.sendBroadcast(
            Intent(Prefs.ACTION_CONFIG_CHANGED).putExtra(Prefs.EXTRA_CONFIG, ConfigCodec.toBundle(prefs)),
        )
    }
}
