package io.github.ringvibe.core

import android.content.SharedPreferences

/**
 * Single source of truth for preference keys, defaults and typed access.
 *
 * The configuration app writes these through the normal [SharedPreferences]; the hooks read them
 * through `XSharedPreferences`, which exposes the same [SharedPreferences] interface. Every accessor
 * here therefore takes a plain [SharedPreferences] so both sides share one implementation.
 */
object Prefs {

    /** Name of the shared-preferences file the settings UI writes (private mode). */
    const val FILE = "ringvibe_prefs"

    /** This module's own package. */
    const val MODULE_PACKAGE = "io.github.ringvibe"

    /**
     * Config reaches the system_server hook by being PUSHED in a broadcast, not pulled: some LSPosed
     * forks (e.g. "Vector") don't round-trip MODE_WORLD_READABLE, and system_server shouldn't block
     * on a third-party ContentProvider. The UI (and a boot receiver) read the private prefs and send
     * the whole config as a [Bundle] extra; the hook caches it. See [ConfigCodec] and [HookConfig].
     */
    const val ACTION_CONFIG_CHANGED = "io.github.ringvibe.action.CONFIG_CHANGED"
    const val EXTRA_CONFIG = "config"

    // --- keys ---
    const val KEY_TRIGGER_CALLS = "trigger_calls"
    const val KEY_TRIGGER_NOTIFICATIONS = "trigger_notifications"
    const val KEY_PATTERN_CALLS = "pattern_calls"
    const val KEY_PATTERN_NOTIFICATIONS = "pattern_notifications"
    const val KEY_CALLS_REPEAT = "calls_repeat"
    const val KEY_FILTER_ONGOING = "filter_ongoing"
    const val KEY_FILTER_LOW = "filter_low"
    const val KEY_FILTER_GROUP_SUMMARY = "filter_group_summary"
    const val KEY_ONLY_SCREEN_OFF = "only_screen_off"
    const val KEY_ALLOWLIST = "app_allowlist"
    const val KEY_BLOCKLIST = "app_blocklist"
    const val KEY_COOLDOWN_MS = "cooldown_ms"
    const val KEY_RINGCONN_PACKAGE = "ringconn_package"
    const val KEY_COMMAND_SOURCE = "command_source"
    const val KEY_VIBRATE_CMD_HEX = "vibrate_cmd_hex"
    const val KEY_WRITE_SERVICE_UUID = "write_service_uuid"
    const val KEY_WRITE_CHAR_UUID = "write_char_uuid"
    const val KEY_DEBUG_LOGGING = "debug_logging"

    // --- defaults ---
    // Official RingConn app (Flutter). Serves all generations; verify on your device and override
    // if it ever differs. Whatever you set here must also be in this module's LSPosed scope.
    const val DEFAULT_RINGCONN_PACKAGE = "com.gdjztech.ringconn"

    // Known, reverse-engineered RingConn transport (github.com/perezjuanj/OpenCircuit). Identical
    // across Gen 2 / Gen 3. Commands are [cmd][sub][payload...][00], write-with-response.
    const val DEFAULT_WRITE_SERVICE_UUID = "8327ad99-2d87-4a22-a8ce-6dd7971c0437"
    const val DEFAULT_WRITE_CHAR_UUID = "8327ad98-2d87-4a22-a8ce-6dd7971c0437"

    // Gen 3 vibrate command, captured live from the official app when it buzzed the ring on a
    // manual measurement completion (2026-08-21): opcode 0x0B, sub 0x03, payload 01 64.
    const val DEFAULT_VIBRATE_CMD = "0B 03 01 64 00"

    // Device-verified Find-My-Ring locator LED command — the fallback "something happened" signal.
    // It lights the ring; it does not vibrate. Used when command source is set to LED.
    const val LED_ON_CMD = "24 01 00"
    const val LED_OFF_CMD = "24 00 00"

    const val COMMAND_VIBRATE = "vibrate"
    const val COMMAND_LED = "led"

    const val DEFAULT_COMMAND_SOURCE = COMMAND_VIBRATE
    const val DEFAULT_PATTERN_CALLS = "double"
    const val DEFAULT_PATTERN_NOTIFICATIONS = "short"
    const val DEFAULT_CALLS_REPEAT = 3
    const val DEFAULT_COOLDOWN_MS = 1500

    fun triggerCalls(p: SharedPreferences) = p.getBoolean(KEY_TRIGGER_CALLS, true)
    fun triggerNotifications(p: SharedPreferences) = p.getBoolean(KEY_TRIGGER_NOTIFICATIONS, true)
    fun patternCalls(p: SharedPreferences) = p.getString(KEY_PATTERN_CALLS, DEFAULT_PATTERN_CALLS) ?: DEFAULT_PATTERN_CALLS
    fun patternNotifications(p: SharedPreferences) = p.getString(KEY_PATTERN_NOTIFICATIONS, DEFAULT_PATTERN_NOTIFICATIONS) ?: DEFAULT_PATTERN_NOTIFICATIONS
    fun callsRepeat(p: SharedPreferences) = p.getInt(KEY_CALLS_REPEAT, DEFAULT_CALLS_REPEAT).coerceIn(1, 6)
    fun filterOngoing(p: SharedPreferences) = p.getBoolean(KEY_FILTER_ONGOING, true)
    fun filterLow(p: SharedPreferences) = p.getBoolean(KEY_FILTER_LOW, true)
    fun filterGroupSummary(p: SharedPreferences) = p.getBoolean(KEY_FILTER_GROUP_SUMMARY, true)
    fun onlyScreenOff(p: SharedPreferences) = p.getBoolean(KEY_ONLY_SCREEN_OFF, false)
    fun cooldownMs(p: SharedPreferences) = p.getInt(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS).toLong()
    fun debugLogging(p: SharedPreferences) = p.getBoolean(KEY_DEBUG_LOGGING, false)
    fun commandSource(p: SharedPreferences) = p.getString(KEY_COMMAND_SOURCE, DEFAULT_COMMAND_SOURCE) ?: DEFAULT_COMMAND_SOURCE

    fun ringconnPackage(p: SharedPreferences): String {
        val v = p.getString(KEY_RINGCONN_PACKAGE, DEFAULT_RINGCONN_PACKAGE)?.trim()
        return if (v.isNullOrEmpty()) DEFAULT_RINGCONN_PACKAGE else v
    }

    fun allowlist(p: SharedPreferences) = parsePackages(p.getString(KEY_ALLOWLIST, ""))
    fun blocklist(p: SharedPreferences) = parsePackages(p.getString(KEY_BLOCKLIST, ""))

    fun vibrateCmdHex(p: SharedPreferences): String {
        val v = p.getString(KEY_VIBRATE_CMD_HEX, DEFAULT_VIBRATE_CMD)?.trim()
        return if (v.isNullOrEmpty()) DEFAULT_VIBRATE_CMD else v
    }

    fun writeServiceUuid(p: SharedPreferences): String {
        val v = p.getString(KEY_WRITE_SERVICE_UUID, DEFAULT_WRITE_SERVICE_UUID)?.trim()
        return if (v.isNullOrEmpty()) DEFAULT_WRITE_SERVICE_UUID else v
    }

    fun writeCharUuid(p: SharedPreferences): String {
        val v = p.getString(KEY_WRITE_CHAR_UUID, DEFAULT_WRITE_CHAR_UUID)?.trim()
        return if (v.isNullOrEmpty()) DEFAULT_WRITE_CHAR_UUID else v
    }

    /** Split a free-text package list on commas, whitespace and newlines. */
    fun parsePackages(raw: String?): Set<String> =
        raw?.split(',', '\n', '\r', ' ', '\t')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
}
