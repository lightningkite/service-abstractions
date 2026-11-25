package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours

/**
 * Verifies that [S3ExternalFileSystem.assertSignatureValid] (reached via
 * [S3ExternalFileSystem.parseExternalUrl]) validates signed URLs by PURE local HMAC recomputation —
 * no network round-trip — and rejects tampered signatures; only a URL signed under another access key
 * is checked with S3.
 */
class S3SignatureVerificationTest {

    init {
        // Force class init so S3ExternalFileSystem's companion registers the URL scheme;
        // the bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        S3ExternalFileSystem
    }

    // Any request reaching this client fails the test: the default path must stay offline.
    private fun system(
        client: HttpClient = HttpClient(MockEngine { error("Unexpected request to ${it.url}") }),
    ): S3ExternalFileSystem = S3ExternalFileSystem(
        name = "test",
        region = Region.US_WEST_2,
        credentialProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAEXAMPLEKEYID0000", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        ),
        bucket = "example-bucket",
        signedUrlDuration = 1.hours,
        context = TestSettingContext(),
        client = client,
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

    @Test
    fun urlWithoutSignatureRejected() {
        val system = system()
        val file = system.root.then("folder/test.txt")
        val unsigned = file.signedUrl.substringBefore("&X-Amz-Signature=")
        assertFailsWith<IllegalArgumentException> {
            system.parseExternalUrl(unsigned)
        }
    }

    /**
     * Only S3 holds the key behind another access key, such as our own temporary credentials before they rotated,
     * so it is asked; it answers 403 for a forgery.
     */
    @Test
    fun signatureUnderAnotherAccessKeyIsCheckedWithS3() {
        val asked = mutableListOf<String>()
        fun system(status: HttpStatusCode) = system(HttpClient(MockEngine { request ->
            asked += request.url.toString()
            respond("", status)
        }))
        val signed = system(HttpStatusCode.OK).root.then("folder/test.txt").signedUrl
            .replace("AKIAEXAMPLEKEYID0000", "AKIADIFFERENTKEYID00")

        assertFailsWith<IllegalArgumentException> { system(HttpStatusCode.Forbidden).parseExternalUrl(signed) }
        assertContains(asked.single(), "X-Amz-Credential=AKIADIFFERENTKEYID00")
        assertEquals(listOf("folder", "test.txt"), system(HttpStatusCode.PartialContent).parseExternalUrl(signed)?.path?.parts)
    }

    /**
     * A genuinely-signed URL that has passed its X-Amz-Date + X-Amz-Expires window must be rejected.
     * Local signature recomputation is not enough: without an expiry check, an expired-but-once-valid
     * URL would be accepted here and laundered into a permanent reference by the serializer.
     */
    @Test
    fun expiredSignatureRejected() {
        val system = system()
        val file = system.root.then("folder/test.txt")
        val params = file.signedUrl.substringAfter('?')

        // Freshly signed with a 1h window: valid right now.
        system.assertSignatureValid(file.path, params, now = java.time.Instant.now())

        // The exact same validly-signed URL, evaluated just past its 1h window: must be rejected.
        assertFailsWith<IllegalArgumentException> {
            system.assertSignatureValid(file.path, params, now = java.time.Instant.now().plusSeconds(3600 + 60))
        }
    }
}
