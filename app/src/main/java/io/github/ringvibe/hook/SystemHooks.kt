package io.github.ringvibe.hook

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.ringvibe.core.Prefs
import io.github.ringvibe.core.Trigger

/**
 * Runs inside system_server (lpparam.packageName == "android"). Detects posted notifications and
 * incoming calls at the framework's central dispatch points, applies the user's filters, and fires
 * an explicit-package broadcast to the RingConn process which does the actual BLE write.
 */
object SystemHooks {

    private const val CALL_STATE_IDLE = 0
    private const val CALL_STATE_RINGING = 1

    // If we never see the terminating IDLE/OFFHOOK for a call, the ringing latch would wedge and
    // suppress all future call buzzes. Treat a RINGING dispatch this long after the latch was set as
    // a fresh call, so the latch self-heals instead of staying stuck forever.
    private const val RINGING_STALE_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var systemContext: Context? = null

    private var lastNotifBuzz = 0L
    @Volatile private var ringing = false
    private var ringingSince = 0L
    private var callRepeat: Runnable? = null
    @Volatile private var configReceiverRegistered = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        hookNotifications(cl)
        hookCalls(cl)
        // Settings are pushed to us in a broadcast by the UI / boot receiver; cache them.
        mainHandler.post { ensureConfigReceiver() }
        XLog.i("system_server detection hooks installed")
    }

    /**
     * Register the config receiver, retrying until the system context exists. At handleLoadPackage
     * time the ActivityThread system context isn't ready yet, so a single early attempt would
     * silently no-op and we'd never receive the UI's pushed settings.
     */
    private fun ensureConfigReceiver() {
        if (configReceiverRegistered) return
        val ctx = systemContext()
        if (ctx == null) {
            mainHandler.postDelayed({ ensureConfigReceiver() }, 2000)
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Prefs.ACTION_CONFIG_CHANGED) HookConfig.update(i.getBundleExtra(Prefs.EXTRA_CONFIG))
            }
        }
        val filter = IntentFilter(Prefs.ACTION_CONFIG_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            configReceiverRegistered = true
            XLog.i("config receiver registered")
        } catch (t: Throwable) {
            XLog.e("register config receiver (system)", t)
        }
    }

    // --- Notifications ---------------------------------------------------------------------------

    private fun hookNotifications(cl: ClassLoader) {
        val nms = XposedHelpers.findClass(
            "com.android.server.notification.NotificationManagerService", cl,
        )
        // Parameter list of enqueueNotificationInternal changes across Android versions, so hook
        // every overload by name. Package is arg[0], Notification is arg[6] in all known variants.
        val hooked = XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    onNotification(param.args)
                } catch (t: Throwable) {
                    XLog.e("notification hook", t)
                }
            }
        })
        // hookAllMethods returns an empty set (it does NOT throw) if the method name doesn't exist
        // on this Android build. Log that loudly, otherwise notifications would silently never buzz.
        if (hooked.isEmpty()) {
            XLog.e("enqueueNotificationInternal not found — notification buzzes DISABLED on this ROM")
        } else {
            XLog.i("hooked enqueueNotificationInternal (${hooked.size} overloads)")
        }
    }

    private fun onNotification(args: Array<Any?>) {
        val prefs = HookConfig.get()
        XLog.verbose = Prefs.debugLogging(prefs)
        if (!Prefs.triggerNotifications(prefs)) return

        // pkg is the first arg in every known version; find the Notification by type so the hook
        // survives parameter-list changes across Android releases (it is the sole Notification arg).
        val pkg = args.getOrNull(0) as? String ?: return
        val notification = args.firstNotNullOfOrNull { it as? Notification } ?: return

        // Never react to ourselves; that would be a feedback loop.
        if (pkg == Prefs.MODULE_PACKAGE) return

        val block = Prefs.blocklist(prefs)
        if (pkg in block) return
        val allow = Prefs.allowlist(prefs)
        if (allow.isNotEmpty() && pkg !in allow) return

        val flags = notification.flags
        if (Prefs.filterOngoing(prefs) &&
            (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        ) return
        if (Prefs.filterGroupSummary(prefs) && (flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        @Suppress("DEPRECATION")
        if (Prefs.filterLow(prefs) && notification.priority < Notification.PRIORITY_DEFAULT) return

        if (Prefs.onlyScreenOff(prefs) && isInteractive()) return

        // enqueueNotificationInternal can run on concurrent binder threads; guard the cooldown so
        // a burst of notifications produces one buzz, not several.
        synchronized(this) {
            val now = SystemClock.uptimeMillis()
            if (now - lastNotifBuzz < Prefs.cooldownMs(prefs)) return
            lastNotifBuzz = now
        }

        XLog.d("notification from $pkg -> buzz")
        sendBuzz(prefs, Trigger.REASON_NOTIFICATION, Prefs.patternNotifications(prefs), pkg)
    }

    // --- Calls -----------------------------------------------------------------------------------

    private fun hookCalls(cl: ClassLoader) {
        val tr = XposedHelpers.findClass("com.android.server.TelephonyRegistry", cl)

        // Legacy all-subscriptions fan-out: notifyCallStateForAllSubs(int state, String number).
        val allSubs = XposedBridge.hookAllMethods(tr, "notifyCallStateForAllSubs", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    (param.args.getOrNull(0) as? Int)?.let { onCallState(it) }
                } catch (t: Throwable) {
                    XLog.e("call hook (all subs)", t)
                }
            }
        })

        // Per-subscription variant: notifyCallState(int phoneId, int subId, int state, String num).
        // Present on most versions; guard in case it isn't. State is arg[2] here.
        val perSub = try {
            XposedBridge.hookAllMethods(tr, "notifyCallState", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        (param.args.getOrNull(2) as? Int)?.let { onCallState(it) }
                    } catch (t: Throwable) {
                        XLog.e("call hook (per sub)", t)
                    }
                }
            })
        } catch (t: Throwable) {
            XLog.d("notifyCallState not hookable on this version: ${t.message}")
            emptySet<Any?>()
        }

        if (allSubs.isEmpty() && perSub.isEmpty()) {
            XLog.e("no TelephonyRegistry call-state method hooked — call buzzes DISABLED on this ROM")
        } else {
            XLog.i("hooked call-state (allSubs=${allSubs.size}, perSub=${perSub.size})")
        }
    }

    /**
     * Both call hooks funnel here; a [ringing] latch de-dupes the repeated RINGING dispatches (the
     * two hooked methods can each fire for the same event, on concurrent threads).
     */
    @Synchronized
    private fun onCallState(state: Int) {
        val prefs = HookConfig.get()
        XLog.verbose = Prefs.debugLogging(prefs)
        if (!Prefs.triggerCalls(prefs)) return

        if (state == CALL_STATE_RINGING) {
            val now = SystemClock.uptimeMillis()
            // Genuine duplicate of the current ringing session: ignore. A RINGING seen long after
            // the latch was set means we missed the previous call's end — re-arm rather than wedge.
            if (ringing && now - ringingSince < RINGING_STALE_MS) return
            ringing = true
            ringingSince = now
            startCallBuzz(prefs)
        } else {
            // OFFHOOK (answered) or IDLE (ended/rejected): stop re-buzzing.
            if (ringing || state == CALL_STATE_IDLE) {
                ringing = false
                stopCallBuzz()
            }
        }
    }

    private fun startCallBuzz(prefs: android.content.SharedPreferences) {
        val pattern = Prefs.patternCalls(prefs)
        val maxRepeats = Prefs.callsRepeat(prefs)
        XLog.d("incoming call -> buzz x$maxRepeats")
        sendBuzz(prefs, Trigger.REASON_CALL, pattern, "incoming-call")

        var count = 1
        val r = object : Runnable {
            override fun run() {
                if (!ringing || count >= maxRepeats) return
                sendBuzz(HookConfig.get(), Trigger.REASON_CALL, pattern, "incoming-call")
                count++
                mainHandler.postDelayed(this, 2500)
            }
        }
        callRepeat = r
        mainHandler.postDelayed(r, 2500)
    }

    private fun stopCallBuzz() {
        callRepeat?.let { mainHandler.removeCallbacks(it) }
        callRepeat = null
    }

    // --- Dispatch ---------------------------------------------------------------------------------

    private fun sendBuzz(
        prefs: android.content.SharedPreferences,
        reason: String,
        pattern: String,
        source: String,
    ) {
        val ctx = systemContext() ?: run { XLog.e("no system context; cannot send buzz"); return }
        val pkg = Prefs.ringconnPackage(prefs)
        val intent = Intent(Trigger.ACTION_VIBRATE).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(Trigger.EXTRA_REASON, reason)
            putExtra(Trigger.EXTRA_PATTERN, pattern)
            putExtra(Trigger.EXTRA_SOURCE_PACKAGE, source)
            putExtra(Trigger.EXTRA_NONCE, SystemClock.uptimeMillis())
            // Resolve the command config here (system_server can read our provider) and hand it to
            // the RingConn process, which can't read the provider itself.
            Trigger.putConfig(this, prefs)
        }
        try {
            // system_server is multi-user; broadcast to all users so the RingConn process (which
            // runs in the foreground user) receives it. system_server holds INTERACT_ACROSS_USERS.
            val userAll = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL") as UserHandle
            XposedHelpers.callMethod(ctx, "sendBroadcastAsUser", intent, userAll)
        } catch (t: Throwable) {
            XLog.d("sendBroadcastAsUser failed, falling back: ${t.message}")
            ctx.sendBroadcast(intent)
        }
        XLog.d("buzz -> $pkg reason=$reason pattern=$pattern src=$source")
    }

    private fun systemContext(): Context? {
        systemContext?.let { return it }
        return try {
            val at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread",
            )
            (XposedHelpers.callMethod(at, "getSystemContext") as Context).also { systemContext = it }
        } catch (t: Throwable) {
            XLog.e("cannot obtain system context", t)
            null
        }
    }

    private fun isInteractive(): Boolean {
        val ctx = systemContext() ?: return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }
}
