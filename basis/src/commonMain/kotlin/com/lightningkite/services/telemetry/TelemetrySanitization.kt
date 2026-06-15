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

    public object None: TelemetrySanitization {
        override fun redactPhoneNumber(phoneNumber: String): String = phoneNumber
        override fun sanitizeUrl(url: String): String = url
        override fun hashCacheKey(key: String): String = key
        override fun sanitizeFilePath(path: String): String = path
        override fun sanitizeFilePathWithDepth(path: String): String = path
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
            val domainAndPath = url.substringAfter("://").substringAfter('@').substringBefore('?')
            return "$schema://$domainAndPath"
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
            return path.substringAfterLast('/')
                .substringAfterLast('\\')
                .ifEmpty { path }
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
        override fun sanitizeFilePathWithDepth(path: String): String {
            // Handle various path separatorsurl.substringAfter("://")
            return path.substringAfterLast('/')
                .substringAfterLast('\\')
                .ifEmpty { path }
        }
    }
}