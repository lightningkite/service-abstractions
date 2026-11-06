package com.lightningkite.services.email.ses

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.SettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.get
import com.lightningkite.services.data.*
import com.lightningkite.services.email.*
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.webhooksubservice.HttpAdapter
import com.lightningkite.services.webhooksubservice.WebhookAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.net.URI
import java.net.URL
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
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
 * ses://?topicArn=<arn>[&bucket=<bucket>&region=<region>]
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
 * - **150KB SNS limit**: SES bounces emails larger than 150KB when the receipt rule uses an SNS
 *   action. For larger emails (up to 40MB), the receipt rule uses an S3 action that stores the
 *   raw email in a bucket and notifies SNS without content; configure that bucket with
 *   `ses://?topicArn=<arn>&bucket=<bucket>&region=<region>` (see `awsSesInbound(storeInS3 = true)`)
 *   and the email is fetched from it.
 * - **Webhook security**: All SNS messages are verified using AWS's X.509 certificate-based
 *   signature verification. The signing certificate URL must be from amazonaws.com. SNS signs
 *   every topic's messages with the same certificate, so messages from any topic but [topicArn]
 *   are rejected; otherwise anyone could subscribe the webhook to their own topic and forge emails.
 * - **Idempotency**: SES may send duplicate notifications; handle by Message-ID
 *
 * @property name Service instance name for logging and identification
 * @property context Service context providing configuration and shared resources
 * @property topicArn The SNS topic SES publishes to; the only topic whose messages are accepted
 * @property emailBucket Where the receipt rule's S3 action stores raw emails; null if emails only arrive inline via SNS
 */
public class SesEmailInboundService(
    override val name: String,
    override val context: SettingContext,
    private val topicArn: String,
    private val emailBucket: EmailBucket? = null,
) : EmailInboundService {

    public class EmailBucket(public val name: String, public val s3: S3Client)

    public companion object {
        init {
            EmailInboundService.Settings.register("ses") { name, url, context ->
                val params = url.substringAfter("?", "").split("&").filter { it.isNotEmpty() }
                    .associate { it.substringBefore("=") to it.substringAfter("=") }
                val topicArn = params["topicArn"] ?: throw IllegalArgumentException(
                    "ses:// settings need the SNS topic SES publishes to, so messages from other topics can be rejected: " +
                            "ses://?topicArn=<arn>"
                )
                val emailBucket = params["bucket"]?.let { bucket ->
                    val region = params["region"]
                        ?: throw IllegalArgumentException("ses:// settings with a bucket need a region too: ses://?topicArn=<arn>&bucket=<bucket>&region=<region>")
                    EmailBucket(
                        name = bucket,
                        s3 = S3Client.builder()
                            .region(Region.of(region))
                            .httpClient(context[AwsConnections].client)
                            .overrideConfiguration(context[AwsConnections].clientOverrideConfiguration)
                            .build()
                    )
                }
                SesEmailInboundService(name, context, topicArn, emailBucket)
            }
        }

        /**
         * Settings for emails delivered inline to [topicArn] by an SNS receipt action (150KB max; SES bounces larger ones).
         */
        public fun EmailInboundService.Settings.Companion.ses(topicArn: String): EmailInboundService.Settings =
            EmailInboundService.Settings("ses://?topicArn=$topicArn")

        /**
         * Settings for emails stored in [bucket] by an S3 receipt action (40MB max), which notifies [topicArn] without content.
         */
        public fun EmailInboundService.Settings.Companion.ses(topicArn: String, bucket: String, region: Region): EmailInboundService.Settings =
            EmailInboundService.Settings("ses://?topicArn=$topicArn&bucket=$bucket&region=${region.id()}")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Cache of downloaded and validated X.509 certificates keyed by URL.
     * This avoids re-downloading certificates for each message.
     */
    internal val certificateCache: ConcurrentHashMap<String, X509Certificate> = ConcurrentHashMap()

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

    override val onReceived: WebhookAdapter<ReceivedEmail> = object : WebhookAdapter<ReceivedEmail> {

        override suspend fun configureWebhook(httpUrl: String) {
            logger.info { "[$name] Webhook URL configured: $httpUrl" }
            logger.warn { "[$name] Automatic webhook configuration not supported for SES. Configure SNS topic subscription manually." }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): ReceivedEmail {
            logger.debug { "[$name] Parsing SES webhook notification" }

            // SNS subscription/unsubscribe confirmations produce a SpecialCaseException carrying a 200
            // response. That is a normal outcome, not a failure, so it must be thrown OUTSIDE
            // telemetryTrace — otherwise the trace would record it as an error outcome. The trace body
            // returns it as a value and we rethrow once the span has closed cleanly.
            val result = telemetryTrace("webhook.parse", attributes = TelemetryAttributes {
                put(TelemetryKeys.Messaging.system, "ses")
            }) { _ ->
                // Parse SNS notification wrapper
                val bodyString = body.text()
                val snsNotification = try {
                    json.decodeFromString<SnsNotification>(bodyString)
                } catch (e: Exception) {
                    logger.error(e) { "[$name] Failed to parse SNS notification" }
                    throw IllegalArgumentException("Invalid SNS notification format", e)
                }

                // Verify SNS message signature (REQUIRED for all message types)
                telemetryTrace("sns.verify", attributes = TelemetryAttributes {
                    put(TelemetryKeys.Messaging.system, "ses")
                }) { _ ->
                    verifySnsSignature(snsNotification)
                }

                // TopicArn is covered by the signature, so after verification it can be trusted.
                if (snsNotification.TopicArn != topicArn) throw SecurityException(
                    "SNS message is from topic '${snsNotification.TopicArn}', not the configured topic '$topicArn'"
                )

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
                    return@telemetryTrace HttpAdapter.SpecialCaseException(
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
                    return@telemetryTrace HttpAdapter.SpecialCaseException(
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

                val action = sesNotification.receipt.action
                val rawMime: ByteArray = when {
                    sesNotification.content != null -> sesNotification.content.toByteArray(Charsets.UTF_8)
                    action?.type == "S3" -> {
                        val bucket = emailBucket ?: throw IllegalStateException(
                            "SES stored the email in S3 bucket '${action.bucketName}', but no bucket is configured. " +
                                    "Use ses://?bucket=<bucket>&region=<region>."
                        )
                        // Any SNS topic can deliver a validly signed message here, so the notification
                        // must not be able to make us read some other object our role has access to.
                        if (action.bucketName != bucket.name) throw SecurityException(
                            "SES notification names bucket '${action.bucketName}', but the configured bucket is '${bucket.name}'"
                        )
                        val key = action.objectKey
                            ?: throw IllegalArgumentException("SES S3 action notification has no objectKey")
                        telemetryTrace("s3.getObject", attributes = TelemetryAttributes {
                            put(TelemetryKeys.Messaging.system, "ses")
                        }) { _ ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    bucket.s3.getObjectAsBytes { it.bucket(bucket.name).key(key) }.asByteArray()
                                } catch (e: NoSuchKeyException) {
                                    throw IllegalStateException("Email s3://${bucket.name}/$key not found", e)
                                }
                            }
                        }
                    }
                    else -> throw UnsupportedOperationException(
                        "SES notification has no email content and its receipt action is '${action?.type}', not 'S3'. " +
                                "Use an SNS action (inline content, 150KB max) or an S3 action with an SNS topic."
                    )
                }

                telemetryTrace("mime.parse", attributes = TelemetryAttributes {
                    put(TelemetryKeys.Messaging.system, "ses")
                }) { _ ->
                    parseReceivedEmail(sesNotification, rawMime)
                }
            }
            when (result) {
                is HttpAdapter.SpecialCaseException -> throw result
                is ReceivedEmail -> return result
                else -> error("Unreachable: telemetryTrace returned ${result::class}")
            }
        }

        override suspend fun pull(): Set<ReceivedEmail> {
            logger.debug { "[$name] pull called (no-op; SES delivers via webhook only)" }
            return emptySet()
        }
    }

    /**
     * Parse a received email from SES notification and raw MIME content.
     */
    private fun parseReceivedEmail(notification: SesNotification, rawContent: ByteArray): ReceivedEmail {
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

        fun authVerdict(verdict: SesVerdict?): EmailAuthVerdict? = when (verdict?.status) {
            null -> null
            "PASS" -> EmailAuthVerdict.Pass
            "FAIL" -> EmailAuthVerdict.Fail
            "GRAY" -> EmailAuthVerdict.Gray
            "PROCESSING_FAILED" -> EmailAuthVerdict.ProcessingFailed
            else -> {
                logger.warn { "[$name] Unrecognized SES auth verdict '${verdict.status}'; treating as unverified" }
                null
            }
        }
        val authentication = EmailAuthentication(
            spf = authVerdict(receipt.spfVerdict),
            dkim = authVerdict(receipt.dkimVerdict),
            dmarc = authVerdict(receipt.dmarcVerdict),
        )

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
            references = references,
            authentication = authentication,
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
    internal suspend fun verifySnsSignature(notification: SnsNotification) {
        // Validate the certificate URL
        validateCertificateUrl(notification.SigningCertURL)

        // Get the certificate (from cache or download)
        val certificate = getCertificate(notification.SigningCertURL)

        // Verify the leaf certificate identity matches AWS's documented SNS signing identity
        if (!certificate.subjectX500Principal.name.contains("CN=sns.amazonaws.com", ignoreCase = true)) {
            throw SecurityException("SNS certificate does not match expected SNS signing identity")
        }

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

        // Enforce a replay window: reject messages whose timestamp is too far from now
        val messageTimestamp = try {
            java.time.Instant.parse(notification.Timestamp)
        } catch (e: Exception) {
            throw SecurityException("SNS message timestamp out of acceptable range", e)
        }
        val skewMillis = kotlin.math.abs(java.time.Instant.now().toEpochMilli() - messageTimestamp.toEpochMilli())
        if (skewMillis > 1.hours.inWholeMilliseconds) {
            throw SecurityException("SNS message timestamp out of acceptable range")
        }

        logger.debug { "[$name] SNS message signature verified successfully" }
    }

    /**
     * Validates that the certificate URL is from an official AWS SNS endpoint.
     * This prevents attackers from hosting their own malicious certificates.
     */
    internal fun validateCertificateUrl(certUrl: String) {
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

        // Path must match the expected SNS signing certificate filename pattern
        if (!Regex("^/SimpleNotificationService-[a-z0-9]+\\.pem$").matches(uri.path)) {
            throw SecurityException("SigningCertURL must point to a .pem file: $certUrl")
        }
    }

    /**
     * Downloads and parses an X.509 certificate from the given URL.
     * Certificates are cached to avoid redundant downloads.
     * Uses computeIfAbsent for atomic single-init, IO dispatcher for the blocking download,
     * and a 10s timeout to avoid hanging on slow AWS endpoints.
     */
    private suspend fun getCertificate(certUrl: String): X509Certificate {
        // Return from cache without suspension if already present
        certificateCache[certUrl]?.let { return it }

        return withTimeout(10.seconds) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                certificateCache.computeIfAbsent(certUrl) { url ->
                    logger.debug { "[$name] Downloading SNS signing certificate from: $url" }

                    val certBytes = try {
                        URL(url).openStream().use { it.readBytes() }
                    } catch (e: Exception) {
                        throw SecurityException("Failed to download SNS certificate from $url: ${e.message}", e)
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
                notification.Type == "UnsubscribeConfirmation"
            ) {
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
                notification.Type == "UnsubscribeConfirmation"
            ) {
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
    private suspend fun autoConfirmSubscription(subscribeUrl: String): Boolean {
        return try {
            // Validate URL is from AWS
            val uri = URI(subscribeUrl)
            val host = uri.host?.lowercase() ?: return false

            if (validCertHostPatterns.none { it.matches(host) }) {
                logger.warn { "[$name] SubscribeURL is not from an SNS endpoint ($host), skipping auto-confirm for security" }
                return false
            }

            logger.debug { "[$name] Auto-confirming SNS subscription via: $subscribeUrl" }

            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val connection = URL(subscribeUrl).openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.getInputStream().use { it.readBytes() }
            }

            true
        } catch (e: Exception) {
            logger.error(e) { "[$name] Failed to auto-confirm SNS subscription" }
            false
        }
    }
}
