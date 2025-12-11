package com.lightningkite.services.email.ses

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date

/**
 * Test utilities for generating and signing SNS notifications.
 *
 * This class provides utilities to:
 * 1. Generate RSA keypairs for testing
 * 2. Create self-signed X.509 certificates using Bouncy Castle
 * 3. Sign SNS messages with the test private key
 * 4. Generate realistic SNS/SES notification payloads
 */
object SnsTestUtils {

    init {
        // Register Bouncy Castle provider
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    /**
     * Generates an RSA keypair for testing.
     */
    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    /**
     * Creates a self-signed X.509 certificate for testing using Bouncy Castle.
     */
    fun createSelfSignedCertificate(keyPair: KeyPair, daysValid: Int = 365): X509Certificate {
        val now = Date()
        val notBefore = now
        val notAfter = Date(now.time + daysValid.toLong() * 24 * 60 * 60 * 1000)

        val issuer = X500Name("CN=SNS Test Certificate, O=Test, C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)

        val certHolder = certBuilder.build(signer)

        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certHolder)
    }

    /**
     * Exports a certificate to PEM format.
     */
    fun exportCertificateToPem(certificate: X509Certificate): String {
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
    }

    /**
     * Signs a message using the SNS signature format.
     *
     * @param stringToSign The constructed string to sign
     * @param privateKey The private key to sign with
     * @param signatureVersion "1" for SHA1withRSA, "2" for SHA256withRSA
     * @return Base64-encoded signature
     */
    fun signMessage(stringToSign: String, privateKey: PrivateKey, signatureVersion: String = "2"): String {
        val algorithm = when (signatureVersion) {
            "1" -> "SHA1withRSA"
            "2" -> "SHA256withRSA"
            else -> throw IllegalArgumentException("Unknown signature version: $signatureVersion")
        }

        val signature = Signature.getInstance(algorithm)
        signature.initSign(privateKey)
        signature.update(stringToSign.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /**
     * Builds the string-to-sign for an SNS notification.
     * This must match exactly what SesEmailInboundService.buildStringToSign() expects.
     */
    fun buildStringToSign(notification: SnsNotification): String {
        return buildString {
            append("Message\n")
            append(notification.Message)
            append("\n")

            append("MessageId\n")
            append(notification.MessageId)
            append("\n")

            if (notification.Type == "SubscriptionConfirmation" ||
                notification.Type == "UnsubscribeConfirmation") {
                if (notification.SubscribeURL != null) {
                    append("SubscribeURL\n")
                    append(notification.SubscribeURL)
                    append("\n")
                }
            }

            if (notification.Type == "Notification" && notification.Subject != null) {
                append("Subject\n")
                append(notification.Subject)
                append("\n")
            }

            append("Timestamp\n")
            append(notification.Timestamp)
            append("\n")

            if (notification.Type == "SubscriptionConfirmation" ||
                notification.Type == "UnsubscribeConfirmation") {
                if (notification.Token != null) {
                    append("Token\n")
                    append(notification.Token)
                    append("\n")
                }
            }

            if (notification.TopicArn != null) {
                append("TopicArn\n")
                append(notification.TopicArn)
                append("\n")
            }

            append("Type\n")
            append(notification.Type)
            append("\n")
        }
    }

    /**
     * Creates a signed SNS notification.
     */
    fun createSignedNotification(
        type: String,
        message: String,
        privateKey: PrivateKey,
        certUrl: String,
        messageId: String = "test-message-${System.currentTimeMillis()}",
        topicArn: String = "arn:aws:sns:us-east-1:123456789012:test-topic",
        subject: String? = null,
        subscribeUrl: String? = null,
        token: String? = null,
        signatureVersion: String = "2"
    ): SnsNotification {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))

        // Create notification without signature first
        val unsigned = SnsNotification(
            Type = type,
            MessageId = messageId,
            TopicArn = topicArn,
            Subject = subject,
            Message = message,
            Timestamp = timestamp,
            SignatureVersion = signatureVersion,
            Signature = "", // Placeholder
            SigningCertURL = certUrl,
            SubscribeURL = subscribeUrl,
            Token = token
        )

        // Build string to sign and create signature
        val stringToSign = buildStringToSign(unsigned)
        val signature = signMessage(stringToSign, privateKey, signatureVersion)

        // Return notification with signature
        return unsigned.copy(Signature = signature)
    }

    /**
     * Creates a sample SES notification payload (the Message content of an SNS notification).
     */
    fun createSesNotificationMessage(
        from: String = "sender@example.com",
        to: List<String> = listOf("recipient@test.local"),
        subject: String = "Test Email",
        plainText: String = "Hello, this is a test email.",
        html: String? = null,
        messageId: String = "test-message-id@example.com",
        includeContent: Boolean = true
    ): String {
        val mimeContent = if (includeContent) {
            buildMimeMessage(from, to, subject, plainText, html, messageId)
        } else {
            null
        }

        val sesNotification = SesNotification(
            notificationType = "Received",
            mail = SesMailObject(
                timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)),
                messageId = "ses-$messageId",
                source = from,
                destination = to,
                headersTruncated = false,
                headers = listOf(
                    SesHeader("From", from),
                    SesHeader("To", to.joinToString(", ")),
                    SesHeader("Subject", subject),
                    SesHeader("Message-ID", "<$messageId>")
                ),
                commonHeaders = SesCommonHeaders(
                    from = listOf(from),
                    to = to,
                    subject = subject,
                    messageId = "<$messageId>"
                )
            ),
            receipt = SesReceiptObject(
                timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)),
                recipients = to,
                spamVerdict = SesVerdict("PASS"),
                virusVerdict = SesVerdict("PASS"),
                spfVerdict = SesVerdict("PASS"),
                dkimVerdict = SesVerdict("PASS"),
                dmarcVerdict = SesVerdict("PASS")
            ),
            content = mimeContent
        )

        return json.encodeToString(sesNotification)
    }

    /**
     * Builds a simple MIME message for testing.
     */
    fun buildMimeMessage(
        from: String,
        to: List<String>,
        subject: String,
        plainText: String,
        html: String? = null,
        messageId: String = "test@example.com"
    ): String {
        return if (html != null) {
            // Multipart alternative
            val boundary = "----=_Part_${System.currentTimeMillis()}"
            """
            |From: $from
            |To: ${to.joinToString(", ")}
            |Subject: $subject
            |Message-ID: <$messageId>
            |MIME-Version: 1.0
            |Content-Type: multipart/alternative; boundary="$boundary"
            |
            |--$boundary
            |Content-Type: text/plain; charset=UTF-8
            |
            |$plainText
            |--$boundary
            |Content-Type: text/html; charset=UTF-8
            |
            |$html
            |--$boundary--
            """.trimMargin()
        } else {
            // Plain text only
            """
            |From: $from
            |To: ${to.joinToString(", ")}
            |Subject: $subject
            |Message-ID: <$messageId>
            |MIME-Version: 1.0
            |Content-Type: text/plain; charset=UTF-8
            |
            |$plainText
            """.trimMargin()
        }
    }

    /**
     * Creates a subscription confirmation notification.
     */
    fun createSubscriptionConfirmation(
        privateKey: PrivateKey,
        certUrl: String,
        topicArn: String = "arn:aws:sns:us-east-1:123456789012:test-topic",
        subscribeUrl: String = "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=...",
        token: String = "test-token-12345"
    ): SnsNotification {
        return createSignedNotification(
            type = "SubscriptionConfirmation",
            message = "You have chosen to subscribe to the topic $topicArn. " +
                "To confirm the subscription, visit the SubscribeURL included in this message.",
            privateKey = privateKey,
            certUrl = certUrl,
            topicArn = topicArn,
            subscribeUrl = subscribeUrl,
            token = token
        )
    }
}
