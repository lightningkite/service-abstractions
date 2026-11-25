package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.files.ExternalFileSystem
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Serving a bucket from a custom domain through CloudFront: URLs handed out use the domain, private
 * buckets get CloudFront signed URLs, and references on the S3 host keep resolving. All CPU-only.
 */
class S3CustomDomainTest {

    init {
        // Force class init so S3ExternalFileSystem's companion registers the URL scheme;
        // the bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        S3ExternalFileSystem
    }

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create("AKIAEXAMPLEKEYID0000", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    )

    /** CloudFront on files.example.com without a signing key, as for a public bucket. */
    private fun publicCloudFront() = CloudFrontUrlSigner(testS3Signer(), "files.example.com")

    private fun system(
        signedUrlDuration: Duration? = 1.hours,
        urlSigner: S3UrlSigner = cloudFront("K2JCJMDEHXQW5F", keyPair.private),
    ) = S3ExternalFileSystem(
        name = "test",
        region = Region.US_WEST_2,
        credentialProvider = credentials,
        bucket = "example-bucket",
        signedUrlDuration = signedUrlDuration,
        context = TestSettingContext(),
        urlSigner = urlSigner,
    )

    private val edgeCaseKeys = listOf(
        "folder/test.txt",
        "hello world.txt",
        "a+b.txt",
        "file%20name.txt",
        "café.txt",
        "a=b&c.txt",
    )

    @Test
    fun privateBucketHandsOutCloudFrontSignedUrls() {
        val system = system()
        val signed = system.root.then("folder/test.txt").signedUrl
        assertTrue(signed.startsWith("https://files.example.com/folder/test.txt?Expires="), signed)
        assertTrue("Key-Pair-Id=K2JCJMDEHXQW5F" in signed, signed)
        assertTrue("X-Amz-" !in signed, signed)
    }

    @Test
    fun privateBucketSignedUrlsRoundTrip() {
        val system = system()
        val failures = edgeCaseKeys.mapNotNull { key ->
            val file = system.root.then(key)
            val parsed = runCatching { system.parseExternalUrl(file.signedUrl) }
            when {
                parsed.isFailure -> "key='$key': threw ${parsed.exceptionOrNull()}"
                parsed.getOrNull() != file -> "key='$key': resolved to ${parsed.getOrNull()?.path}"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun privateBucketRejectsUnsignedAndTamperedDomainUrls() {
        val system = system()
        val file = system.root.then("folder/test.txt")
        val signed = file.signedUrl
        val otherFile = signed.replace("/folder/test.txt?", "/folder/other.txt?")
        assertFailsWith<IllegalArgumentException> { system.parseExternalUrl(otherFile) }
        val badKey = signed.replace("Key-Pair-Id=K2JCJMDEHXQW5F", "Key-Pair-Id=KOTHER")
        assertFailsWith<IllegalArgumentException> { system.parseExternalUrl(badKey) }
    }

    @Test
    fun s3PresignedUrlsIssuedBeforeTheDomainStillVerify() {
        val before = system(urlSigner = testS3Signer())
        val after = system()
        val file = before.root.then("folder/test.txt")
        val presigned = file.signedUrl
        assertTrue(presigned.startsWith("https://example-bucket.s3.us-west-2.amazonaws.com/"), presigned)
        assertEquals(after.root.then("folder/test.txt"), after.parseExternalUrl(presigned))
    }

    @Test
    fun explicitTimeoutUsesCloudFrontToo() {
        val signed = system().root.then("a.txt").signUrl(5.hours)
        assertTrue(signed.startsWith("https://files.example.com/a.txt?Expires="), signed)
        val expires = signed.substringAfter("Expires=").substringBefore('&').toLong()
        val expected = java.time.Instant.now().plusSeconds(5 * 3600).epochSecond
        assertTrue(kotlin.math.abs(expires - expected) <= 5, "Expires=$expires, expected about $expected")
    }

    @Test
    fun uploadUrlsStillGoToS3() {
        val upload = system().root.then("a.txt").uploadUrl(1.hours)
        assertTrue(upload.startsWith("https://example-bucket.s3.us-west-2.amazonaws.com/a.txt?"), upload)
    }

    @Test
    fun publicBucketHandsOutPlainDomainUrls() {
        val system = system(signedUrlDuration = null, urlSigner = publicCloudFront())
        val file = system.root.then("hello world.txt")
        assertEquals("https://files.example.com/hello%20world.txt", file.signedUrl)
        assertEquals(file, system.parseExternalUrl(file.signedUrl))
    }

    @Test
    fun storedUrlsOnEitherHostResolve() {
        val system = system()
        val file = system.root.then("a+b.txt")
        assertEquals(file, system.parseLegacyUrl("https://files.example.com/a+b.txt"))
        assertEquals(file, system.parseLegacyUrl("https://example-bucket.s3.us-west-2.amazonaws.com/a+b.txt"))
        assertEquals(file, system.parseLegacyUrl("https://s3-us-west-2.amazonaws.com/example-bucket/a+b.txt"))
        assertNull(system.parseLegacyUrl("https://files.example.org/a+b.txt"))
    }

    @Test
    fun publicBucketExplicitTimeoutFallsBackToS3() {
        // No CloudFront key to sign with, so a time-limited URL can only be an S3 presigned one.
        val system = system(signedUrlDuration = null, urlSigner = publicCloudFront())
        val signed = system.root.then("a.txt").signUrl(1.hours)
        assertTrue(signed.startsWith("https://example-bucket.s3.us-west-2.amazonaws.com/a.txt?X-Amz-"), signed)
    }

    @Test
    fun cloudFrontRejectsBadConfiguration() {
        assertFailsWith<IllegalArgumentException> { CloudFrontUrlSigner(testS3Signer(), "https://files.example.com") }
        assertFailsWith<IllegalArgumentException> { CloudFrontUrlSigner(testS3Signer(), "files.example.com/prefix") }
        assertFailsWith<IllegalArgumentException> {
            CloudFrontUrlSigner(testS3Signer(), "files.example.com", keyPairId = "K2JCJMDEHXQW5F")
        }
        assertFailsWith<IllegalArgumentException> {
            CloudFrontUrlSigner(testS3Signer(), "files.example.com", privateKey = keyPair.private)
        }
    }

    // --- Settings URL ---

    private val pem: String =
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keyPair.private.encoded) + "\n-----END PRIVATE KEY-----\n"

    private val rawKey: String = Base64.getEncoder().encodeToString(pem.toByteArray())

    @Test
    fun settingsUrlWithTerraformStyleKey() {
        // Matches what awsS3Bucket emits: urlencode(base64encode(private_key_pem_pkcs8)).
        val key = URLEncoder.encode(Base64.getEncoder().encodeToString(pem.toByteArray()), Charsets.UTF_8)
        val system = ExternalFileSystem.Settings(
            "s3://example-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=1h&domain=files.example.com" +
                    "&cloudFrontKeyId=K2JCJMDEHXQW5F&cloudFrontPrivateKey=$key"
        ).invoke("test", TestSettingContext()) as S3ExternalFileSystem

        assertEquals("https://files.example.com/", system.urlSigner.publicRoot)
        assertEquals("K2JCJMDEHXQW5F", (system.urlSigner as CloudFrontUrlSigner).keyPairId)
        val file = system.root.then("folder/test.txt")
        assertTrue(file.signedUrl.startsWith("https://files.example.com/folder/test.txt?Expires="))
        // Signs with the same key as the original pair.
        system().urlSigner.verify("folder/test.txt", file.signedUrl.substringAfter('?'))
    }

    @Test
    fun settingsUrlWithRawBase64Key() {
        // Unencoded base64 ('+', '/', '=') must survive the settings parser too.
        val key = Base64.getEncoder().encodeToString(pem.toByteArray())
        val system = ExternalFileSystem.Settings(
            "s3://example-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=1h&domain=files.example.com" +
                    "&cloudFrontKeyId=K2JCJMDEHXQW5F&cloudFrontPrivateKey=$key"
        ).invoke("test", TestSettingContext()) as S3ExternalFileSystem
        assertEquals(keyPair.private, CloudFrontUrlSigner.parsePrivateKey(pem))
        assertTrue(system.urlSigner is CloudFrontUrlSigner)
    }

    @Test
    fun settingsUrlForPublicDomain() {
        val system = ExternalFileSystem.Settings(
            "s3://example-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=forever&domain=files.example.com"
        ).invoke("test", TestSettingContext()) as S3ExternalFileSystem
        assertEquals("https://files.example.com/a.txt", system.root.then("a.txt").signedUrl)
    }

    @Test
    fun settingsUrlRejectsHalfACloudFrontKey() {
        assertFailsWith<IllegalArgumentException> {
            ExternalFileSystem.Settings(
                "s3://example-bucket.s3-us-west-2.amazonaws.com/?domain=files.example.com&cloudFrontKeyId=K2JCJMDEHXQW5F"
            ).invoke("test", TestSettingContext())
        }
        // A key with no domain to serve it on.
        assertFailsWith<IllegalArgumentException> {
            ExternalFileSystem.Settings(
                "s3://example-bucket.s3-us-west-2.amazonaws.com/?cloudFrontKeyId=K2JCJMDEHXQW5F&cloudFrontPrivateKey=$rawKey"
            ).invoke("test", TestSettingContext())
        }
        // Private (default 1h) on a domain with no key at all.
        assertFailsWith<IllegalArgumentException> {
            ExternalFileSystem.Settings(
                "s3://example-bucket.s3-us-west-2.amazonaws.com/?domain=files.example.com"
            ).invoke("test", TestSettingContext())
        }
        // Signing keys on a public bucket, whose URLs would go out unsigned.
        for (duration in listOf("forever", "null", "Infinity")) {
            assertFailsWith<IllegalArgumentException>(duration) {
                ExternalFileSystem.Settings(
                    "s3://example-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=$duration&domain=files.example.com" +
                            "&cloudFrontKeyId=K2JCJMDEHXQW5F&cloudFrontPrivateKey=$rawKey"
                ).invoke("test", TestSettingContext())
            }
        }
    }
}
