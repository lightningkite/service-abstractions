package com.lightningkite.services.files.s3

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for FIX 38: [S3ExternalFileSystem.DirectAwsCredentials] is a public data class, so
 * without an explicit override its compiler-generated `toString()` would print the raw AWS secret
 * access key (and session token) in any log line, exception message, or debugger view.
 */
class DirectAwsCredentialsToStringTest {
    @Test
    fun toStringRedactsSecretAndToken() {
        val creds = S3ExternalFileSystem.DirectAwsCredentials(
            access = "AKIAEXAMPLE",
            secret = "super-secret-value",
            token = "session-token-value",
        )

        val rendered = creds.toString()

        assertFalse(rendered.contains("super-secret-value"), "toString() must not contain the raw secret")
        assertFalse(rendered.contains("session-token-value"), "toString() must not contain the raw session token")
        // The access key id is not sensitive on its own and is useful for debugging - it should stay visible.
        assertTrue(rendered.contains("AKIAEXAMPLE"))
    }

    @Test
    fun toStringHandlesNullToken() {
        val creds = S3ExternalFileSystem.DirectAwsCredentials(access = "AKIAEXAMPLE", secret = "super-secret-value")

        assertFalse(creds.toString().contains("super-secret-value"))
    }
}
