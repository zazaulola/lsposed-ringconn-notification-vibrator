package io.github.ringvibe.core

import android.content.SharedPreferences
import android.os.Bundle

/**
 * A read-only [SharedPreferences] backed by a [Bundle] pushed to the hook in a broadcast. This lets
 * the hooks reuse every typed accessor in [Prefs] unchanged, while the actual values arrive as
 * broadcast extras instead of a shared file. Missing keys fall through to the caller's default.
 */
class BundlePrefs(private val b: Bundle) : SharedPreferences {

    override fun getBoolean(key: String, defValue: Boolean) =
        if (b.containsKey(key)) b.getBoolean(key, defValue) else defValue

    override fun getInt(key: String, defValue: Int) =
        if (b.containsKey(key)) b.getInt(key, defValue) else defValue

    override fun getLong(key: String, defValue: Long) =
        if (b.containsKey(key)) b.getLong(key, defValue) else defValue

    override fun getFloat(key: String, defValue: Float) =
        if (b.containsKey(key)) b.getFloat(key, defValue) else defValue

    override fun getString(key: String, defValue: String?): String? =
        if (b.containsKey(key)) b.getString(key, defValue) else defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues

    override fun getAll(): MutableMap<String, *> =
        HashMap<String, Any?>().apply { for (k in b.keySet()) put(k, b.get(k)) }

    override fun contains(key: String) = b.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only")
}
