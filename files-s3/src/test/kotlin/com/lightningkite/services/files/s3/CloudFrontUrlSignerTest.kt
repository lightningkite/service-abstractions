package com.lightningkite.services.files.s3

import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CloudFrontUrlSigner] hand-rolls CloudFront's canned-policy signing, so these tests pin it against the
 * AWS SDK's implementation and check that verification rejects anything it didn't sign.
 */
class CloudFrontUrlSignerTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val signer = cloudFront("K2JCJMDEHXQW5F", keyPair.private)
    private val resource = "https://files.example.com/folder/caf%C3%A9%20menu.txt"

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun matchesAwsSdkSignature() {
        val expires = Instant.ofEpochSecond(1_900_000_000)
        val ours = signer.signResource(resource, expires)
        val sdk = CloudFrontUtilities.create().getSignedUrlWithCannedPolicy(
            CannedSignerRequest.builder()
                .resourceUrl(resource)
                .privateKey(keyPair.private)
                .keyPairId("K2JCJMDEHXQW5F")
                .expirationDate(expires)
                .build()
        ).url()

        assertEquals(sdk.substringBefore('?'), ours.substringBefore('?'))
        assertEquals(params(sdk), params(ours))
    }

    @Test
    fun verifiesItsOwnSignature() {
        val signed = signer.signResource(resource, Instant.now().plusSeconds(3600))
        signer.verifyResource(resource, signed.substringAfter('?'))
    }

    @Test
    fun rejectsTamperedSignature() {
        val query = signer.signResource(resource, Instant.now().plusSeconds(3600)).substringAfter('?')
        val signature = params("?$query").getValue("Signature")
        val flipped = (if (signature[10] == 'A') 'B' else 'A').toString()
        val tampered = query.replace(signature, signature.replaceRange(10, 11, flipped))
        assertFailsWith<IllegalArgumentException> { signer.verifyResource(resource, tampered) }
    }

    @Test
    fun rejectsSignatureForAnotherFile() {
        val query = signer.signResource(resource, Instant.now().plusSeconds(3600)).substringAfter('?')
        assertFailsWith<IllegalArgumentException> {
            signer.verifyResource("https://files.example.com/someone-else.txt", query)
        }
    }

    @Test
    fun rejectsExtendedExpiry() {
        val expires = Instant.now().plusSeconds(3600)
        val query = signer.signResource(resource, expires).substringAfter('?')
            .replace("Expires=${expires.epochSecond}", "Expires=${expires.epochSecond + 86_400}")
        assertFailsWith<IllegalArgumentException> { signer.verifyResource(resource, query) }
    }

    @Test
    fun rejectsExpiredUrl() {
        val expires = Instant.now().plusSeconds(60)
        val query = signer.signResource(resource, expires).substringAfter('?')
        assertFailsWith<IllegalArgumentException> {
            signer.verifyResource(resource, query, now = expires)
        }
    }

    @Test
    fun rejectsOtherKeyPairId() {
        val other = cloudFront("KOTHERKEY0000", keyPair.private)
        val query = other.signResource(resource, Instant.now().plusSeconds(3600)).substringAfter('?')
        assertFailsWith<IllegalArgumentException> { signer.verifyResource(resource, query) }
    }

    @Test
    fun rejectsMissingParameters() {
        assertFailsWith<IllegalArgumentException> { signer.verifyResource(resource, "") }
        assertFailsWith<IllegalArgumentException> { signer.verifyResource(resource, "Expires=1900000000&Key-Pair-Id=K2JCJMDEHXQW5F") }
    }

    @Test
    fun rejectsMalformedSignature() {
        val query = signer.signResource(resource, Instant.now().plusSeconds(3600)).substringAfter('?')
        val signature = params("?$query").getValue("Signature")
        assertFailsWith<IllegalArgumentException> { signer.verifyResource(resource, query.replace(signature, "not*base64!")) }
        assertFailsWith<IllegalArgumentException> { signer.verifyResource(resource, query.replace(signature, signature.take(40))) }
    }

    /** A key without CRT parameters has no public exponent to derive from, so verification re-signs instead. */
    @Test
    fun keyWithoutPublicExponentStillVerifies() {
        val crt = keyPair.private as java.security.interfaces.RSAPrivateCrtKey
        val bare = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.RSAPrivateKeySpec(crt.modulus, crt.privateExponent))
        assertTrue(bare !is java.security.interfaces.RSAPrivateCrtKey)
        val bareSigner = cloudFront("K2JCJMDEHXQW5F", bare)

        val query = bareSigner.signResource(resource, Instant.now().plusSeconds(3600)).substringAfter('?')
        bareSigner.verifyResource(resource, query)
        // Same key, so the two signers accept each other's URLs.
        signer.verifyResource(resource, query)
        assertFailsWith<IllegalArgumentException> {
            bareSigner.verifyResource("https://files.example.com/someone-else.txt", query)
        }
    }

    @Test
    fun toStringHidesPrivateKey() {
        assertTrue("***" in signer.toString())
        assertTrue(Base64.getEncoder().encodeToString(keyPair.private.encoded).take(40) !in signer.toString())
    }

    // --- Private key parsing ---

    private val pkcs8: ByteArray = keyPair.private.encoded

    /** The RSAPrivateKey inside PKCS#8's trailing OCTET STRING, i.e. the same key as PKCS#1. */
    private val pkcs1: ByteArray = run {
        fun lengthAt(at: Int): Pair<Int, Int> { // (length, header bytes after the tag)
            val first = pkcs8[at].toInt() and 0xff
            if (first < 0x80) return first to 1
            val count = first and 0x7f
            var length = 0
            for (i in 1..count) length = (length shl 8) or (pkcs8[at + i].toInt() and 0xff)
            return length to 1 + count
        }
        var at = 1 + lengthAt(1).second // inside the outer SEQUENCE
        at += 3 // INTEGER version
        at += 1 + lengthAt(at + 1).let { it.second + it.first } // algorithm SEQUENCE
        check(pkcs8[at] == 0x04.toByte())
        val (length, header) = lengthAt(at + 1)
        pkcs8.copyOfRange(at + 1 + header, at + 1 + header + length)
    }

    private fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    private fun assertParsesToOurKey(encoded: String) {
        assertEquals(keyPair.private, CloudFrontUrlSigner.parsePrivateKey(encoded))
    }

    @Test
    fun parsesPkcs8Pem() = assertParsesToOurKey(pem("PRIVATE KEY", pkcs8))

    @Test
    fun parsesPkcs1Pem() = assertParsesToOurKey(pem("RSA PRIVATE KEY", pkcs1))

    @Test
    fun parsesBase64OfPem() =
        assertParsesToOurKey(Base64.getEncoder().encodeToString(pem("PRIVATE KEY", pkcs8).toByteArray()))

    @Test
    fun parsesUrlSafeBase64OfPem() =
        assertParsesToOurKey(Base64.getUrlEncoder().withoutPadding().encodeToString(pem("RSA PRIVATE KEY", pkcs1).toByteArray()))

    @Test
    fun parsesBase64OfDer() {
        assertParsesToOurKey(Base64.getEncoder().encodeToString(pkcs8))
        assertParsesToOurKey(Base64.getEncoder().encodeToString(pkcs1))
    }

    @Test
    fun rejectsEncryptedOrUnknownKeys() {
        assertFailsWith<IllegalArgumentException> {
            CloudFrontUrlSigner.parsePrivateKey(pem("ENCRYPTED PRIVATE KEY", pkcs8))
        }
        assertFailsWith<IllegalArgumentException> { CloudFrontUrlSigner.parsePrivateKey("not a key") }
        assertFailsWith<IllegalArgumentException> { CloudFrontUrlSigner.parsePrivateKey("") }
    }
}
