package com.lightningkite.services.email.sendgrid

import com.lightningkite.EmailAddress
import com.lightningkite.services.Untested
import com.lightningkite.MediaType
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.email.*
import com.lightningkite.toEmailAddress
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

private val logger = KotlinLogging.logger("SendGridEmailInboundService")

/**
 * SendGrid Inbound Parse implementation of [EmailInboundService].
 *
 * This service processes incoming emails received via SendGrid's Inbound Parse webhook.
 * SendGrid posts emails to your webhook URL as `multipart/form-data`.
 *
 * ## Configuration
 *
 * Configure via [EmailInboundService.Settings]:
 *
 * ```kotlin
 * val inboundEmail = EmailInboundService.Settings("sendgrid-inbound://")
 * ```
 *
 * ## SendGrid Setup
 *
 * 1. Configure Inbound Parse in SendGrid dashboard
 * 2. Set webhook URL to your endpoint (e.g., `https://example.com/webhooks/email`)
 * 3. Optionally enable spam check and raw email
 *
 * ## Webhook Format
 *
 * SendGrid sends emails as `multipart/form-data` with these fields:
 * - `from`: Sender email address (e.g., "John Doe <john@example.com>")
 * - `to`: Primary recipients
 * - `cc`: CC recipients
 * - `subject`: Email subject
 * - `text`: Plain text body
 * - `html`: HTML body
 * - `envelope`: JSON object with SMTP envelope data
 * - `charsets`: JSON object with character set info
 * - `headers`: Raw email headers (line-separated)
 * - `spam_score`: Spam score if enabled (0-10)
 * - `spam_report`: Detailed spam report if enabled
 * - Attachments: File parts with original names
 *
 * ## Security
 *
 * Webhook signature verification using ECDSA (P-256 curve):
 * - If a verification key is provided, all webhooks MUST have valid signatures
 * - Signatures use the `X-Twilio-Email-Event-Webhook-Signature` and
 *   `X-Twilio-Email-Event-Webhook-Timestamp` headers
 * - The signature is computed over: `timestamp + raw_body`
 * - Timestamp must be within 5 minutes to prevent replay attacks
 *
 * To obtain your public verification key:
 * 1. Go to SendGrid Settings > Mail Settings > Event Webhooks
 * 2. Click edit on your webhook, go to Security features
 * 3. Copy the public verification key (PEM format without headers)
 *
 * @see EmailInboundService
 * @see ReceivedEmail
 */
@Untested
public class SendGridEmailInboundService(
    override val name: String,
    override val context: SettingContext,
    private val verificationKey: String,
) : EmailInboundService {

    private val tracer: Tracer? = context.openTelemetry?.getTracer("email-inbound-sendgrid")

    public companion object {
        private const val SIGNATURE_HEADER = "x-twilio-email-event-webhook-signature"
        private const val TIMESTAMP_HEADER = "x-twilio-email-event-webhook-timestamp"
        private const val MAX_TIMESTAMP_AGE_MS = 5 * 60 * 1000L // 5 minutes

        init {
            EmailInboundService.Settings.register("sendgrid") { name, url, context ->
                val uri = java.net.URI(url)
                // Extract verification key from userInfo (before @)
                val verificationKey = uri.userInfo?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException(
                        "SendGrid verification key is required. " +
                        "URL format: sendgrid://VERIFICATION_KEY@ " +
                        "Get your public verification key from SendGrid Settings > Mail Settings > Event Webhooks > Security features"
                    )
                SendGridEmailInboundService(name, context, verificationKey)
            }
        }

        /**
         * Creates settings for SendGrid Inbound Parse email service.
         *
         * SendGrid Inbound Parse must be configured manually in the SendGrid dashboard.
         * Point your webhook URL to your application's email webhook endpoint.
         *
         * @param verificationKey ECDSA public key for webhook signature verification (REQUIRED).
         *   This is the Base64-encoded public key from SendGrid's webhook security settings.
         *   To obtain: Go to SendGrid Settings > Mail Settings > Event Webhooks > Security features
         * @return Settings configured for SendGrid inbound email
         */
        public fun EmailInboundService.Settings.Companion.sendgrid(
            verificationKey: String
        ): EmailInboundService.Settings {
            return EmailInboundService.Settings(
                "sendgrid://${java.net.URLEncoder.encode(verificationKey, "UTF-8")}@"
            )
        }
    }

    override val onReceived: WebhookSubservice<ReceivedEmail> = object : WebhookSubservice<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {
            logger.info { "[$name] Webhook URL should be configured in SendGrid dashboard: $httpUrl" }
            logger.warn { "[$name] Automatic webhook configuration is not supported. Configure manually in SendGrid." }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): ReceivedEmail {
            val span = tracer?.spanBuilder("email.webhook.parse")
                ?.setSpanKind(SpanKind.SERVER)
                ?.setAttribute("email.webhook.operation", "inbound_parse")
                ?.setAttribute("email.provider", "sendgrid")
                ?.startSpan()

            return try {
                val scope = span?.makeCurrent()
                try {
                    logger.debug { "[$name] Parsing SendGrid webhook" }

                    // Get raw body bytes BEFORE any parsing (required for signature verification)
                    val rawBodyBytes = body.data.bytes()

                    // Verify signature (required for all webhooks)
                    verifySignature(headers, rawBodyBytes, verificationKey)

                    span?.setAttribute("email.webhook.signature_verified", true)

                    // Verify content type
                    if (!body.mediaType.accepts(MediaType.MultiPart.FormData)) {
                        throw IllegalArgumentException(
                            "Expected multipart/form-data but got ${body.mediaType}. " +
                            "Ensure SendGrid Inbound Parse is configured correctly."
                        )
                    }

                    // Parse multipart form data
                    val boundary = body.mediaType.parameters["boundary"]
                        ?: throw IllegalArgumentException("Missing boundary parameter in Content-Type")

                    val parts = parseMultipartFormData(rawBodyBytes, boundary)

                    val receivedEmail = parseReceivedEmail(parts)

                    // Add email-specific attributes
                    span?.setAttribute("email.from", receivedEmail.from.value.raw)
                    span?.setAttribute("email.to", receivedEmail.to.joinToString(",") { it.value.raw })
                    span?.setAttribute("email.subject", receivedEmail.subject)
                    span?.setAttribute("email.attachments_count", receivedEmail.attachments.size.toLong())
                    span?.setAttribute("email.message_id", receivedEmail.messageId)
                    receivedEmail.spamScore?.let { span?.setAttribute("email.spam_score", it) }

                    span?.setStatus(StatusCode.OK)
                    receivedEmail
                } finally {
                    scope?.close()
                }
            } catch (e: Exception) {
                span?.setStatus(StatusCode.ERROR, "Failed to parse webhook: ${e.message}")
                span?.recordException(e)
                throw e
            } finally {
                span?.end()
            }
        }

        override suspend fun onSchedule() {
            // SendGrid Inbound Parse is webhook-only, no polling needed
            logger.debug { "[$name] onSchedule called (no-op for SendGrid webhook-based service)" }
        }
    }

    /**
     * Parses multipart/form-data into a map of field names to parts.
     */
    private fun parseMultipartFormData(data: ByteArray, boundary: String): Map<String, List<MultipartPart>> {
        val parts = mutableMapOf<String, MutableList<MultipartPart>>()
        val boundaryBytes = "--$boundary".toByteArray()
        val endBoundaryBytes = "--$boundary--".toByteArray()

        var position = 0

        // Find first boundary
        position = findBoundary(data, boundaryBytes, position) ?: return emptyMap()
        position += boundaryBytes.size + 2 // Skip boundary and CRLF

        while (position < data.size) {
            // Check for end boundary
            if (data.size >= position + endBoundaryBytes.size &&
                data.sliceArray(position until position + endBoundaryBytes.size)
                    .contentEquals(endBoundaryBytes)) {
                break
            }

            // Parse part headers
            val headersEnd = findSequence(data, "\r\n\r\n".toByteArray(), position)
                ?: break

            val headersSection = String(data.sliceArray(position until headersEnd))
            val partHeaders = parseHeaders(headersSection)

            // Parse Content-Disposition to get field name and filename
            val contentDisposition = partHeaders["content-disposition"] ?: ""
            val fieldName = extractQuotedValue(contentDisposition, "name") ?: "unknown"
            val filename = extractQuotedValue(contentDisposition, "filename")
            val contentType = partHeaders["content-type"] ?: "text/plain"

            // Find part body (ends at next boundary)
            val bodyStart = headersEnd + 4 // Skip \r\n\r\n
            val bodyEnd = findBoundary(data, boundaryBytes, bodyStart) ?: data.size

            // Remove trailing CRLF before boundary
            val actualBodyEnd = if (bodyEnd >= 2 &&
                data[bodyEnd - 2] == '\r'.code.toByte() &&
                data[bodyEnd - 1] == '\n'.code.toByte()) {
                bodyEnd - 2
            } else {
                bodyEnd
            }

            val bodyData = data.sliceArray(bodyStart until actualBodyEnd)

            val part = MultipartPart(
                name = fieldName,
                filename = filename,
                contentType = contentType,
                data = bodyData
            )

            parts.getOrPut(fieldName) { mutableListOf() }.add(part)

            // Move to next part
            position = bodyEnd + boundaryBytes.size + 2 // Skip boundary and CRLF
        }

        return parts
    }

    /**
     * Finds the position of a boundary in the data.
     */
    private fun findBoundary(data: ByteArray, boundary: ByteArray, startPos: Int): Int? {
        for (i in startPos..data.size - boundary.size) {
            if (data.sliceArray(i until i + boundary.size).contentEquals(boundary)) {
                return i
            }
        }
        return null
    }

    /**
     * Finds a byte sequence in the data.
     */
    private fun findSequence(data: ByteArray, sequence: ByteArray, startPos: Int): Int? {
        for (i in startPos..data.size - sequence.size) {
            if (data.sliceArray(i until i + sequence.size).contentEquals(sequence)) {
                return i
            }
        }
        return null
    }

    /**
     * Parses headers section into a map.
     */
    private fun parseHeaders(headersSection: String): Map<String, String> {
        return headersSection.lines()
            .filter { it.contains(":") }
            .associate { line ->
                val (name, value) = line.split(":", limit = 2)
                name.trim().lowercase() to value.trim()
            }
    }

    /**
     * Extracts a quoted value from a header (e.g., name="value").
     */
    private fun extractQuotedValue(header: String, key: String): String? {
        val regex = Regex("""$key="([^"]+)"""")
        return regex.find(header)?.groupValues?.getOrNull(1)
    }

    /**
     * Parses SendGrid multipart parts into a ReceivedEmail.
     */
    private fun parseReceivedEmail(parts: Map<String, List<MultipartPart>>): ReceivedEmail {
        // Extract basic fields
        val from = parts["from"]?.firstOrNull()?.dataAsString()?.let { parseEmailAddress(it) }
            ?: throw IllegalArgumentException("Missing 'from' field in SendGrid webhook")

        val to = parts["to"]?.firstOrNull()?.dataAsString()?.let { parseEmailAddressList(it) }
            ?: emptyList()

        val cc = parts["cc"]?.firstOrNull()?.dataAsString()?.let { parseEmailAddressList(it) }
            ?: emptyList()

        val subject = parts["subject"]?.firstOrNull()?.dataAsString() ?: ""

        val plainText = parts["text"]?.firstOrNull()?.dataAsString()
        val html = parts["html"]?.firstOrNull()?.dataAsString()

        // Parse envelope JSON
        val envelope = parts["envelope"]?.firstOrNull()?.dataAsString()?.let { parseEnvelope(it) }

        // Parse headers
        val headersText = parts["headers"]?.firstOrNull()?.dataAsString() ?: ""
        val emailHeaders = parseEmailHeaders(headersText)

        // Extract spam score
        val spamScore = parts["spam_score"]?.firstOrNull()?.dataAsString()?.toDoubleOrNull()

        // Extract Message-ID from headers
        val messageId = emailHeaders["message-id"]?.firstOrNull()
            ?: emailHeaders["x-message-id"]?.firstOrNull()
            ?: java.util.UUID.randomUUID().toString()

        // Extract threading headers
        val inReplyTo = emailHeaders["in-reply-to"]?.firstOrNull()
        val references = emailHeaders["references"]?.firstOrNull()?.split(Regex("\\s+")) ?: emptyList()

        // Parse attachments (any file part that isn't a standard field)
        val standardFields = setOf("from", "to", "cc", "subject", "text", "html", "envelope",
            "charsets", "headers", "spam_score", "spam_report", "dkim", "SPF")

        val attachments = parts.entries
            .filter { (name, _) -> name !in standardFields }
            .flatMap { (_, partList) ->
                partList.mapNotNull { part ->
                    part.filename?.let { filename ->
                        ReceivedAttachment(
                            filename = filename,
                            contentType = MediaType(part.contentType),
                            size = part.data.size.toLong(),
                            contentId = null,
                            content = Data.Bytes(part.data),
                            contentUrl = null
                        )
                    }
                }
            }

        // Also check for attachment* fields
        val explicitAttachments = parts.entries
            .filter { (name, _) -> name.startsWith("attachment") }
            .flatMap { (_, partList) ->
                partList.mapNotNull { part ->
                    part.filename?.let { filename ->
                        ReceivedAttachment(
                            filename = filename,
                            contentType = MediaType(part.contentType),
                            size = part.data.size.toLong(),
                            contentId = null,
                            content = Data.Bytes(part.data),
                            contentUrl = null
                        )
                    }
                }
            }

        return ReceivedEmail(
            messageId = messageId.trim('<', '>'),
            from = from,
            to = to,
            cc = cc,
            replyTo = null,  // SendGrid doesn't expose Reply-To directly in webhook
            subject = subject,
            html = html,
            plainText = plainText,
            receivedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            headers = emailHeaders,
            attachments = (attachments + explicitAttachments).distinctBy { it.filename },
            envelope = envelope,
            spamScore = spamScore,
            inReplyTo = inReplyTo,
            references = references
        )
    }

    /**
     * Parses envelope JSON from SendGrid.
     */
    private fun parseEnvelope(envelopeJson: String): EmailEnvelope? {
        return try {
            val json = Json.parseToJsonElement(envelopeJson).jsonObject
            val from = json["from"]?.jsonPrimitive?.content?.toEmailAddress()
            val to = json["to"]?.jsonArray?.map { it.jsonPrimitive.content.toEmailAddress() }

            if (from != null && to != null) {
                EmailEnvelope(from = from, to = to)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "[$name] Failed to parse envelope JSON: $envelopeJson" }
            null
        }
    }

    /**
     * Parses email headers from SendGrid's headers field.
     * Format: "Header-Name: value\nAnother-Header: value"
     */
    private fun parseEmailHeaders(headersText: String): Map<String, List<String>> {
        val headers = mutableMapOf<String, MutableList<String>>()

        headersText.lines().forEach { line ->
            if (line.contains(":")) {
                val (name, value) = line.split(":", limit = 2)
                headers.getOrPut(name.trim().lowercase()) { mutableListOf() }.add(value.trim())
            }
        }

        return headers
    }

    /**
     * Parses a single email address with optional name.
     * Format: "John Doe <john@example.com>" or "john@example.com"
     */
    private fun parseEmailAddress(address: String): EmailAddressWithName {
        val trimmed = address.trim()

        return when {
            trimmed.contains("<") && trimmed.contains(">") -> {
                val name = trimmed.substringBefore("<").trim().trim('"')
                val email = trimmed.substringAfter("<").substringBefore(">").trim()
                EmailAddressWithName(email.toEmailAddress(), name.ifEmpty { null })
            }
            else -> EmailAddressWithName(trimmed.toEmailAddress())
        }
    }

    /**
     * Parses a comma-separated list of email addresses.
     */
    private fun parseEmailAddressList(addresses: String): List<EmailAddressWithName> {
        if (addresses.isBlank()) return emptyList()

        return addresses.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseEmailAddress(it) }
    }

    /**
     * Verifies the SendGrid webhook signature using ECDSA with P-256 curve.
     *
     * SendGrid signs webhooks with: ECDSA(SHA256(timestamp + rawBody))
     *
     * @param headers HTTP headers from the webhook request
     * @param rawBody Raw body bytes (must not be parsed/modified)
     * @param publicKeyBase64 Base64-encoded ECDSA public key from SendGrid
     * @throws SecurityException if signature is invalid, missing, or timestamp is too old
     */
    private fun verifySignature(
        headers: Map<String, List<String>>,
        rawBody: ByteArray,
        publicKeyBase64: String
    ) {
        // Get signature and timestamp from headers (case-insensitive)
        val headersLower = headers.mapKeys { it.key.lowercase() }

        val signatureBase64 = headersLower[SIGNATURE_HEADER]?.firstOrNull()
            ?: throw SecurityException("Missing $SIGNATURE_HEADER header in SendGrid webhook")

        val timestampStr = headersLower[TIMESTAMP_HEADER]?.firstOrNull()
            ?: throw SecurityException("Missing $TIMESTAMP_HEADER header in SendGrid webhook")

        // Validate timestamp to prevent replay attacks
        val timestamp = timestampStr.toLongOrNull()
            ?: throw SecurityException("Invalid timestamp format in SendGrid webhook: $timestampStr")

        val now = System.currentTimeMillis() / 1000
        val age = now - timestamp
        if (age > MAX_TIMESTAMP_AGE_MS / 1000 || age < -60) { // Allow 60s clock skew
            throw SecurityException("SendGrid webhook timestamp too old or in future: ${age}s")
        }

        // Construct the payload to verify: timestamp + rawBody
        val payloadToSign = timestampStr.toByteArray(Charsets.UTF_8) + rawBody

        // Decode the public key
        val publicKey = try {
            // Remove PEM headers if present
            val keyStr = publicKeyBase64
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()

            val keyBytes = Base64.getDecoder().decode(keyStr)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            throw SecurityException("Failed to parse SendGrid public key: ${e.message}", e)
        }

        // Decode the signature
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (e: Exception) {
            throw SecurityException("Failed to decode SendGrid signature: ${e.message}", e)
        }

        // Verify signature using SHA256withECDSA (P-256 curve)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update(payloadToSign)

        if (!signature.verify(signatureBytes)) {
            throw SecurityException("Invalid SendGrid webhook signature")
        }

        logger.debug { "[$name] SendGrid webhook signature verified successfully" }
    }

    /**
     * Represents a part of multipart/form-data.
     */
    private data class MultipartPart(
        val name: String,
        val filename: String?,
        val contentType: String,
        val data: ByteArray
    ) {
        fun dataAsString(): String = String(data)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as MultipartPart

            if (name != other.name) return false
            if (filename != other.filename) return false
            if (contentType != other.contentType) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + (filename?.hashCode() ?: 0)
            result = 31 * result + contentType.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}
