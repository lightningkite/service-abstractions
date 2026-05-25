package com.lightningkite.services.email.ses

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.*

/**
 * Pinning tests for security-critical behavior in [SesEmailInboundService] SNS signature verification.
 *
 * Three behaviors are pinned here:
 * 1. Certificate URL host/path validation regex
 * 2. ±1 hour timestamp replay window
 * 3. Certificate subject CN must match the SNS signing identity
 *
 * These tests exercise the internal helpers ([SesEmailInboundService.validateCertificateUrl],
 * [SesEmailInboundService.verifySnsSignature], [SesEmailInboundService.certificateCache])
 * directly so that one behavior can be observed independently of the others.
 */
class SesEmailInboundSignatureSecurityTest {

    private val testContext = TestSettingContext()

    init {
        // Ensure URL parser is registered.
        SesEmailInboundService
    }

    private fun service() = SesEmailInboundService(name = "test-ses", context = testContext)

    // ==================== 1. Certificate URL regex ====================

    @Test
    fun validateCertificateUrl_acceptsCanonicalSnsUrl() {
        // Sanity-check the happy path documented by AWS:
        // https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html
        service().validateCertificateUrl(
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-abc123def456.pem"
        )
    }

    @Test
    fun validateCertificateUrl_rejectsWrongHost() {
        // An attacker-controlled domain must never be accepted even with a matching path.
        val ex = assertFailsWith<SecurityException> {
            service().validateCertificateUrl(
                "https://evil.com/SimpleNotificationService-abc.pem"
            )
        }
        assertTrue(
            ex.message?.contains("not a valid SNS endpoint") == true,
            "Expected host rejection, got: ${ex.message}"
        )
    }

    @Test
    fun validateCertificateUrl_rejectsWrongFilename() {
        // Right host, wrong filename — must still be rejected so attackers can't
        // upload arbitrary .pem files to an open S3 bucket fronted by sns.*.amazonaws.com.
        val ex = assertFailsWith<SecurityException> {
            service().validateCertificateUrl(
                "https://sns.us-east-1.amazonaws.com/evil.pem"
            )
        }
        assertTrue(
            ex.message?.contains("must point to a .pem file") == true,
            "Expected path rejection, got: ${ex.message}"
        )
    }

    @Test
    fun validateCertificateUrl_rejectsTrailingExtensionInjection() {
        // Guards against regex anchoring bugs: a path like /SimpleNotificationService-abc.pem.evil
        // must not satisfy a pattern that's only checking for ".pem" substring.
        val ex = assertFailsWith<SecurityException> {
            service().validateCertificateUrl(
                "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-abc.pem.evil"
            )
        }
        assertTrue(
            ex.message?.contains("must point to a .pem file") == true,
            "Expected path rejection, got: ${ex.message}"
        )
    }

    // ==================== 2. Timestamp replay window (±1h) ====================

    /**
     * Builds a syntactically valid SNS notification signed with [keyPair] whose
     * cert is pre-loaded into the service cache. The only field varied is the timestamp.
     */
    private fun buildAndPrimeService(timestamp: Instant): Pair<SesEmailInboundService, SnsNotification> {
        val keyPair = SnsTestUtils.generateKeyPair()
        val cert = SnsTestUtils.createSelfSignedCertificate(keyPair)
        val certUrl = "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-test.pem"

        val svc = service()
        svc.certificateCache[certUrl] = cert

        // Build the unsigned notification with the supplied timestamp, then sign so that
        // a signature failure can't mask a timestamp failure.
        val unsigned = SnsNotification(
            Type = "Notification",
            MessageId = "test-msg-id",
            TopicArn = "arn:aws:sns:us-east-1:123456789012:test-topic",
            Subject = null,
            Message = "hello",
            Timestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp.atOffset(ZoneOffset.UTC)),
            SignatureVersion = "2",
            Signature = "",
            SigningCertURL = certUrl,
        )
        val stringToSign = SnsTestUtils.buildStringToSign(unsigned)
        val signature = SnsTestUtils.signMessage(stringToSign, keyPair.private, "2")
        return svc to unsigned.copy(Signature = signature)
    }

    @Test
    fun timestampWithinWindow_doesNotThrowForTimestampReason() = runTest {
        // "Now" is well within the ±1h window, so verifySnsSignature must complete
        // without throwing. Any other failure here would be a regression.
        val (svc, notification) = buildAndPrimeService(Instant.now())
        svc.verifySnsSignature(notification)
    }

    @Test
    fun timestampTwoHoursPast_throwsTimestampSecurityException() = runTest {
        val (svc, notification) = buildAndPrimeService(Instant.now().minusSeconds(2 * 3600))
        val ex = assertFailsWith<SecurityException> { svc.verifySnsSignature(notification) }
        assertTrue(
            ex.message?.contains("timestamp", ignoreCase = true) == true,
            "Expected timestamp-related rejection, got: ${ex.message}"
        )
    }

    @Test
    fun timestampTwoHoursFuture_throwsTimestampSecurityException() = runTest {
        val (svc, notification) = buildAndPrimeService(Instant.now().plusSeconds(2 * 3600))
        val ex = assertFailsWith<SecurityException> { svc.verifySnsSignature(notification) }
        assertTrue(
            ex.message?.contains("timestamp", ignoreCase = true) == true,
            "Expected timestamp-related rejection, got: ${ex.message}"
        )
    }

    // ==================== 3. Certificate subject CN check ====================

    @Test
    fun certSubject_acceptsSnsAmazonawsComCn() = runTest {
        // CN matches AWS's documented SNS signing identity — must pass the CN check.
        val keyPair = SnsTestUtils.generateKeyPair()
        val cert = SnsTestUtils.createSelfSignedCertificate(keyPair, commonName = "sns.amazonaws.com")
        val certUrl = "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-good.pem"

        val svc = service()
        svc.certificateCache[certUrl] = cert

        val signed = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = "hello",
            privateKey = keyPair.private,
            certUrl = certUrl,
        )

        // Should complete without SecurityException — signature and timestamp are both valid.
        svc.verifySnsSignature(signed)
    }

    @Test
    fun certSubject_rejectsForeignCn() = runTest {
        // An attacker who managed to host a syntactically valid cert at a sns.*.amazonaws.com
        // URL (e.g., via a misconfigured bucket / open redirect) must still be rejected
        // because the subject CN does not match the SNS signing identity.
        val keyPair = SnsTestUtils.generateKeyPair()
        val cert = SnsTestUtils.createSelfSignedCertificate(keyPair, commonName = "evil.example.com")
        val certUrl = "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-evil.pem"

        val svc = service()
        svc.certificateCache[certUrl] = cert

        val signed = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = "hello",
            privateKey = keyPair.private,
            certUrl = certUrl,
        )

        val ex = assertFailsWith<SecurityException> { svc.verifySnsSignature(signed) }
        assertTrue(
            ex.message?.contains("SNS signing identity") == true ||
                ex.message?.contains("does not match expected") == true,
            "Expected CN-mismatch rejection, got: ${ex.message}"
        )
    }
}
