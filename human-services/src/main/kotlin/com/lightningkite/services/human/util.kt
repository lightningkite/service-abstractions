package com.lightningkite.services.human

internal const val MAX_MESSAGES = 500

internal const val EMAIL_PANEL_ID = "email"
internal const val SMS_PANEL_ID = "sms"

/** Escapes a string for safe inclusion in JSON output. */
internal fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 0x20) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
        }
    }
    append('"')
}

/** Parses the port from a `human://host:port` URL string. Returns null if not found. */
internal fun parsePort(url: String): Int? {
    // Format: human://host:port  or  human://host
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isBlank()) return null
    val portStr = afterScheme.substringAfter(":", "").substringBefore("/").trim()
    return portStr.toIntOrNull()
}
