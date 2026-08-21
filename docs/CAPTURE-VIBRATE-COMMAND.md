# Capturing the Gen 3 vibrate command

RingVibe knows how to *write* to the ring, but the exact bytes that make a **Gen 3** buzz are not
publicly documented — Gen 2 had no motor, so the command appears in no existing capture. You capture
it once, on your own ring, then paste it into the module. Until then the module falls back to the
Find-My-Ring **LED** so you can confirm everything else works.

There are two paths. Path A (packet capture) is the reliable one.

---

## Path A — sniff it with an HCI snoop log

### 1. Turn on Bluetooth HCI logging (once)

On the phone: **Settings → System → Developer options → Enable Bluetooth HCI snoop log → Enabled
(Full)**. Then toggle **Bluetooth off and on** so a clean log starts.

### 2. Note the ring's MAC (a filter, optional but handy)

```bash
tools/ring-mac.sh
```

### 3. Trigger exactly one vibration, in isolation

Leave the phone idle ~30 s, then make the ring buzz **once** through the official app, and note the
time. On Gen 3 the app buzzes on:

- completion of a **manual HR / SpO₂ / Blood-Pressure** measurement (cleanest — you control when),
- a **Sedentary** or **Battery** reminder,
- manual **exercise** start countdown.

Wait ~30 s again so the command stands alone in the log.

### 4. Pull the log

```bash
tools/pull-hci.sh          # non-rooted, via adb bugreport
# or, on a rooted phone:
tools/pull-hci.sh --root
```

Saves `captures/btsnoop_hci.log`.

### 5. Read the command writes

```bash
tools/decode-writes.sh captures/btsnoop_hci.log
# add --mac <RING_MAC> to restrict to the ring
```

This lists every phone→ring write to the command handle `0x0802` with a timestamp and hex value.
Find the write whose time matches your buzz. It will look like `[cmd][sub]…00`. That hex string is
your vibrate command.

> Sanity check your pipeline: the Find-My-Ring LED command `24 01 00` should also show up on `0x0802`
> whenever you use the app's "Find my ring" feature.

### 6. Plug it in

RingVibe → **RingConn integration → Vibrate command (hex)** → paste the bytes (e.g. `03 02 01 00`).
Set **What to send to the ring** to **Vibrate**. Tap **Send test buzz now**.

---

## Path B — decompile the app (fallback, harder)

The RingConn app is **Flutter**, so the protocol logic is in native `lib/arm64-v8a/libapp.so`, not
in the Java `classes.dex` — **jadx/apktool are useless here.**

```bash
adb shell pm path com.gdjztech.ringconn          # find the split APKs
adb pull <base.apk> ; adb pull <split_config.arm64_v8a.apk>
# then run https://github.com/worawit/blutter on the extracted lib/arm64-v8a
strings libapp.so | grep -iE 'vibrat|haptic|motor|buzz|remind|sedentary|wellness|key.*(vibrat|buzz|remind)'
```

Dart method/key names survive as strings (e.g. `keyStartSearchLight`). Find the vibrate handler and
read the `[cmd][sub][payload]` it writes to `8327ad98-…`. If `blutter` can't parse the app's Dart
version, reverse an **older** app version — the wire protocol is stable across releases.

---

## If capture shows no "buzz now" write

It's possible Gen 3 vibration for *reminders* is computed on-ring from pushed config rather than a
real-time command. If so, the reminder buzz won't have a replayable write — but an **immediate**
actuator command (like Find-My-Ring) still does. In that case:

- keep **What to send** on **LED** for a real ring-side signal today, and/or
- check whether Gen 3 **Find My Ring** buzzes (not just lights); if it does, capture *that* write —
  it's the cleanest on-demand buzz primitive.

Please open an issue with your captured bytes so the default can be filled in for everyone.
