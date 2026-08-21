package io.github.ringvibe.core

/**
 * A buzz pattern the module realises by sending the ring's single vibrate command one or more
 * times.
 *
 * The RingConn Gen 3 firmware supports short / medium / long native patterns, but the exact
 * command byte that selects them is device-specific and must be discovered (see the capture guide).
 * Until a per-level command is known, the module distinguishes patterns purely by repetition:
 * [pulses] buzzes spaced [gapMs] apart. [nativeLevel] is plumbed through so a command template that
 * encodes duration can use it (the "LL" placeholder in the configured hex, if present).
 */
enum class VibrationPattern(
    val id: String,
    val pulses: Int,
    val gapMs: Long,
    /** 0 = short, 1 = medium, 2 = long — a hint for command templates that encode duration. */
    val nativeLevel: Int,
) {
    SHORT("short", pulses = 1, gapMs = 0, nativeLevel = 0),
    MEDIUM("medium", pulses = 1, gapMs = 0, nativeLevel = 1),
    LONG("long", pulses = 1, gapMs = 0, nativeLevel = 2),
    DOUBLE("double", pulses = 2, gapMs = 220, nativeLevel = 0),
    TRIPLE("triple", pulses = 3, gapMs = 220, nativeLevel = 0);

    companion object {
        fun from(id: String?): VibrationPattern =
            entries.firstOrNull { it.id == id } ?: SHORT
    }
}
