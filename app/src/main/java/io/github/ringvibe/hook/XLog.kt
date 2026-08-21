package io.github.ringvibe.hook

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Logging for the hook side. Info/errors always go to the LSPosed module log (visible in the
 * LSPosed manager) and to logcat under tag "RingVibe"; debug lines are gated on the module's
 * "Verbose logging" preference.
 */
object XLog {
    private const val TAG = "RingVibe"

    @Volatile
    var verbose = false

    fun i(msg: String) {
        XposedBridge.log("[$TAG] $msg")
        Log.i(TAG, msg)
    }

    fun d(msg: String) {
        if (!verbose) return
        XposedBridge.log("[$TAG] $msg")
        Log.d(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("[$TAG] ERROR: $msg")
        if (t != null) XposedBridge.log(t)
        Log.e(TAG, msg, t)
    }
}
