package io.github.ringvibe.core

/**
 * IPC contract shared by both sides of the module.
 *
 * Detection runs inside `system_server` (see [io.github.ringvibe.hook.SystemHooks]); the code that
 * actually talks to the ring runs inside the RingConn app process (see
 * [io.github.ringvibe.hook.RingConnHooks]). Xposed hooks execute in whichever process they are
 * injected into and cannot call across process boundaries directly, so the two sides communicate
 * with an explicit-package broadcast: system_server sends, the RingConn process receives.
 */
object Trigger {

    /** Explicit broadcast action carrying a vibrate request to the RingConn process. */
    const val ACTION_VIBRATE = "io.github.ringvibe.action.VIBRATE"

    /** Reason for the buzz: [REASON_CALL], [REASON_NOTIFICATION] or [REASON_TEST]. */
    const val EXTRA_REASON = "reason"

    /** The resolved [VibrationPattern] name to play, e.g. "short" / "double". */
    const val EXTRA_PATTERN = "pattern"

    /** Source package that triggered the buzz (for logging / dedup). */
    const val EXTRA_SOURCE_PACKAGE = "source_pkg"

    /** Monotonic nonce so a receiver can drop duplicate/stale broadcasts. */
    const val EXTRA_NONCE = "nonce"

    const val REASON_CALL = "call"
    const val REASON_NOTIFICATION = "notification"
    const val REASON_TEST = "test"

    // The command config travels IN the broadcast, resolved by the sender (system_server, or the
    // settings UI). The RingConn process can't read our ContentProvider itself because Android 11+
    // package visibility hides our package from it — so it just executes what it's handed.
    const val EXTRA_USE_LED = "use_led"
    const val EXTRA_VIBRATE_HEX = "vibrate_hex"
    const val EXTRA_SERVICE_UUID = "service_uuid"
    const val EXTRA_CHAR_UUID = "char_uuid"

    /** Resolve the command config from [prefs] and attach it to a vibrate broadcast. */
    fun putConfig(intent: android.content.Intent, prefs: android.content.SharedPreferences) {
        val useLed = Prefs.commandSource(prefs) == Prefs.COMMAND_LED ||
            Hex.parse(Prefs.vibrateCmdHex(prefs)) == null
        intent.putExtra(EXTRA_USE_LED, useLed)
        intent.putExtra(EXTRA_VIBRATE_HEX, Prefs.vibrateCmdHex(prefs))
        intent.putExtra(EXTRA_SERVICE_UUID, Prefs.writeServiceUuid(prefs))
        intent.putExtra(EXTRA_CHAR_UUID, Prefs.writeCharUuid(prefs))
    }
}
