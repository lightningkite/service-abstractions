package com.lightningkite.services.otel

import java.security.MessageDigest

/**
 * Utilities for sanitizing sensitive data before logging to OpenTelemetry spans.
 *
 * These functions help prevent PII (Personally Identifiable Information) and secrets
 * from being exposed in telemetry backends.
 */
public object TelemetrySanitization {

    /**
     * Redacts a phone number to show only country code and last 4 digits.
     *
     * Examples:
     * - `+15551234567` → `+1***4567`
     * - `+447123456789` → `+44***6789`
     *
     * @param phoneNumber The phone number to redact
     * @return Redacted phone number safe for telemetry
     */
    public fun redactPhoneNumber(phoneNumber: String): String {
        // Handle E.164 format: +[country code][number]
        if (phoneNumber.startsWith("+") && phoneNumber.length > 6) {
            val countryCode = phoneNumber.takeWhile { it.isDigit() || it == '+' }.take(3)
            val lastFour = phoneNumber.takeLast(4)
            return "$countryCode***$lastFour"
        }
        // For non-standard formats, redact everything except last 4
        return if (phoneNumber.length > 4) {
            "***${phoneNumber.takeLast(4)}"
        } else {
            "***"
        }
    }

    /**
     * Sanitizes a URL by removing query parameters and credentials.
     *
     * Examples:
     * - `https://api.example.com/path?token=secret` → `https://api.example.com/path`
     * - `https://user:pass@example.com/path` → `https://example.com/path`
     *
     * @param url The URL to sanitize
     * @return Sanitized URL safe for telemetry
     */
    public fun sanitizeUrl(url: String): String {
        return try {
            val urlObj = java.net.URL(url)
            val protocol = urlObj.protocol
            val host = urlObj.host
            val port = if (urlObj.port != -1 && urlObj.port != urlObj.defaultPort) ":${urlObj.port}" else ""
            val path = urlObj.path.ifEmpty { "/" }

            "$protocol://$host$port$path"
        } catch (e: Exception) {
            // If URL parsing fails, just remove query params the simple way
            url.substringBefore('?')
        }
    }

    /**
     * Creates a safe representation of a cache key by hashing it.
     *
     * This produces a consistent identifier that can be used for debugging
     * without exposing the actual key value.
     *
     * Examples:
     * - `user:12345:session` → `sha256:a3b2c1...` (truncated hash)
     *
     * @param key The cache key to hash
     * @return SHA-256 hash prefix of the key
     */
    public fun hashCacheKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(key.toByteArray())
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }
        return "sha256:${hashHex.take(16)}..."
    }

    /**
     * Sanitizes a file path to only show the filename, not the full path.
     *
     * This prevents exposure of directory structure which may contain
     * sensitive information (usernames, customer IDs, etc.)
     *
     * Examples:
     * - `/users/john.doe/documents/secret.pdf` → `secret.pdf`
     * - `s3://bucket/customer-123/data.json` → `data.json`
     *
     * @param path The file path to sanitize
     * @return Just the filename without directory information
     */
    public fun sanitizeFilePath(path: String): String {
        // Handle various path separators
        return path.substringAfterLast('/')
            .substringAfterLast('\\')
            .ifEmpty { path }
    }

    /**
     * Alternative approach: shows directory depth and filename only.
     *
     * Examples:
     * - `/users/john.doe/documents/secret.pdf` → `[depth:3]/secret.pdf`
     * - `s3://bucket/customer-123/data.json` → `[depth:2]/data.json`
     *
     * @param path The file path to sanitize
     * @return Depth indicator and filename
     */
    public fun sanitizeFilePathWithDepth(path: String): String {
        val cleanPath = path.replace('\\', '/')
        val parts = cleanPath.split('/').filter { it.isNotEmpty() }
        val filename = parts.lastOrNull() ?: path
        val depth = (parts.size - 1).coerceAtLeast(0)
        return "[depth:$depth]/$filename"
    }
}
