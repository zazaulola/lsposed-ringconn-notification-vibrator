package io.github.ringvibe.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.ringvibe.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.title = getString(R.string.app_name)
    }
}
