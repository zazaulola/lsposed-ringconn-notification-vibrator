package io.github.ringvibe.core

import android.content.SharedPreferences
import android.os.Bundle

/** Serialize the settings to a [Bundle] for pushing to the hooks in a broadcast, and back. */
object ConfigCodec {

    fun toBundle(prefs: SharedPreferences): Bundle {
        val b = Bundle()
        for ((k, v) in prefs.all) {
            when (v) {
                is Boolean -> b.putBoolean(k, v)
                is Int -> b.putInt(k, v)
                is Long -> b.putLong(k, v)
                is Float -> b.putFloat(k, v)
                is String -> b.putString(k, v)
                else -> { /* skip unsupported types */ }
            }
        }
        return b
    }
}
