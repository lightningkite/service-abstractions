package com.lightningkite.services.email.ses

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailEnvelope
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.ReceivedEmail
import com.lightningkite.toEmailAddress
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URL
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinInstant

private val logger = KotlinLogging.logger("SesEmailInboundService")

/**
 * AWS SES inbound email service implementation.
 *
 * This implementation receives emails via AWS SES inbound receipt rules delivered
 * through SNS HTTP/HTTPS notifications. It parses the SNS notification wrapper,
 * extracts the SES notification, and parses the raw MIME content into a [ReceivedEmail].
 *
 * ## AWS Setup Required
 *
 * 1. **Verify domain** in SES console
 * 2. **Create receipt rule** to deliver emails to SNS topic
 * 3. **Create SNS topic** and subscribe an HTTPS endpoint to it
 * 4. **Configure webhook URL** in your application to receive SNS notifications
 *
 * ## URL Format
 *
 * ```
 * ses-inbound://
 * ```
 *
 * Configuration is done via AWS console or infrastructure-as-code (Terraform/CloudFormation).
 *
 * ## Features
 *
 * - **SNS subscription confirmation**: Automatically handles subscription confirmation
 * - **MIME parsing**: Extracts plain text, HTML, and attachments from raw MIME
 * - **Spam/virus verdicts**: Includes SES spam and virus scan results
 * - **Email threading**: Preserves In-Reply-To and References headers
 * - **Envelope data**: Includes SMTP envelope information (important for BCC)
 *
 * ## Important Notes
 *
 * - **150KB SNS limit**: Emails larger than ~150KB may have content stored in S3 instead
 *   of inline. This implementation currently only supports inline content.
 * - **Webhook security**: All SNS messages are verified using AWS's X.509 certificate-based
 *   signature verification. The signing certificate URL must be from amazonaws.com.
 * - **Idempotency**: SES may send duplicate notifications; handle by Message-ID
 *
 * @property name Service instance name for logging and identification
 * @property context Service context providing configuration and shared resources
 */
public class SesEmailInboundService(
    override val name: String,
    override val context: SettingContext
) : EmailInboundService {

    public companion object {
        init {
            EmailInboundService.Settings.register("ses") { name, _, context ->
                SesEmailInboundService(name, context)
            }
        }

        /**
         * Creates settings for AWS SES inbound email service.
         *
         * AWS SES receives emails via SNS notifications. Infrastructure must be configured
         * separately (via Terraform, CloudFormation, or AWS Console).
         *
         * @return Settings configured for SES inbound email
         */
        public fun EmailInboundService.Settings.Companion.ses(): EmailInboundService.Settings =
            EmailInboundService.Settings("ses://")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Cache of downloaded and validated X.509 certificates keyed by URL.
     * This avoids re-downloading certificates for each message.
     */
    private val certificateCache = ConcurrentHashMap<String, X509Certificate>()

    /**
     * Valid hostnames for SNS signing certificate URLs.
     * The certificate MUST come from an official AWS SNS endpoint.
     */
    private val validCertHostPatterns = listOf(
        Regex("""^sns\.[a-z0-9-]+\.amazonaws\.com$"""),
        Regex("""^sns\.amazonaws\.com$""")
    )

    init {
        logger.info { "[$name] AWS SES inbound email service initialized" }
    }

    override val healthCheckFrequency: Duration = 6.hours

    override suspend fun healthCheck(): HealthStatus {
        // For webhook-based service, just verify we're configured
        logger.debug { "[$name] Health check: OK (webhook-based service)" }
        return HealthStatus(HealthStatus.Level.OK)
    }

    override suspend fun connect() {
        logger.info { "[$name] Connect called (no-op for webhook-based service)" }
    }

    override suspend fun disconnect() {
        logger.info { "[$name] Disconnect called (no-op for webhook-based service)" }
    }

    override val onReceived: WebhookSubservice<ReceivedEmail> = object : WebhookSubservice<ReceivedEmail> {

        override suspend fun configureWebhook(httpUrl: String) {
            logger.info { "[$name] Webhook URL configured: $httpUrl" }
            logger.warn { "[$name] Automatic webhook configuration not supported for SES. Configure SNS topic subscription manually." }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): ReceivedEmail {
            logger.debug { "[$name] Parsing SES webhook notification" }

            // Parse SNS notification wrapper
            val bodyString = body.text()
            val snsNotification = try {
                json.decodeFromString<SnsNotification>(bodyString)
            } catch (e: Exception) {
                logger.error(e) { "[$name] Failed to parse SNS notification" }
                throw IllegalArgumentException("Invalid SNS notification format", e)
            }

            // Verify SNS message signature (REQUIRED for all message types)
            verifySnsSignature(snsNotification)

            // Handle subscription confirmation - auto-confirm and return success response
            if (snsNotification.Type == "SubscriptionConfirmation") {
                val subscribeUrl = snsNotification.SubscribeURL
                val topicArn = snsNotification.TopicArn ?: "unknown"
                logger.info { "[$name] Received SNS subscription confirmation for topic: $topicArn" }

                if (subscribeUrl != null) {
                    val confirmed = autoConfirmSubscription(subscribeUrl)
                    if (confirmed) {
                        logger.info { "[$name] Successfully auto-confirmed SNS subscription for topic: $topicArn" }
                    } else {
                        logger.warn { "[$name] Failed to auto-confirm SNS subscription. Manual confirmation required: $subscribeUrl" }
                    }
                }

                // Return 200 OK so SNS knows we received the confirmation
                throw HttpAdapter.SpecialCaseException(
                    HttpAdapter.HttpResponseLike(
                        status = 200,
                        headers = mapOf("Content-Type" to listOf("text/plain")),
                        body = TypedData.text("Subscription confirmed", MediaType.Text.Plain)
                    )
                )
            }

            // Handle unsubscribe confirmation
            if (snsNotification.Type == "UnsubscribeConfirmation") {
                logger.info { "[$name] Received SNS unsubscribe confirmation" }
                throw HttpAdapter.SpecialCaseException(
                    HttpAdapter.HttpResponseLike(
                        status = 200,
                        headers = mapOf("Content-Type" to listOf("text/plain")),
                        body = TypedData.text("Unsubscribe confirmed", MediaType.Text.Plain)
                    )
                )
            }

            // Parse SES notification from Message field
            val sesNotification = try {
                json.decodeFromString<SesNotification>(snsNotification.Message)
            } catch (e: Exception) {
                logger.error(e) { "[$name] Failed to parse SES notification from SNS message" }
                throw IllegalArgumentException("Invalid SES notification format", e)
            }

            // Check notification type
            if (sesNotification.notificationType != "Received") {
                throw UnsupportedOperationException(
                    "Unsupported SES notification type: ${sesNotification.notificationType}"
                )
            }

            // Check if content is available inline
            val rawContent = sesNotification.content
                ?: throw UnsupportedOperationException(
                    "Email content not included in notification (likely stored in S3). " +
                    "S3-based content retrieval is not yet implemented."
                )

            return parseReceivedEmail(sesNotification, rawContent)
        }

        override suspend fun onSchedule() {
            logger.debug { "[$name] onSchedule called (no-op for webhook-based service)" }
        }
    }

    /**
     * Parse a received email from SES notification and raw MIME content.
     */
    private fun parseReceivedEmail(notification: SesNotification, rawContent: String): ReceivedEmail {
        val mail = notification.mail
        val receipt = notification.receipt

        // Parse MIME message
        val mimeMessage = MimeParser.parseRawMime(rawContent)

        // Extract basic fields
        val messageId = mail.commonHeaders.messageId ?: mail.messageId
        val subject = mail.commonHeaders.subject ?: mimeMessage.subject ?: ""
        val plainText = MimeParser.extractPlainText(mimeMessage)
        val html = MimeParser.extractHtml(mimeMessage)
        val attachments = MimeParser.extractAttachments(mimeMessage)

        // Parse sender - use From header, fallback to envelope
        val from = mail.commonHeaders.from?.firstOrNull()?.let { MimeParser.parseEmailAddress(it) }
            ?: MimeParser.parseEmailAddress(mail.source)
            ?: throw IllegalArgumentException("No sender address found in email")

        // Parse recipients - prefer headers, fallback to envelope
        val to = MimeParser.parseEmailAddresses(mail.commonHeaders.to).takeIf { it.isNotEmpty() }
            ?: MimeParser.parseEmailAddresses(mail.destination)

        val cc = MimeParser.parseEmailAddresses(mail.commonHeaders.cc)

        // Reply-To handling
        val replyToHeader = mimeMessage.getHeader("Reply-To")?.firstOrNull()
        val replyTo = replyToHeader?.let { MimeParser.parseEmailAddress(it) }

        // Parse timestamp
        val receivedAt = try {
            java.time.Instant.parse(mail.timestamp).toKotlinInstant()
        } catch (e: Exception) {
            logger.warn(e) { "[$name] Failed to parse timestamp: ${mail.timestamp}" }
            kotlin.time.Instant.DISTANT_PAST
        }

        // Build headers map
        val headers = MimeParser.extractHeaders(mimeMessage)

        // Extract threading headers
        val inReplyTo = MimeParser.getInReplyTo(mimeMessage)
        val references = MimeParser.getReferences(mimeMessage)

        // Build envelope
        val envelope = EmailEnvelope(
            from = mail.source.toEmailAddress(),
            to = mail.destination.map { it.toEmailAddress() }
        )

        // Calculate spam score from verdicts
        // SES doesn't provide a numeric score, so we convert PASS/FAIL to binary
        val spamScore = when (receipt.spamVerdict.status) {
            "PASS" -> 0.0
            "FAIL" -> 10.0
            "GRAY" -> 5.0
            else -> null
        }

        logger.info { "[$name] Parsed email: messageId=$messageId, from=${from.value}, subject=$subject" }

        return ReceivedEmail(
            messageId = messageId,
            from = from,
            to = to,
            cc = cc,
            replyTo = replyTo,
            subject = subject,
            html = html,
            plainText = plainText,
            receivedAt = receivedAt,
            headers = headers,
            attachments = attachments,
            envelope = envelope,
            spamScore = spamScore,
            inReplyTo = inReplyTo,
            references = references
        )
    }

    /**
     * Verifies the SNS message signature.
     *
     * AWS SNS uses X.509 certificate-based signatures. The verification process:
     * 1. Validate the SigningCertURL points to an official AWS SNS endpoint
     * 2. Download and cache the X.509 certificate
     * 3. Verify the certificate is valid and not expired
     * 4. Construct the string to sign based on message type
     * 5. Verify the signature using the certificate's public key
     *
     * @param notification The SNS notification to verify
     * @throws SecurityException if signature verification fails
     */
    private fun verifySnsSignature(notification: SnsNotification) {
        // Validate the certificate URL
        validateCertificateUrl(notification.SigningCertURL)

        // Get the certificate (from cache or download)
        val certificate = getCertificate(notification.SigningCertURL)

        // Construct the string to sign based on message type
        val stringToSign = buildStringToSign(notification)

        // Decode the signature
        val signatureBytes = try {
            Base64.getDecoder().decode(notification.Signature)
        } catch (e: Exception) {
            throw SecurityException("Failed to decode SNS signature: ${e.message}", e)
        }

        // Determine the signature algorithm based on SignatureVersion
        val algorithm = when (notification.SignatureVersion) {
            "1" -> "SHA1withRSA"
            "2" -> "SHA256withRSA"
            else -> throw SecurityException("Unsupported SNS SignatureVersion: ${notification.SignatureVersion}")
        }

        // Verify the signature
        val signature = Signature.getInstance(algorithm)
        signature.initVerify(certificate.publicKey)
        signature.update(stringToSign.toByteArray(Charsets.UTF_8))

        if (!signature.verify(signatureBytes)) {
            throw SecurityException("Invalid SNS message signature")
        }

        logger.debug { "[$name] SNS message signature verified successfully" }
    }

    /**
     * Validates that the certificate URL is from an official AWS SNS endpoint.
     * This prevents attackers from hosting their own malicious certificates.
     */
    private fun validateCertificateUrl(certUrl: String) {
        val uri = try {
            URI(certUrl)
        } catch (e: Exception) {
            throw SecurityException("Invalid SigningCertURL: $certUrl", e)
        }

        // Must be HTTPS
        if (uri.scheme?.lowercase() != "https") {
            throw SecurityException("SigningCertURL must use HTTPS: $certUrl")
        }

        // Host must be an official AWS SNS endpoint
        val host = uri.host?.lowercase()
            ?: throw SecurityException("SigningCertURL has no host: $certUrl")

        val isValidHost = validCertHostPatterns.any { pattern -> pattern.matches(host) }
        if (!isValidHost) {
            throw SecurityException("SigningCertURL host is not a valid SNS endpoint: $host")
        }

        // Path must end with .pem
        if (!uri.path.endsWith(".pem")) {
            throw SecurityException("SigningCertURL must point to a .pem file: $certUrl")
        }
    }

    /**
     * Downloads and parses an X.509 certificate from the given URL.
     * Certificates are cached to avoid redundant downloads.
     */
    private fun getCertificate(certUrl: String): X509Certificate {
        return certificateCache.getOrPut(certUrl) {
            logger.debug { "[$name] Downloading SNS signing certificate from: $certUrl" }

            val certBytes = try {
                URL(certUrl).openStream().use { it.readBytes() }
            } catch (e: Exception) {
                throw SecurityException("Failed to download SNS certificate from $certUrl: ${e.message}", e)
            }

            val certificate = try {
                val factory = CertificateFactory.getInstance("X.509")
                factory.generateCertificate(certBytes.inputStream()) as X509Certificate
            } catch (e: Exception) {
                throw SecurityException("Failed to parse SNS certificate: ${e.message}", e)
            }

            // Verify the certificate is currently valid
            try {
                certificate.checkValidity()
            } catch (e: Exception) {
                throw SecurityException("SNS certificate is not valid (expired or not yet valid): ${e.message}", e)
            }

            certificate
        }
    }

    /**
     * Builds the string to sign for SNS signature verification.
     *
     * The string format is: "KeyName\nKeyValue\n" for each field in alphabetical order.
     * Different message types include different fields.
     */
    private fun buildStringToSign(notification: SnsNotification): String {
        return buildString {
            // Fields must be in byte-sort (alphabetical) order
            // Field names and values are separated by newlines

            append("Message\n")
            append(notification.Message)
            append("\n")

            append("MessageId\n")
            append(notification.MessageId)
            append("\n")

            // For SubscriptionConfirmation and UnsubscribeConfirmation, include SubscribeURL
            if (notification.Type == "SubscriptionConfirmation" ||
                notification.Type == "UnsubscribeConfirmation") {
                if (notification.SubscribeURL != null) {
                    append("SubscribeURL\n")
                    append(notification.SubscribeURL)
                    append("\n")
                }
            }

            // For Notification messages, include Subject if present
            if (notification.Type == "Notification" && notification.Subject != null) {
                append("Subject\n")
                append(notification.Subject)
                append("\n")
            }

            append("Timestamp\n")
            append(notification.Timestamp)
            append("\n")

            // For SubscriptionConfirmation and UnsubscribeConfirmation, include Token
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
     * Automatically confirms an SNS subscription by fetching the SubscribeURL.
     *
     * This is safe because we've already verified the SNS signature, which proves
     * the message came from AWS SNS. The SubscribeURL is also validated to ensure
     * it points to an amazonaws.com domain.
     *
     * @param subscribeUrl The URL to fetch to confirm the subscription
     * @return true if confirmation was successful, false otherwise
     */
    private fun autoConfirmSubscription(subscribeUrl: String): Boolean {
        return try {
            // Validate URL is from AWS
            val uri = URI(subscribeUrl)
            val host = uri.host?.lowercase() ?: return false

            if (!host.endsWith(".amazonaws.com")) {
                logger.warn { "[$name] SubscribeURL is not from amazonaws.com ($host), skipping auto-confirm for security" }
                return false
            }

            logger.debug { "[$name] Auto-confirming SNS subscription via: $subscribeUrl" }

            val connection = URL(subscribeUrl).openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.getInputStream().use { it.readBytes() }

            true
        } catch (e: Exception) {
            logger.error(e) { "[$name] Failed to auto-confirm SNS subscription" }
            false
        }
    }
}
