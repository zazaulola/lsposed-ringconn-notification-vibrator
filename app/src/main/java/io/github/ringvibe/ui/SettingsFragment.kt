package io.github.ringvibe.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.ringvibe.R
import io.github.ringvibe.core.ConfigCodec
import io.github.ringvibe.core.Prefs
import io.github.ringvibe.core.StatusProbe
import io.github.ringvibe.core.Trigger

class SettingsFragment : PreferenceFragmentCompat() {

    // Settings are stored as normal private prefs. We push the whole config to the system_server
    // hook in a broadcast whenever it changes (and when the screen opens), since that hook can't
    // read our private prefs itself.
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        pushConfig()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.FILE
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        updateStatus()

        findPreference<Preference>("test_vibrate")?.setOnPreferenceClickListener {
            sendTestBuzz()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(changeListener)
        pushConfig() // sync the hook with the current settings whenever the screen opens
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(changeListener)
    }

    private fun updateStatus() {
        val status = findPreference<Preference>("status") ?: return
        status.setSummary(if (StatusProbe.isModuleActive()) R.string.status_active else R.string.status_inactive)
    }

    /** Push the full settings bundle to the system_server hook's runtime receiver (implicit). */
    private fun pushConfig() {
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        ctx.sendBroadcast(
            Intent(Prefs.ACTION_CONFIG_CHANGED).putExtra(Prefs.EXTRA_CONFIG, ConfigCodec.toBundle(prefs)),
        )
    }

    private fun sendTestBuzz() {
        val ctx = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val pkg = Prefs.ringconnPackage(prefs)
        pushConfig() // keep the system_server hook in sync too
        val intent = Intent(Trigger.ACTION_VIBRATE).apply {
            setPackage(pkg)
            putExtra(Trigger.EXTRA_REASON, Trigger.REASON_TEST)
            putExtra(Trigger.EXTRA_PATTERN, Prefs.patternNotifications(prefs))
            putExtra(Trigger.EXTRA_SOURCE_PACKAGE, Prefs.MODULE_PACKAGE)
            putExtra(Trigger.EXTRA_NONCE, System.currentTimeMillis())
            Trigger.putConfig(this, prefs) // UI reads its own prefs directly
        }
        ctx.sendBroadcast(intent)
        Toast.makeText(
            ctx,
            "Sent test buzz to $pkg — make sure the RingConn app is running and connected.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
