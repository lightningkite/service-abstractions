package com.lightningkite.services.files.s3

import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.files.ExternalFileSystem
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * [S3PresignedUrlSigner] hand-rolls SigV4 presigning, so these tests pin it against the SDK's
 * [S3Presigner]. Also covers how [CloudFrontUrlSigner] hands work to the signer it wraps.
 * Everything is CPU-only; no request reaches S3.
 */
class S3PresignedUrlSignerTest {

    private val session = StaticCredentialsProvider.create(
        AwsSessionCredentials.create("ASIAEXAMPLEKEYID0000", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "token/with+special=chars")
    )
    private val host = "example-bucket.s3.us-west-2.amazonaws.com"

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    /**
     * Signs the same request both ways. The signature covers X-Amz-Date (to the second), so retry if the
     * two land in different seconds.
     */
    private fun assertMatchesSdk(credentials: StaticCredentialsProvider, key: String, upload: Boolean) {
        val ours = S3PresignedUrlSigner(Region.US_WEST_2, "example-bucket", credentials)
        val sdk = S3Presigner.builder().region(Region.US_WEST_2).credentialsProvider(credentials).build()
        repeat(5) {
            val mine = if (upload) ours.signUpload(key, 1.hours) else ours.signDownload(key, 1.hours)
            val theirs = if (upload) {
                sdk.presignPutObject {
                    it.signatureDuration(1.hours.toJavaDuration())
                    it.putObjectRequest { r -> r.bucket("example-bucket").key(key) }
                }.url().toString()
            } else {
                sdk.presignGetObject {
                    it.signatureDuration(1.hours.toJavaDuration())
                    it.getObjectRequest { r -> r.bucket("example-bucket").key(key) }
                }.url().toString()
            }
            if (params(mine)["X-Amz-Date"] != params(theirs)["X-Amz-Date"]) return@repeat
            assertEquals(theirs.substringBefore('?'), mine.substringBefore('?'))
            assertEquals(params(theirs), params(mine))
            return
        }
        error("Couldn't sign within the same second in 5 tries")
    }

    @Test
    fun downloadMatchesSdk() = assertMatchesSdk(testCredentials, "folder/test.txt", upload = false)

    @Test
    fun downloadWithEncodedKeyMatchesSdk() =
        assertMatchesSdk(testCredentials, "folder/hello world+café.txt", upload = false)

    @Test
    fun downloadWithSessionTokenMatchesSdk() = assertMatchesSdk(session, "folder/test.txt", upload = false)

    @Test
    fun uploadMatchesSdk() = assertMatchesSdk(testCredentials, "folder/test.txt", upload = true)

    @Test
    fun verifiesSdkPresignedUrl() {
        val sdk = S3Presigner.builder().region(Region.US_WEST_2).credentialsProvider(testCredentials).build()
        val presigned = sdk.presignGetObject {
            it.signatureDuration(1.hours.toJavaDuration())
            it.getObjectRequest { r -> r.bucket("example-bucket").key("folder/test.txt") }
        }.url().toString()
        testS3Signer().verify("folder/test.txt", presigned.substringAfter('?'))
    }

    @Test
    fun verifiesItsOwnUrlAndRejectsExpired() {
        val signer = testS3Signer()
        val query = signer.signDownload("folder/test.txt", 1.hours).substringAfter('?')
        signer.verify("folder/test.txt", query)
        assertFailsWith<IllegalArgumentException> {
            signer.verify("folder/test.txt", query, now = java.time.Instant.now().plusSeconds(3600 + 60))
        }
    }

    @Test
    fun toStringHidesCredentials() {
        assertFalse("wJalrXUtnFEMI" in testS3Signer().toString())
    }

    // --- Server signatures (urlSecret) ---

    private val urlSecret = "0123456789abcdef0123456789abcdef"

    /** Another server's AWS credentials: a different key pair from [testCredentials]. */
    private val otherServer = StaticCredentialsProvider.create(
        AwsBasicCredentials.create("AKIAOTHERKEYID000000", "otherSecretAccessKey/EXAMPLEKEY000000000")
    )

    private fun secretSigner(credentials: StaticCredentialsProvider, secret: String? = urlSecret) =
        S3PresignedUrlSigner(Region.US_WEST_2, "example-bucket", credentials, secret?.encodeToByteArray())

    @Test
    fun serverSignatureIsTheLastSignedParameter() {
        val url = secretSigner(testCredentials).signDownload("folder/test.txt", 1.hours)
        val names = url.substringAfter('?').split('&').map { it.substringBefore('=') }
        assertEquals(listOf(S3PresignedUrlSigner.SERVER_SIGNATURE_PARAM, "X-Amz-Signature"), names.takeLast(2))
        assertEquals(names.dropLast(1), names.dropLast(1).sorted())
    }

    @Test
    fun serverSignedUrlVerifiesWithOtherAwsCredentials() {
        // Another server: same secret, different (e.g. per-instance or rotated) AWS credentials. Without the
        // server signature this would have to ask S3, which these fake credentials can't satisfy.
        val query = secretSigner(testCredentials).signDownload("folder/hello world.txt", 1.hours).substringAfter('?')
        secretSigner(otherServer).verify("folder/hello world.txt", query)
    }

    @Test
    fun serverSignedUrlStillCarriesAValidS3Signature() {
        // A signer with the same AWS credentials but no secret can only check X-Amz-Signature, which must
        // cover the server signature parameter in its sorted place for S3 to accept the URL.
        val query = secretSigner(testCredentials).signDownload("folder/test.txt", 1.hours).substringAfter('?')
        secretSigner(testCredentials, secret = null).verify("folder/test.txt", query)
    }

    @Test
    fun serverSignedUrlStillExpires() {
        val query = secretSigner(testCredentials).signDownload("folder/test.txt", 1.hours).substringAfter('?')
        assertFailsWith<IllegalArgumentException> {
            secretSigner(otherServer).verify("folder/test.txt", query, now = java.time.Instant.now().plusSeconds(3600 + 60))
        }
    }

    @Test
    fun urlsSignedBeforeTheSecretStillVerify() {
        val query = testS3Signer().signDownload("folder/test.txt", 1.hours).substringAfter('?')
        secretSigner(testCredentials).verify("folder/test.txt", query)
    }

    @Test
    fun uploadUrlsHaveNoServerSignature() {
        val url = secretSigner(testCredentials).signUpload("folder/test.txt", 1.hours)
        assertFalse(S3PresignedUrlSigner.SERVER_SIGNATURE_PARAM in url, url)
    }

    @Test
    fun shortServerKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> { secretSigner(testCredentials, secret = "too-short") }
    }

    // --- Where the settings URL gets the server key ---

    /** A host whose deployment secret derives [derived] for every purpose, recording the purposes asked for. */
    private class SecretHost(val derived: ByteArray?) : SettingContext by TestSettingContext() {
        val purposes = mutableListOf<String>()
        override fun deriveSecret(purpose: String): ByteArray? = derived.also { purposes += purpose }
    }

    private fun settingsSystem(context: SettingContext, query: String = "signedUrlDuration=1h") =
        ExternalFileSystem.Settings(
            // Static credentials in the URL, so signing never looks for real ones.
            "s3://AKIASETTINGSKEY00000:settingsSecretKeyEXAMPLE@example-bucket.s3-us-west-2.amazonaws.com/?$query"
        ).invoke("test", context) as S3ExternalFileSystem

    private fun S3ExternalFileSystem.signedTestUrl() = root.then("folder/test.txt").signedUrl

    @Test
    fun settingsUrlPassesTheUrlSecret() {
        val url = settingsSystem(TestSettingContext(), "signedUrlDuration=1h&urlSecret=$urlSecret").signedTestUrl()
        assertTrue("&${S3PresignedUrlSigner.SERVER_SIGNATURE_PARAM}=" in url, url)
        secretSigner(otherServer).verify("folder/test.txt", url.substringAfter('?'))
    }

    @Test
    fun settingsUrlRejectsAShortUrlSecret() {
        assertFailsWith<IllegalArgumentException> {
            settingsSystem(TestSettingContext(), "signedUrlDuration=1h&urlSecret=too-short")
        }
    }

    @Test
    fun serverKeyIsDerivedFromTheContextPerBucket() {
        val host = SecretHost(urlSecret.encodeToByteArray())
        val url = settingsSystem(host).signedTestUrl()
        assertEquals(listOf("files-s3/example-bucket"), host.purposes)
        assertTrue("&${S3PresignedUrlSigner.SERVER_SIGNATURE_PARAM}=" in url, url)
        // Any server whose context derives the same key verifies it, whatever its AWS credentials.
        secretSigner(otherServer).verify("folder/test.txt", url.substringAfter('?'))
    }

    @Test
    fun urlSecretOverridesTheContext() {
        val host = SecretHost("a-different-derived-key-0000000000".encodeToByteArray())
        val url = settingsSystem(host, "signedUrlDuration=1h&urlSecret=$urlSecret").signedTestUrl()
        assertEquals(emptyList(), host.purposes)
        secretSigner(otherServer).verify("folder/test.txt", url.substringAfter('?'))
    }

    @Test
    fun noServerSignatureWithoutAKey() {
        val url = settingsSystem(SecretHost(null)).signedTestUrl()
        assertFalse(S3PresignedUrlSigner.SERVER_SIGNATURE_PARAM in url, url)
    }

    // --- CloudFrontUrlSigner wrapping an S3 signer ---

    private val cloudFront by lazy {
        cloudFront("K2JCJMDEHXQW5F", KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private)
    }

    @Test
    fun cloudFrontSignsDownloadsOnItsDomain() {
        val url = cloudFront.signDownload("folder/hello world.txt", 1.hours)
        assertTrue(url.startsWith("https://files.example.com/folder/hello%20world.txt?Expires="), url)
        cloudFront.verify("folder/hello world.txt", url.substringAfter('?'))
    }

    @Test
    fun cloudFrontLeavesUploadsToS3() {
        val url = cloudFront.signUpload("folder/test.txt", 1.hours)
        assertTrue(url.startsWith("https://$host/folder/test.txt?X-Amz-Algorithm="), url)
    }

    @Test
    fun cloudFrontVerifiesS3PresignedUrlsThroughTheWrappedSigner() {
        val presigned = testS3Signer().signDownload("folder/test.txt", 1.hours)
        cloudFront.verify("folder/test.txt", presigned.substringAfter('?'))
        assertFailsWith<IllegalArgumentException> {
            cloudFront.verify("folder/test.txt", presigned.substringAfter('?'), now = java.time.Instant.now().plusSeconds(3600 + 60))
        }
    }

    @Test
    fun cloudFrontRejectsItsUrlsForAnotherKey() {
        val query = cloudFront.signDownload("folder/test.txt", 1.hours).substringAfter('?')
        assertFailsWith<IllegalArgumentException> { cloudFront.verify("folder/other.txt", query) }
    }
}
