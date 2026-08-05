package com.lightningkite.services.telemetry

/**
 * Utilities for sanitizing sensitive data before logging to OpenTelemetry spans.
 *
 * These functions help prevent PII (Personally Identifiable Information) and secrets
 * from being exposed in telemetry backends.
 */
public interface TelemetrySanitization {
    public fun redactPhoneNumber(phoneNumber: String): String
    public fun sanitizeUrl(url: String): String
    public fun hashCacheKey(key: String): String
    public fun sanitizeFilePath(path: String): String
    public fun sanitizeFilePathWithDepth(path: String): String

    /**
     * Scrubs credential-bearing connection URLs (e.g. `mongodb://user:pass@host`) that may be embedded
     * anywhere within free-form text, such as an exception message or stack trace. Unlike [sanitizeUrl],
     * the input is not assumed to be a URL by itself.
     */
    public fun sanitizeExceptionMessage(message: String): String

    public object None: TelemetrySanitization {
        override fun redactPhoneNumber(phoneNumber: String): String = phoneNumber
        override fun sanitizeUrl(url: String): String = url
        override fun hashCacheKey(key: String): String = key
        override fun sanitizeFilePath(path: String): String = path
        override fun sanitizeFilePathWithDepth(path: String): String = path
        override fun sanitizeExceptionMessage(message: String): String = message
    }
    public object Strict: TelemetrySanitization {

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
        override fun redactPhoneNumber(phoneNumber: String): String {
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
        override fun sanitizeUrl(url: String): String {
            val schema = url.substringBefore("://")
            val afterScheme = url.substringAfter("://").substringBefore('?')
            val authorityEnd = afterScheme.indexOf('/').let { if (it == -1) afterScheme.length else it }
            val authority = afterScheme.substring(0, authorityEnd)
            val pathPart = afterScheme.substring(authorityEnd)
            // The userinfo (user:pass) section is separated from the host by the LAST '@' in the
            // authority, not the first: a password may legally contain a literal '@', and splitting on
            // the first occurrence leaks the tail of the password into the "host" portion below.
            val host = authority.substringAfterLast('@')
            return "$schema://$host$pathPart"
        }

        override fun hashCacheKey(key: String): String {
            return key.split(':').joinToString(":") { part ->
                if (part.isEmpty()) part else part.take(1) + "-".repeat(part.length - 1)
            }
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
        override fun sanitizeFilePath(path: String): String {
            // Handle various path separators
            val fileName = path.substringAfterLast('/').substringAfterLast('\\')
            // Directory-style input (trailing separator) yields an empty filename here. Falling back to
            // the original `path` (the old behavior) would return the full unredacted path for exactly
            // the inputs this function exists to protect, so fall back to a fixed sentinel instead.
            return fileName.ifEmpty { "(root)" }
        }

        /**
         * Alias for [sanitizeFilePath]. There is no depth parameter on this function, so no
         * depth-aware behavior can be implemented without an API change to callers outside this fix's
         * scope (files/files-s3). Kept as a distinct method to avoid a breaking rename, but delegates
         * to [sanitizeFilePath] so the two can't drift out of sync the way they previously did (both
         * implementations shared the same unredacted-fallback bug).
         */
        override fun sanitizeFilePathWithDepth(path: String): String = sanitizeFilePath(path)

        // Matches a scheme + "://" + the following run of non-whitespace characters, i.e. a URL-shaped
        // token embedded anywhere in free text (an exception message, a stack trace line, ...).
        private val embeddedUrlPattern = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://\S+""")

        override fun sanitizeExceptionMessage(message: String): String =
            embeddedUrlPattern.replace(message) { sanitizeUrl(it.value) }
    }
}