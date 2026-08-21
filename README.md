# RingVibe

An **LSPosed / Xposed module** that buzzes a **RingConn Gen 3** smart ring on **phone notifications**
and **incoming calls** — something the official app deliberately does not do.

## Why this exists

The RingConn Gen 3 is the first RingConn with a vibration motor. But the official app only buzzes it
for *health* events (battery, sedentary, wellness reminders, and measurement completions). Calls,
texts, and app notifications are **explicitly not** delivered to the ring. RingVibe fills that gap.

It does **not** open its own Bluetooth connection. Instead it rides the connection the official
RingConn app already holds — which is the only practical way, because the ring drops commands from
any central that isn't LE-bonded and hasn't passed the ring's per-connection challenge. The app has
already cleared both; the module reuses that. See [docs/PROTOCOL.md](docs/PROTOCOL.md).

## The vibrate command

The Gen 3 vibrate command has been **captured and confirmed on-device**:

```
0B 03 01 64 00     (opcode 0x0B, sub 0x03, payload 01 64 = on + intensity/duration 0x64)
```

It's baked in as the default, so RingVibe buzzes out of the box. It was recovered with the module's
own **learn mode** (which logs the commands the official app writes to the ring) at the moment the
app buzzed the ring on a manual heart-rate measurement — no external packet sniffer needed. If a
future firmware changes it, re-capture with **[docs/CAPTURE-VIBRATE-COMMAND.md](docs/CAPTURE-VIBRATE-COMMAND.md)**
(or grep logcat for `[LEARN]` while triggering a measurement) and paste the new bytes into settings.

If you ever blank the command, RingVibe falls back to the device-verified Find-My-Ring LED
(`24 01 00`), which *blinks* the ring instead of buzzing — a useful way to prove the pipeline.

## Gotcha: the RingConn app must NOT be on the Magisk DenyList

If you use Magisk DenyList with Zygisk enforce, **remove `com.gdjztech.ringconn` from it** — a
denylisted process gets Zygisk (and therefore LSPosed) unloaded, so the module can't inject into the
Bluetooth process and nothing happens. If the app then misbehaves with root visible, install Shamiko
and keep it on the *hide* list instead (that hides root while still injecting).

## How it works

```
 ┌───────────────── system_server (scope: "android") ─────────────────┐
 │  hook NotificationManagerService.enqueueNotificationInternal        │
 │  hook TelephonyRegistry.notifyCallState* (RINGING)                  │
 │      → filter (app allow/block, ongoing, low-importance, screen,    │
 │        cooldown) → explicit-package broadcast                       │
 └───────────────────────────────┬────────────────────────────────────┘
                                  │  Intent(ACTION_VIBRATE) setPackage(RingConn)
                                  ▼
 ┌──────────── RingConn app process (com.gdjztech.ringconn) ───────────┐
 │  hook android.bluetooth.BluetoothGatt.* → capture the live GATT     │
 │  BroadcastReceiver → write [cmd] to char 8327ad98-… on that GATT    │
 │      (vibrate command, or LED fallback), retrying if the bus is busy │
 └─────────────────────────────────────────────────────────────────────┘
```

The RingConn app is a **Flutter** app, so its command logic is compiled into native `libapp.so` and
cannot be hooked directly. The `BluetoothGatt` write, however, is ordinary Android Java — that's the
seam the module uses to both *find* the live connection and *inject* onto it.

## Requirements

- A rooted phone with **LSPosed** (Zygisk/Magisk or KernelSU).
- The official **RingConn** app installed (`com.gdjztech.ringconn`) and connected to a **Gen 3** ring.
- Android 9+ (built against SDK 35; verified to load on newer via name-based hooks).

## Build

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

(JDK 17 or 21 works; the repo pins Gradle 8.11.1 + AGP 8.7.3.)

## Install & activate

```bash
tools/install.sh          # adb install the APK
```

Then:

1. **LSPosed → Modules → RingVibe → enable.**
2. Set its **scope**: ✔ *System Framework* (`android`), ✔ *RingConn* (`com.gdjztech.ringconn`),
   ✔ *RingVibe* itself (only so the settings screen can show "active").
3. **Reboot.**
4. Open **RingVibe**, make sure the RingConn app is running and connected, and tap **Send test buzz
   now**. With no captured command yet, the ring's LED should blink.

## Configure

Everything is in the RingVibe settings screen:

- **Triggers** — calls on/off, notifications on/off.
- **Vibration** — pattern per trigger (single/double/triple), and how many times to re-buzz while
  ringing.
- **Notification filtering** — ignore ongoing/foreground-service notifications, low-importance ones,
  and group summaries; app allow-list / block-list; "only when screen off"; a cooldown between buzzes.
- **RingConn integration** — the app package and the write service/characteristic UUIDs (pre-filled
  with the known values), the **vibrate command hex** you captured, and whether to send *Vibrate* or
  the *LED* blink.
- **Test & debug** — send a test buzz; verbose logging (logcat tag `RingVibe`, also in the LSPosed log).

## Known limitations & caveats

- **The vibrate opcode is yours to capture** (above). Everything else is pre-wired.
- **Depends on the RingConn app running and connected.** The module injects on *its* live link; if
  the app is force-stopped or the ring is disconnected, there's nothing to write to. Enable the
  RingConn app's *Persistent Mode* and set its battery usage to *Unrestricted*.
- **Shared GATT queue.** The module and the app write on the same connection. Android allows one
  outstanding GATT op at a time, so the module retries when the bus is busy, and there's a small
  chance an injected write's completion callback briefly confuses the app's own BLE bookkeeping. Keep
  the cooldown reasonable; buzzes are rare and tiny.
- **Anti-tamper.** Apps like this sometimes ship root/hook detection. If the RingConn app starts
  misbehaving, use LSPosed's hiding options, or expect to adapt.
- **Android version drift.** Detection hooks target framework internals by method *name* to survive
  signature changes, but a major framework refactor could still require an update.
- **This is a hack on a proprietary app.** It's for your own device and your own ring. Firmware or
  app updates can change behavior.

## Project layout

```
app/src/main/java/io/github/ringvibe/
  core/       Prefs, Trigger (IPC contract), VibrationPattern, Hex, StatusProbe,
              ConfigCodec + BundlePrefs (config <-> Bundle), BootReceiver          (shared, dependency-free)
  hook/       ModuleEntry (dispatch), SystemHooks (detection), RingConnHooks (inject), HookConfig, XLog
  ui/         SettingsActivity + SettingsFragment (configuration screen)
docs/         PROTOCOL.md, CAPTURE-VIBRATE-COMMAND.md
tools/        ring-mac.sh, pull-hci.sh, decode-writes.sh, install.sh
```

### How settings reach the hooks

The hooks run in system_server and the RingConn process; neither can read the module's private
prefs directly (world-readable prefs are unreliable on some LSPosed forks, and system_server
shouldn't block on a third-party ContentProvider). So the flow is **push, not pull**: the settings
UI writes normal private prefs and broadcasts the whole config as a `Bundle` to the system_server
hook (which caches it); a boot receiver re-pushes it after a reboot. For each buzz, system_server
resolves the command from that config and hands it to the RingConn process **inside the broadcast**,
so the RingConn hook never needs to read settings at all. Everything works on built-in defaults until
you configure something.

## Credits

RingConn BLE reverse-engineering: [perezjuanj/OpenCircuit](https://github.com/perezjuanj/OpenCircuit).
Xposed API by rovo89; LSPosed by the LSPosed team.
