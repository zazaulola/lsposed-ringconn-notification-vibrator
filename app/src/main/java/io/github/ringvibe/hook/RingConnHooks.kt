package io.github.ringvibe.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.ringvibe.core.Hex
import io.github.ringvibe.core.Prefs
import io.github.ringvibe.core.Trigger
import io.github.ringvibe.core.VibrationPattern
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Runs inside the RingConn app process. The app is Flutter, so its command logic lives in native
 * libapp.so and is out of Xposed's reach — but the actual GATT write goes through the Java framework
 * class android.bluetooth.BluetoothGatt. We therefore:
 *
 *  1. Hook BluetoothGatt so we can grab the app's *live, already-bonded* connection to the ring.
 *  2. Register a receiver for the trigger broadcast from system_server.
 *  3. On a trigger, write our command bytes onto that live connection — reusing the app's LE bond
 *     and per-connection auth, which an independent connection could not.
 */
object RingConnHooks {

    private const val WRITE_TYPE_DEFAULT = 2 // BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    // The RingConn app shares this GATT connection, and Android allows one outstanding op at a time,
    // so writes often come back "busy" while the app is active. Retry generously with growing
    // backoff so a buzz/LED command reliably lands.
    private const val MAX_WRITE_RETRIES = 10
    private const val RETRY_DELAY_MS = 140L
    private const val LED_ON_MS = 380L

    private var installed = false
    private var receiver: BroadcastReceiver? = null

    /** The RingConn app's live BluetoothGatt for the ring. Weak so we never keep a dead one alive. */
    @Volatile private var gattRef: WeakReference<Any>? = null

    private lateinit var bgHandler: Handler

    // Start with the known-correct defaults; refreshed from config once the app context exists.
    private var serviceUuid = Prefs.DEFAULT_WRITE_SERVICE_UUID
    private var charUuid = Prefs.DEFAULT_WRITE_CHAR_UUID

    /**
     * Called for any scoped app that isn't system_server or our own app — by default only
     * com.gdjztech.ringconn. We can't read config here (no app context yet at handleLoadPackage), so
     * we install with default UUIDs; the BLE hooks are inert in a non-BLE app anyway, and real
     * config is loaded once [registerReceiver] gives us the app context.
     */
    fun maybeInstall(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true

        val thread = HandlerThread("ringvibe-ble").apply { start() }
        bgHandler = Handler(thread.looper)

        hookGattCapture()
        hookAppContext()
        XLog.i("RingConn hooks installed in ${lpparam.packageName} (write $serviceUuid / $charUuid)")
    }

    // --- Capture the live connection --------------------------------------------------------------

    private fun hookGattCapture() {
        val gattClass = android.bluetooth.BluetoothGatt::class.java
        val capture = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    tryCapture(param.thisObject, param.args.getOrNull(0))
                    if (param.method.name == "writeCharacteristic") logOutgoing(param)
                } catch (_: Throwable) { /* capture is best-effort */ }
            }
        }
        // Any of these calls, made by flutter_blue_plus during connect/keepalive, hands us the gatt.
        for (m in listOf(
            "writeCharacteristic",
            "readCharacteristic",
            "setCharacteristicNotification",
            "writeDescriptor",
            "discoverServices",
        )) {
            try {
                XposedBridge.hookAllMethods(gattClass, m, capture)
            } catch (t: Throwable) {
                XLog.d("could not hook BluetoothGatt.$m: ${t.message}")
            }
        }

        // Most reliable capture: grab the gatt the moment the app opens the connection, then verify
        // it exposes the ring's service once discovery has had time to finish. This works even when
        // the app is idle in the background and never issues a write.
        try {
            XposedBridge.hookAllMethods(
                android.bluetooth.BluetoothDevice::class.java, "connectGatt",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val gatt = param.result ?: return
                        for (delay in longArrayOf(3000, 6000, 10000)) {
                            bgHandler.postDelayed({ tryCapture(gatt, null) }, delay)
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            XLog.d("could not hook BluetoothDevice.connectGatt: ${t.message}")
        }
    }

    /**
     * Learn mode: log every command the RingConn app itself writes to the ring's write
     * characteristic. Trigger a real vibration in the official app (e.g. finish a manual HR/SpO2/BP
     * measurement) and the exact vibrate command shows up here — no packet sniffing needed. Grep
     * logcat for "[LEARN]".
     */
    private fun logOutgoing(param: XC_MethodHook.MethodHookParam) {
        val ch = param.args.getOrNull(0) ?: return
        val chUuid = runCatching { XposedHelpers.callMethod(ch, "getUuid").toString() }.getOrNull()
        if (!chUuid.equals(charUuid, true)) return
        val bytes = (param.args.getOrNull(1) as? ByteArray)
            ?: runCatching { XposedHelpers.callMethod(ch, "getValue") as? ByteArray }.getOrNull()
            ?: return
        XLog.i("[LEARN] app->ring ${Hex.format(bytes)}")
    }

    /** Decide whether [gatt] is the ring's connection and, if so, remember it. */
    private fun tryCapture(gatt: Any?, firstArg: Any?) {
        if (gatt == null) return
        var isRing = false

        // Fast path: the argument is a characteristic/descriptor on the ring's data service.
        val characteristic = when {
            firstArg == null -> null
            firstArg.javaClass.simpleName == "BluetoothGattDescriptor" ->
                runCatching { XposedHelpers.callMethod(firstArg, "getCharacteristic") }.getOrNull()
            firstArg.javaClass.simpleName == "BluetoothGattCharacteristic" -> firstArg
            else -> null
        }
        if (characteristic != null) {
            val svc = runCatching { XposedHelpers.callMethod(characteristic, "getService") }.getOrNull()
            val svcUuid = svc?.let { XposedHelpers.callMethod(it, "getUuid").toString() }
            val chUuid = runCatching { XposedHelpers.callMethod(characteristic, "getUuid").toString() }.getOrNull()
            if (svcUuid.equals(serviceUuid, true) || chUuid.equals(charUuid, true)) isRing = true
        }

        // Fallback: after service discovery, ask the gatt whether it exposes the ring's service.
        if (!isRing) {
            val svc = runCatching {
                XposedHelpers.callMethod(gatt, "getService", UUID.fromString(serviceUuid))
            }.getOrNull()
            if (svc != null) isRing = true
        }

        if (isRing && gattRef?.get() !== gatt) {
            gattRef = WeakReference(gatt)
            XLog.d("captured ring GATT connection")
        }
    }

    // --- Receiver for the trigger -----------------------------------------------------------------

    private fun hookAppContext() {
        val onApp = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    (param.args.getOrNull(0) as? Application)?.let { registerReceiver(it); return }
                    (param.thisObject as? Application)?.let { registerReceiver(it) }
                } catch (t: Throwable) {
                    XLog.e("register receiver", t)
                }
            }
        }
        // Primary: Application.onCreate (fires when a subclass calls super.onCreate(), as Flutter's
        // FlutterApplication does). Backup: Instrumentation.callApplicationOnCreate, which the
        // framework always invokes even if a subclass overrides onCreate without calling super.
        // registerReceiver is idempotent, so double-firing is harmless.
        XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", onApp)
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", Application::class.java.classLoader,
                "callApplicationOnCreate", Application::class.java, onApp,
            )
        } catch (t: Throwable) {
            XLog.d("Instrumentation.callApplicationOnCreate not hookable: ${t.message}")
        }
    }

    private fun registerReceiver(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Trigger.ACTION_VIBRATE) onTrigger(intent)
            }
        }
        val filter = IntentFilter(Trigger.ACTION_VIBRATE)
        if (Build.VERSION.SDK_INT >= 33) {
            // Sender (system_server) is a different UID, so the receiver must be exported. We don't
            // gate it behind a custom permission because system_server can't hold an app-defined
            // permission, so it couldn't send. Tradeoff: any app could send ACTION_VIBRATE and make
            // the ring buzz — a harmless annoyance, not a data risk.
            ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, filter)
        }
        receiver = r
        XLog.i("trigger receiver registered")
    }

    // --- Play a buzz ------------------------------------------------------------------------------

    private fun onTrigger(intent: Intent) {
        // The command config rides in the broadcast; the sender (system_server / UI) resolved it
        // from the settings. Blank/absent extras fall back to the built-in defaults, so a raw
        // broadcast (or an older sender) still buzzes with the default command.
        serviceUuid = intent.getStringExtra(Trigger.EXTRA_SERVICE_UUID)?.ifBlank { null } ?: Prefs.DEFAULT_WRITE_SERVICE_UUID
        charUuid = intent.getStringExtra(Trigger.EXTRA_CHAR_UUID)?.ifBlank { null } ?: Prefs.DEFAULT_WRITE_CHAR_UUID

        val gatt = gattRef?.get()
        if (gatt == null) {
            XLog.i("trigger received but no live ring GATT yet — is RingConn connected to the ring?")
            return
        }

        val pattern = VibrationPattern.from(intent.getStringExtra(Trigger.EXTRA_PATTERN))
        val vibrateHex = intent.getStringExtra(Trigger.EXTRA_VIBRATE_HEX)?.ifBlank { null } ?: Prefs.DEFAULT_VIBRATE_CMD
        val vibrateBytes = Hex.parse(vibrateHex)
        val useLed = intent.getBooleanExtra(Trigger.EXTRA_USE_LED, false) || vibrateBytes == null
        val reason = intent.getStringExtra(Trigger.EXTRA_REASON)

        bgHandler.post { playPattern(gatt, pattern, useLed, vibrateBytes, reason) }
    }

    private fun playPattern(
        gatt: Any,
        pattern: VibrationPattern,
        useLed: Boolean,
        vibrateBytes: ByteArray?,
        reason: String?,
    ) {
        val ledOn = Hex.parse(Prefs.LED_ON_CMD)!!
        val ledOff = Hex.parse(Prefs.LED_OFF_CMD)!!
        XLog.d("play ${pattern.id} x${pattern.pulses} via ${if (useLed) "LED" else "vibrate"} (reason=$reason)")

        for (i in 0 until pattern.pulses) {
            if (useLed) {
                writeWithRetry(gatt, ledOn)
                sleep(LED_ON_MS)
                writeWithRetry(gatt, ledOff)
            } else {
                writeWithRetry(gatt, vibrateBytes ?: ledOn)
            }
            if (i < pattern.pulses - 1) sleep(pattern.gapMs)
        }
    }

    private fun writeWithRetry(gatt: Any, bytes: ByteArray): Boolean {
        val ch = resolveWriteChar(gatt) ?: run {
            XLog.e("write characteristic $charUuid not found on captured GATT")
            return false
        }
        repeat(MAX_WRITE_RETRIES) { attempt ->
            if (doWrite(gatt, ch, bytes)) {
                XLog.d("wrote ${Hex.format(bytes)}")
                return true
            }
            // Busy: the app has an outstanding GATT op. Back off (growing) and retry.
            sleep(RETRY_DELAY_MS + attempt * 40L)
        }
        XLog.e("write ${Hex.format(bytes)} failed after $MAX_WRITE_RETRIES tries (GATT busy?)")
        return false
    }

    private fun resolveWriteChar(gatt: Any): Any? = runCatching {
        val svc = XposedHelpers.callMethod(gatt, "getService", UUID.fromString(serviceUuid)) ?: return null
        XposedHelpers.callMethod(svc, "getCharacteristic", UUID.fromString(charUuid))
    }.getOrNull()

    private fun doWrite(gatt: Any, ch: Any, bytes: ByteArray): Boolean {
        // Android 13+ API: writeCharacteristic(char, value, writeType) -> int (0 == SUCCESS).
        try {
            val res = XposedHelpers.callMethod(gatt, "writeCharacteristic", ch, bytes, WRITE_TYPE_DEFAULT)
            return (res as? Int) == 0
        } catch (_: Throwable) {
            // Older API: set value on the characteristic, then write.
        }
        return try {
            XposedHelpers.callMethod(ch, "setWriteType", WRITE_TYPE_DEFAULT)
            XposedHelpers.callMethod(ch, "setValue", bytes)
            XposedHelpers.callMethod(gatt, "writeCharacteristic", ch) as? Boolean ?: false
        } catch (t: Throwable) {
            XLog.e("legacy writeCharacteristic failed", t)
            false
        }
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
