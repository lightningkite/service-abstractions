package com.lightningkite.services.email.mailgun

import com.lightningkite.services.TestSettingContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Tests for the lenient-rejecting hex decoder used in Mailgun webhook signature verification.
 *
 * The 1.0.0 performance/security pass replaced `String.equals(ignoreCase=true)` on hex digests
 * with hex-decode + `MessageDigest.isEqual` constant-time byte compare. The decoder must reject
 * malformed input by returning null — never throw — so the caller can short-circuit with the
 * same generic `SecurityException("Invalid Mailgun webhook signature")` and avoid leaking
 * which specific check failed.
 */
class MailgunHexDecodeTest {

    private val service = MailgunEmailInboundService(
        name = "test",
        context = TestSettingContext(),
        apiKey = "test-key",
        domain = "example.com",
    )

    @Test
    fun decodesLowerCaseHex() {
        assertContentEquals(byteArrayOf(0x00, 0x12, 0x34.toByte(), 0xab.toByte()), service.hexDecode("001234ab"))
    }

    @Test
    fun decodesUpperCaseHex() {
        assertContentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte()), service.hexDecode("CAFE"))
    }

    @Test
    fun decodesMixedCaseHex() {
        assertContentEquals(byteArrayOf(0xCa.toByte(), 0xfE.toByte()), service.hexDecode("cAfE"))
    }

    @Test
    fun emptyStringDecodesToEmptyArray() {
        assertContentEquals(byteArrayOf(), service.hexDecode(""))
    }

    @Test
    fun oddLengthReturnsNull() {
        assertNull(service.hexDecode("abc"))
        assertNull(service.hexDecode("a"))
    }

    @Test
    fun nonHexCharactersReturnNull() {
        assertNull(service.hexDecode("zz"))
        assertNull(service.hexDecode("xyzw"))
        assertNull(service.hexDecode("0g"))
        assertNull(service.hexDecode("g0"))
    }

    @Test
    fun whitespaceReturnsNull() {
        // Signatures must be tight hex — no leading/trailing/internal whitespace.
        assertNull(service.hexDecode(" 00"))
        assertNull(service.hexDecode("00 "))
        assertNull(service.hexDecode("0 0"))
    }

    @Test
    fun realisticMailgunSignatureLength() {
        // Mailgun signs with HmacSHA256 → 32 bytes → 64 lowercase hex chars.
        val hex = "0123456789abcdef".repeat(4)
        val decoded = service.hexDecode(hex)
        assertContentEquals(ByteArray(32) { i ->
            // Decoded pattern is 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF repeating
            when (i % 8) {
                0 -> 0x01; 1 -> 0x23; 2 -> 0x45; 3 -> 0x67
                4 -> 0x89.toByte(); 5 -> 0xab.toByte(); 6 -> 0xcd.toByte(); else -> 0xef.toByte()
            }
        }, decoded)
    }
}
