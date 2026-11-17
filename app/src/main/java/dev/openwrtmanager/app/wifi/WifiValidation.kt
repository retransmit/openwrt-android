package dev.openwrtmanager.app.wifi

enum class SsidError { EMPTY, TOO_LONG, INVALID_CHARACTER }

enum class KeyError { TOO_SHORT, TOO_LONG, NOT_ASCII }

/** A network name is 1 to 32 bytes of UTF-8, so characters outside ASCII count for more than one. */
fun validateSsid(ssid: String): SsidError? = when {
    ssid.isEmpty() -> SsidError.EMPTY
    // Line breaks and other control characters cannot be stored in the config, and no device could type them.
    ssid.any { it.code < 0x20 || it.code == 0x7f } -> SsidError.INVALID_CHARACTER
    ssid.toByteArray(Charsets.UTF_8).size > 32 -> SsidError.TOO_LONG
    else -> null
}

/**
 * A WPA passphrase is 8 to 63 printable ASCII characters, or the raw key as exactly 64 hex digits.
 * Open networks have none.
 */
fun validateKey(key: String, security: Security): KeyError? = when {
    security == Security.OPEN -> null
    key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> null
    key.any { it.code !in 0x20..0x7e } -> KeyError.NOT_ASCII
    key.length < 8 -> KeyError.TOO_SHORT
    key.length > 63 -> KeyError.TOO_LONG
    else -> null
}
