# RingConn BLE transport (what RingVibe needs to know)

This is the subset of the RingConn Bluetooth protocol the module relies on. It is stable across
Gen 2 and Gen 3 (Gen 3 decodes byte-for-byte identically). Full credit and much deeper detail:
[perezjuanj/OpenCircuit](https://github.com/perezjuanj/OpenCircuit) (formerly OpenRingConn).

## GATT layout

| Role | UUID | Value handle | Properties |
|------|------|--------------|------------|
| Primary data **service** | `8327ad99-2d87-4a22-a8ce-6dd7971c0437` | 0x0800 | — |
| **Write** (commands host→ring) | `8327ad98-2d87-4a22-a8ce-6dd7971c0437` | **0x0802** | write (with response) |
| **Notify** (responses ring→host) | `8327ad97-2d87-4a22-a8ce-6dd7971c0437` | 0x0804 | notify (CCCD 0x0805, enable with `01 00`) |

Everything the ring does goes through this one service's write/notify pair — not per-metric
characteristics. RingVibe writes to the **write** characteristic above.

## Framing

- **Command (host → ring):** `[cmd][sub][payload…][0x00]` — sent **verbatim**, **no checksum**.
  The trailing byte is a literal `0x00`, *not* an XOR. Written **with response**.
- **Response (ring → host):** `[respid][payload…][xor]`, where `respid = cmd XOR 0x80` and the last
  byte is an XOR of all preceding bytes.

## Commands relevant here

| Meaning | Bytes | Notes |
|---------|-------|-------|
| **Vibrate (Gen 3)** | `0B 03 01 64 00` | 🟢 captured & device-verified 2026-08-21. opcode `0x0B`, sub `0x03`, payload `01 64` (on + intensity/duration `0x64`). One short buzz. |
| Find-My-Ring **LED on** | `24 01 00` | 🟢 device-verified. Lights the ring blue ~120 s. Reply `a4 00 a4`. |
| Find-My-Ring **LED off** | `24 00 00` | Turns it back off. |

The vibrate command was not in any public capture (Gen 2 has no motor); it was recovered by logging
the official app's own writes to `0x0802` with the module's learn mode while the app buzzed the ring
on a manual measurement — see [CAPTURE-VIBRATE-COMMAND.md](CAPTURE-VIBRATE-COMMAND.md).

The LED command is RingVibe's built-in fallback: it proves the whole detect → inject pipeline works
on a ring that has no captured vibrate command yet. It blinks; it does not buzz.

## Why the module hooks the app instead of connecting itself

Two gates stop an *independent* Bluetooth connection from driving the ring:

1. **LE bond** — the ring silently drops every data command from a central it isn't bonded with.
2. **Per-connection SM3 challenge/response** (current firmware) computed from the ring's own MAC.

The official RingConn app has already cleared both on its live connection. By writing on **that**
connection (captured via `BluetoothGatt` hooks) the module inherits the bond and the auth for free —
no re-pairing, no re-implementing SM3. It also avoids the single-connection limit (the ring accepts
only one central at a time, so an independent connection would fight the app for it).
