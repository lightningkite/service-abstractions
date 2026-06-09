package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.files.ExternalServerFileSerializer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours

/**
 * Verifies that [S3FileObject.assertSignatureValid] (reached via
 * [S3PublicFileSystem.parseExternalUrl]) validates signed URLs by PURE local HMAC recomputation —
 * no network round-trip — and rejects tampered signatures.
 *
 * Signing and verification with static credentials are entirely CPU-bound, so these tests need no
 * real AWS access and never touch the network on the default path.
 */
class S3SignatureVerificationTest {

    init {
        S3PublicFileSystem
    }

    @AfterTest
    fun resetFlag() {
        ExternalServerFileSerializer.inlineScanOnDeserialize = false
    }

    private fun system(): S3PublicFileSystem = S3PublicFileSystem(
        name = "test",
        region = Region.US_WEST_2,
        credentialProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAEXAMPLEKEYID0000", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        ),
        bucket = "example-bucket",
        signedUrlDuration = 1.hours,
        context = TestSettingContext(),
    )

    /**
     * A validly-signed URL produced by our own signer must pass via local recomputation with no
     * network, returning the parsed file object.
     */
    @Test
    fun validSignaturePassesWithoutNetwork() {
        val system = system()
        val file = system.root.then("folder/test.txt")
        val signed = file.signedUrl

        // Default path: must NOT throw and must NOT hit the network.
        val parsed = system.parseExternalUrl(signed)
        assertEquals(file, parsed, "A validly-signed URL should parse back to the same file object")
    }

    /**
     * A URL whose signature has been tampered with must be rejected by recomputation — without any
     * network fallback on the default path.
     */
    @Test
    fun tamperedSignatureRejected() {
        val system = system()
        val file = system.root.then("folder/test.txt")
        val signed = file.signedUrl

        // Flip the last hex character of the signature to a different valid hex char.
        val sigValue = signed.substringAfter("X-Amz-Signature=")
        val lastChar = sigValue.last()
        val replacement = if (lastChar == '0') '1' else '0'
        val tampered = signed.dropLast(1) + replacement

        assertFailsWith<IllegalArgumentException> {
            system.parseExternalUrl(tampered)
        }
    }

    /**
     * A foreign signature using parameters we never produced (different X-Amz-Credential) is
     * rejected on the default path rather than being trusted.
     */
    @Test
    fun foreignSignatureRejected() {
        val system = system()
        val file = system.root.then("folder/test.txt")
        val signed = file.signedUrl
        // Swap the credential's access-key portion so recomputation produces a different signature.
        val tampered = signed.replace("AKIAEXAMPLEKEYID0000", "AKIADIFFERENTKEYID00")
        assertFailsWith<IllegalArgumentException> {
            system.parseExternalUrl(tampered)
        }
    }
}
