package io.github.ringvibe.core

/** Parsing/formatting for the space- or colon-separated hex the user pastes for the vibrate command. */
object Hex {

    /**
     * Parse a loose hex string ("03 01 01", "0x03,0x01", "030101") into bytes.
     * Returns null if the cleaned string has odd length or contains non-hex characters.
     */
    fun parse(input: String?): ByteArray? {
        if (input.isNullOrBlank()) return null
        val cleaned = input
            .replace("0x", "", ignoreCase = true)
            .replace("0X", "")
            .filter { !it.isWhitespace() && it != ':' && it != ',' && it != '-' }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun format(bytes: ByteArray?): String =
        bytes?.joinToString(" ") { "%02X".format(it) } ?: ""
}
