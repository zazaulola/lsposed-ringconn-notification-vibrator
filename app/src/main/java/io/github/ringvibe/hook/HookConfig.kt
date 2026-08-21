package io.github.ringvibe.hook

import android.content.SharedPreferences
import android.os.Bundle
import io.github.ringvibe.core.BundlePrefs

/**
 * The system_server hook's view of the settings. It's a cache fed by the [Prefs.ACTION_CONFIG_CHANGED]
 * broadcast that the settings UI (and a boot receiver) send with the full config as a Bundle. Until
 * the first push arrives, every accessor falls back to the built-in defaults, so detection works out
 * of the box; once the user configures anything, the UI pushes and this reflects it.
 */
object HookConfig {

    @Volatile private var cached: SharedPreferences = BundlePrefs(Bundle())

    fun get(): SharedPreferences = cached

    fun update(config: Bundle?) {
        if (config != null) {
            cached = BundlePrefs(Bundle(config))
            XLog.d("config updated (${config.size()} keys)")
        }
    }
}
