package com.lightningkite.services.telemetry

import kotlin.test.*

/**
 * [TelemetrySanitization.Strict] is the last line of defense between application internals (connection
 * URLs, file paths, exception messages) and third-party telemetry vendors. These tests target the holes
 * found in a release review: a directory-style path that re-exposed itself via the `ifEmpty` fallback, a
 * URL splitter that leaked the tail of a password containing a literal `@`, and exception messages that
 * bypassed sanitization entirely.
 */
class TelemetrySanitizationTest {

    // ── sanitizeFilePath / sanitizeFilePathWithDepth ─────────────────────────

    @Test
    fun sanitizeFilePath_directoryStylePath_doesNotLeakOriginal() {
        val path = "/users/john.doe/documents/"
        val result = TelemetrySanitization.Strict.sanitizeFilePath(path)
        // The old `.ifEmpty { path }` fallback returned the untouched input for any trailing-separator
        // path, defeating the redaction. The result must never equal the original sensitive path.
        assertNotEquals(path, result)
        assertFalse(result.contains("john.doe"))
    }

    @Test
    fun sanitizeFilePath_windowsDirectoryStylePath_doesNotLeakOriginal() {
        val path = "C:\\Users\\john.doe\\documents\\"
        val result = TelemetrySanitization.Strict.sanitizeFilePath(path)
        assertNotEquals(path, result)
        assertFalse(result.contains("john.doe"))
    }

    @Test
    fun sanitizeFilePath_normalFile_returnsFilenameOnly() {
        assertEquals("secret.pdf", TelemetrySanitization.Strict.sanitizeFilePath("/users/john.doe/documents/secret.pdf"))
    }

    // ── sanitizeUrl ────────────────────────────────────────────────────────

    @Test
    fun sanitizeUrl_passwordContainingAtSign_fullyRedacted() {
        // A password containing a literal '@' is legal and common in generated credentials. Splitting on
        // the FIRST '@' (the old implementation) leaks everything after that first '@' up to the real
        // userinfo/host boundary.
        val url = "https://user:p@ssword@example.com/path"
        val result = TelemetrySanitization.Strict.sanitizeUrl(url)
        assertFalse(result.contains("user"))
        assertFalse(result.contains("ssword"))
        assertEquals("https://example.com/path", result)
    }

    @Test
    fun sanitizeUrl_passwordContainingColon_fullyRedacted() {
        val url = "redis://user:pa:ss@host.internal:6379"
        val result = TelemetrySanitization.Strict.sanitizeUrl(url)
        assertFalse(result.contains("user"))
        assertFalse(result.contains("pa"))
        assertFalse(result.contains("ss"))
        assertEquals("redis://host.internal:6379", result)
    }

    @Test
    fun sanitizeUrl_noCredentials_unaffected() {
        assertEquals(
            "https://api.example.com/path",
            TelemetrySanitization.Strict.sanitizeUrl("https://api.example.com/path?token=secret"),
        )
    }

    // ── sanitizeExceptionMessage ──────────────────────────────────────────

    @Test
    fun sanitizeExceptionMessage_embeddedConnectionUrl_redacted() {
        val message = "Failed to connect to mongodb://admin:S3cr3t@db.internal:27017/mydb after 3 retries"
        val result = TelemetrySanitization.Strict.sanitizeExceptionMessage(message)
        assertFalse(result.contains("admin"))
        assertFalse(result.contains("S3cr3t"))
        assertTrue(result.contains("db.internal:27017/mydb"))
        assertTrue(result.contains("Failed to connect to"))
        assertTrue(result.contains("after 3 retries"))
    }

    @Test
    fun sanitizeExceptionMessage_plainMessage_unaffected() {
        assertEquals("connection refused", TelemetrySanitization.Strict.sanitizeExceptionMessage("connection refused"))
    }

    @Test
    fun none_sanitizeExceptionMessage_isPassthrough() {
        val message = "mongodb://admin:S3cr3t@db.internal/mydb"
        assertEquals(message, TelemetrySanitization.None.sanitizeExceptionMessage(message))
    }
}
