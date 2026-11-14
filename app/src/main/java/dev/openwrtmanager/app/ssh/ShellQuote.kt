package dev.openwrtmanager.app.ssh

/** POSIX single-quote escaping: the result is always one literal shell word. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\''") + "'"
