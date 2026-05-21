package com.lightningkite.services.email.mailgun

import com.lightningkite.services.TestSettingContext
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sign + verify round-trip tests for Mailgun's HMAC-SHA256 webhook signature scheme.
 *
 * Mailgun signs as: HMAC-SHA256(key = apiKey, data = "${timestamp}${token}") and hex-encodes
 * the digest. The 1.0.0 hardening pass switched to constant-time byte compare via the lenient
 * `hexDecode` so that malformed-hex and signature-mismatch errors are reported identically —
 * no information leak about which check failed.
 */
class MailgunVerifySignatureTest {

    private val apiKey = "key-test-mailgun-api-key-1234567890"

    private val service = MailgunEmailInboundService(
        name = "test",
        context = TestSettingContext(),
        apiKey = apiKey,
        domain = "example.com",
    )

    /** Computes the legitimate Mailgun signature for the given timestamp + token. */
    private fun computeSignature(timestamp: String, token: String, key: String = apiKey): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp$token".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun nowEpoch(): String = (System.currentTimeMillis() / 1000).toString()

    @Test
    fun validSignatureRoundTripPasses() {
        val ts = nowEpoch()
        val token = "test-token-1"
        val sig = computeSignature(ts, token)
        // Should not throw
        service.verifySignature(
            mapOf(
                "timestamp" to listOf(ts),
                "token" to listOf(token),
                "signature" to listOf(sig),
            ),
            apiKey,
        )
    }

    @Test
    fun tamperedSignatureThrowsInvalid() {
        val ts = nowEpoch()
        val token = "test-token-2"
        val realSig = computeSignature(ts, token)

        // Flip one hex char to a different hex digit so the result is still valid hex
        // (same length, all hex chars) but a wrong digest.
        val tampered = (if (realSig[0] == '0') '1' else '0') + realSig.substring(1)
        check(tampered != realSig) { "Test bug: tampered signature equals real signature" }

        val e = assertFailsWith<SecurityException> {
            service.verifySignature(
                mapOf(
                    "timestamp" to listOf(ts),
                    "token" to listOf(token),
                    "signature" to listOf(tampered),
                ),
                apiKey,
            )
        }
        assertEquals("Invalid Mailgun webhook signature", e.message)
    }

    @Test
    fun missingTimestampThrowsMissingFields() {
        val token = "tok"
        val sig = computeSignature(nowEpoch(), token)
        val e = assertFailsWith<SecurityException> {
            service.verifySignature(
                mapOf("token" to listOf(token), "signature" to listOf(sig)),
                apiKey,
            )
        }
        assertEquals("Missing signature fields in Mailgun webhook", e.message)
    }

    @Test
    fun missingTokenThrowsMissingFields() {
        val ts = nowEpoch()
        val sig = computeSignature(ts, "tok")
        val e = assertFailsWith<SecurityException> {
            service.verifySignature(
                mapOf("timestamp" to listOf(ts), "signature" to listOf(sig)),
                apiKey,
            )
        }
        assertEquals("Missing signature fields in Mailgun webhook", e.message)
    }

    @Test
    fun missingSignatureThrowsMissingFields() {
        val ts = nowEpoch()
        val e = assertFailsWith<SecurityException> {
            service.verifySignature(
                mapOf("timestamp" to listOf(ts), "token" to listOf("tok")),
                apiKey,
            )
        }
        assertEquals("Missing signature fields in Mailgun webhook", e.message)
    }

    @Test
    fun timestampOlderThan15MinutesThrowsWithTimestampMessage() {
        val staleTs = ((System.currentTimeMillis() - 16 * 60 * 1000) / 1000).toString()
        val token = "stale-token"
        val sig = computeSignature(staleTs, token)
        val e = assertFailsWith<SecurityException> {
            service.verifySignature(
                mapOf(
                    "timestamp" to listOf(staleTs),
                    "token" to listOf(token),
                    "signature" to listOf(sig),
                ),
                apiKey,
            )
        }
        assertTrue(
            e.message?.contains("timestamp", ignoreCase = true) == true,
            "Expected timestamp-related message, got: ${e.message}",
        )
    }

    @Test
    fun malformedHexOddLengthThrowsGenericInvalidSignature() {
        val e = assertFailsWith<IllegalArgumentException> {
            service.verifySignature(
                mapOf(
                    "timestamp" to listOf(nowEpoch()),
                    "token" to listOf("tok"),
                    "signature" to listOf("abc"),
                ),
                apiKey,
            )
        }
        assertEquals("Invalid Mailgun webhook signature - not proper hex", e.message)
    }

    @Test
    fun malformedHexNonHexCharsThrowsGenericInvalidSignature() {
        val e = assertFailsWith<IllegalArgumentException> {
            service.verifySignature(
                mapOf(
                    "timestamp" to listOf(nowEpoch()),
                    "token" to listOf("tok"),
                    "signature" to listOf("zzzz"),
                ),
                apiKey,
            )
        }
        assertEquals("Invalid Mailgun webhook signature - not proper hex", e.message)
    }
}
