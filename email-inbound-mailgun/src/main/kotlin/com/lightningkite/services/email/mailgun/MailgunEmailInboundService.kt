package com.lightningkite.services.email.mailgun

import com.lightningkite.services.*
import com.lightningkite.services.data.*
import com.lightningkite.services.email.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.webhooksubservice.WebhookAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger("MailgunEmailInboundService")

// The blank line (CRLF CRLF) that separates a multipart part's headers from its body per RFC 2046.
private val HEADER_BODY_SEPARATOR = "\r\n\r\n".toByteArray()

/**
 * Mailgun implementation of EmailInboundService for receiving inbound emails via webhooks.
 *
 * Mailgun sends inbound emails to your webhook endpoint as HTTP POST requests with
 * form-urlencoded or multipart/form-data payloads.
 *
 * ## Configuration
 *
 * ```kotlin
 * val inboundService = EmailInboundService.Settings("mailgun-inbound://api-key@domain")
 * ```
 *
 * URL format: `mailgun-inbound://[api-key@]domain`
 * - `api-key`: Optional Mailgun API key for webhook signature verification
 * - `domain`: Your Mailgun domain (not used for inbound, but kept for consistency)
 *
 * ## Webhook Setup
 *
 * 1. In Mailgun dashboard, go to Receiving > Routes
 * 2. Create a route that forwards to your webhook URL
 * 3. Example: `match_recipient(".*@inbound.yourdomain.com")` → forward to `https://api.yourdomain.com/webhooks/mailgun/inbound`
 *
 * ## Security
 *
 * Webhook signatures are verified using Mailgun's HMAC-SHA256 signature scheme.
 * The API key is REQUIRED for signature verification - all webhooks without valid
 * signatures will be rejected.
 *
 * ## Mailgun Webhook Format
 *
 * Mailgun sends the following fields (form-urlencoded or multipart):
 * - `sender`: SMTP envelope sender (e.g., "user@example.com")
 * - `recipient`: SMTP envelope recipient
 * - `from`: From header with optional display name (e.g., "John Doe <john@example.com>")
 * - `To`: Comma-separated To addresses
 * - `Cc`: Comma-separated Cc addresses
 * - `subject`: Email subject
 * - `body-plain`: Plain text body
 * - `body-html`: HTML body
 * - `stripped-text`: Plain text without quoted parts
 * - `stripped-html`: HTML without quoted parts
 * - `Message-Id`: Unique message identifier
 * - `Message-headers`: JSON array of all headers as [name, value] pairs
 * - `timestamp`: Unix timestamp for signature verification
 * - `token`: Random token for signature verification
 * - `signature`: HMAC-SHA256 signature
 * - `attachment-count`: Number of attachments
 * - `attachment-N`: Attachment file (multipart only, N = 1, 2, 3...)
 *
 * @property name Service instance name
 * @property context Service context containing SerializersModule, metrics backend, etc.
 * @property apiKey Mailgun API key for signature verification (REQUIRED)
 * @property domain Mailgun domain (not used for inbound parsing)
 */
@Untested
public class MailgunEmailInboundService(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val domain: String = "",
) : EmailInboundService {

    public companion object {
        init {
            EmailInboundService.Settings.Companion.register("mailgun") { name, url, context ->
                val uri = java.net.URI(url)
                val userInfo = uri.userInfo?.split(":")
                val apiKey = userInfo?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException(
                        "Mailgun API key is required. " +
                                "URL format: mailgun://API_KEY@domain " +
                                "Get your API key from Mailgun dashboard > API Keys"
                    )
                val domain = uri.host ?: ""
                MailgunEmailInboundService(name, context, apiKey, domain)
            }
        }

        /**
         * Creates settings for Mailgun inbound email service.
         *
         * @param apiKey Mailgun API key for webhook signature verification (REQUIRED).
         *   Get your API key from Mailgun dashboard > API Keys.
         * @param domain Mailgun domain (for documentation/identification purposes).
         * @return Settings configured for Mailgun inbound email
         */
        public fun EmailInboundService.Settings.Companion.mailgun(
            apiKey: String,
            domain: String? = null,
        ): EmailInboundService.Settings {
            val url = buildString {
                append("mailgun://")
                append(apiKey)
                append("@")
                append(domain ?: "")
            }
            return EmailInboundService.Settings(url)
        }
    }

    override val onReceived: WebhookAdapter<ReceivedEmail> = object : WebhookAdapter<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {
            logger.info { "[$name] Webhook URL configured: $httpUrl" }
            logger.info { "[$name] Configure this URL in Mailgun dashboard: Receiving > Routes" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): ReceivedEmail = telemetryTrace("webhook.parse", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("email.operation"), "webhook_parse")
            put(TelemetryKey.OfString("email.provider"), "mailgun")
            put(TelemetryKeys.Messaging.system, "mailgun")
            put(TelemetryKey.OfString("email.webhook.event_type"), "inbound")
        }) { span ->
            // Parse form data from body
            val parsedBody = parseFormData(body)

            // Verify signature (required for all webhooks)
            verifySignature(parsedBody.fields, apiKey)

            // Parse email fields
            val receivedEmail = parseMailgunEmail(parsedBody.fields, body.mediaType, parsedBody.attachmentParts)

            // Add email metadata to span (PII redacted: keep only domain for from/to, drop subject)
            span.enrich(TelemetryAttributes {
                put(
                    TelemetryKey.OfString("email.from"),
                    receivedEmail.from.value.toString().let { addr -> addr.substringAfter('@', addr) })
                put(
                    TelemetryKey.OfString("email.to"),
                    receivedEmail.to.joinToString(", ") {
                        it.value.toString().let { addr -> addr.substringAfter('@', addr) }
                    })
                put(TelemetryKey.OfString("email.message_id"), receivedEmail.messageId)
                if (receivedEmail.attachments.isNotEmpty()) {
                    put(TelemetryKey.OfLong("email.attachments.count"), receivedEmail.attachments.size.toLong())
                }
                receivedEmail.spamScore?.let { score -> put(TelemetryKey.OfDouble("email.spam_score"), score) }
            })

            receivedEmail
        }

        override suspend fun pull(): Set<ReceivedEmail> {
            logger.debug { "[$name] pull called (no-op; Mailgun delivers via webhook only)" }
            return emptySet()
        }
    }

    override suspend fun connect() {
        logger.info { "[$name] Mailgun inbound service connected (webhook-based, no persistent connection)" }
    }

    override suspend fun disconnect() {
        logger.info { "[$name] Mailgun inbound service disconnected" }
    }

    /** Text form fields plus any file parts (attachments) extracted from the webhook body. */
    internal data class ParsedMailgunBody(
        val fields: Map<String, List<String>>,
        val attachmentParts: List<MultipartPart>,
    )

    /**
     * One part of a `multipart/form-data` body. `data` is the raw, un-decoded part payload —
     * kept as bytes throughout so binary attachments (images, PDFs, etc.) round-trip intact.
     */
    internal data class MultipartPart(
        val name: String,
        val filename: String?,
        val contentType: String,
        val data: ByteArray,
    ) {
        fun dataAsString(): String = String(data, Charsets.UTF_8)

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

    /**
     * Parses form data from the request body.
     */
    private suspend fun parseFormData(body: TypedData): ParsedMailgunBody {
        val contentType = body.mediaType.toString()

        return when {
            contentType.startsWith("application/x-www-form-urlencoded", ignoreCase = true) -> {
                ParsedMailgunBody(parseUrlEncoded(body.data.text()), emptyList())
            }

            contentType.startsWith("multipart/form-data", ignoreCase = true) -> {
                parseMultipartFormData(body)
            }

            else -> {
                logger.warn { "[$name] Unexpected content type: $contentType, attempting urlencoded parsing" }
                ParsedMailgunBody(parseUrlEncoded(body.data.text()), emptyList())
            }
        }
    }

    /**
     * Parses URL-encoded form data.
     */
    private fun parseUrlEncoded(text: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()

        if (text.isBlank()) return emptyMap()

        text.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                val value = java.net.URLDecoder.decode(parts[1], "UTF-8")
                result.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

        return result
    }

    /**
     * Parses multipart/form-data into text fields and attachment parts, operating on raw bytes
     * throughout. The previous implementation ran the whole body through `String.split`, which
     * corrupts binary attachment content (non-UTF-8 byte sequences get mangled on decode) and
     * unconditionally discarded every part named "attachment-*" — so attachments were reported
     * (via `attachment-count`) but never delivered.
     */
    internal suspend fun parseMultipartFormData(body: TypedData): ParsedMailgunBody {
        val contentType = body.mediaType.toString()
        val boundary = contentType.substringAfter("boundary=", "").trim().removeSurrounding("\"")

        if (boundary.isEmpty()) {
            logger.warn { "[$name] No boundary found in multipart content-type" }
            return ParsedMailgunBody(emptyMap(), emptyList())
        }

        val parts = parseMultipartParts(body.data.bytes(), boundary)

        // A part with a filename is an attachment; anything else is a text form field. This is
        // the same signal RFC 7578 uses to distinguish files from ordinary fields, and it doesn't
        // depend on Mailgun's "attachment-N" naming convention holding exactly.
        val fields = mutableMapOf<String, MutableList<String>>()
        val attachmentParts = mutableListOf<MultipartPart>()
        parts.forEach { part ->
            if (part.filename != null) {
                attachmentParts.add(part)
            } else {
                fields.getOrPut(part.name) { mutableListOf() }.add(part.dataAsString())
            }
        }

        return ParsedMailgunBody(fields, attachmentParts)
    }

    /** Scans a multipart/form-data byte body for its parts, splitting on the boundary bytes. */
    private fun parseMultipartParts(data: ByteArray, boundary: String): List<MultipartPart> {
        val parts = mutableListOf<MultipartPart>()
        val boundaryBytes = "--$boundary".toByteArray()
        val endBoundaryBytes = "--$boundary--".toByteArray()

        var position = findSequence(data, boundaryBytes, 0) ?: return emptyList()
        position += boundaryBytes.size + 2 // Skip boundary and CRLF

        while (position < data.size) {
            if (data.size >= position + endBoundaryBytes.size &&
                data.sliceArray(position until position + endBoundaryBytes.size).contentEquals(endBoundaryBytes)
            ) {
                break
            }

            val headersEnd = findSequence(data, HEADER_BODY_SEPARATOR, position) ?: break
            val headersSection = String(data, position, headersEnd - position, Charsets.ISO_8859_1)
            val partHeaders = parseHeaders(headersSection)

            val disposition = partHeaders["content-disposition"] ?: ""
            val fieldName = extractQuotedValue(disposition, "name") ?: "unknown"
            val filename = extractQuotedValue(disposition, "filename")
            val partContentType = partHeaders["content-type"] ?: "text/plain"

            val bodyStart = headersEnd + HEADER_BODY_SEPARATOR.size
            val bodyEnd = findSequence(data, boundaryBytes, bodyStart) ?: data.size

            // Trailing CRLF before the next boundary is part of the multipart framing, not the part body.
            val actualBodyEnd = if (bodyEnd >= 2 &&
                data[bodyEnd - 2] == '\r'.code.toByte() && data[bodyEnd - 1] == '\n'.code.toByte()
            ) bodyEnd - 2 else bodyEnd

            parts.add(
                MultipartPart(
                    name = fieldName,
                    filename = filename,
                    contentType = partContentType,
                    data = data.sliceArray(bodyStart until actualBodyEnd),
                )
            )

            position = bodyEnd + boundaryBytes.size + 2 // Skip boundary and CRLF
        }

        return parts
    }

    private fun findSequence(data: ByteArray, sequence: ByteArray, startPos: Int): Int? {
        for (i in startPos..data.size - sequence.size) {
            if (data.sliceArray(i until i + sequence.size).contentEquals(sequence)) return i
        }
        return null
    }

    private fun parseHeaders(headersSection: String): Map<String, String> {
        return headersSection.lines()
            .filter { it.contains(":") }
            .associate { line ->
                val (headerName, value) = line.split(":", limit = 2)
                headerName.trim().lowercase() to value.trim()
            }
    }

    private fun extractQuotedValue(header: String, key: String): String? {
        return Regex("""$key="([^"]+)"""").find(header)?.groupValues?.getOrNull(1)
    }

    /**
     * Verifies Mailgun webhook signature.
     * Mailgun signs webhooks with HMAC-SHA256(timestamp + token).
     */
    internal fun verifySignature(formData: Map<String, List<String>>, apiKey: String) {
        val timestamp = formData["timestamp"]?.firstOrNull()
        val token = formData["token"]?.firstOrNull()
        val signature = formData["signature"]?.firstOrNull()

        if (timestamp == null || token == null || signature == null) {
            logger.warn { "[$name] Missing signature fields in webhook" }
            throw SecurityException("Missing signature fields in Mailgun webhook")
        }

        // Check timestamp is recent (within 15 minutes)
        val timestampMs = timestamp.toLongOrNull()?.times(1000)
        if (timestampMs != null) {
            val age = System.currentTimeMillis() - timestampMs
            if (age > 15 * 60 * 1000) {
                throw SecurityException("Webhook timestamp too old: ${age.milliseconds}")
            }
        }

        // Compute expected signature.
        val data = "$timestamp$token"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKey = javax.crypto.spec.SecretKeySpec(apiKey.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val expectedBytes = mac.doFinal(data.toByteArray())

        val providedBytes = try {
            signature.hexToByteArray()
        } catch(e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Mailgun webhook signature - not proper hex", e)
        }
        if (!java.security.MessageDigest.isEqual(providedBytes, expectedBytes)) {
            throw SecurityException("Invalid Mailgun webhook signature")
        }

        logger.debug { "[$name] Webhook signature verified successfully" }
    }

    /**
     * Parses Mailgun form data into ReceivedEmail.
     */
    internal fun parseMailgunEmail(
        formData: Map<String, List<String>>,
        contentType: MediaType,
        attachmentParts: List<MultipartPart> = emptyList(),
    ): ReceivedEmail {
        val messageId = formData["Message-Id"]?.firstOrNull()
            ?: formData["message-id"]?.firstOrNull()
            ?: "mailgun-${System.currentTimeMillis()}"

        val from = parseEmailAddress(formData["from"]?.firstOrNull() ?: formData["From"]?.firstOrNull() ?: "")
        val to = parseEmailAddressList(formData["To"]?.firstOrNull() ?: formData["to"]?.firstOrNull() ?: "")
        val cc = parseEmailAddressList(formData["Cc"]?.firstOrNull() ?: formData["cc"]?.firstOrNull() ?: "")

        val subject = formData["subject"]?.firstOrNull()
            ?: formData["Subject"]?.firstOrNull()
            ?: "(No Subject)"

        val plainText = formData["stripped-text"]?.firstOrNull()
            ?: formData["body-plain"]?.firstOrNull()

        val html = formData["stripped-html"]?.firstOrNull()
            ?: formData["body-html"]?.firstOrNull()

        val receivedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis())

        // Parse envelope
        val envelopeFrom = formData["sender"]?.firstOrNull()
        val envelopeTo = formData["recipient"]?.firstOrNull()
        val envelope = if (envelopeFrom != null && envelopeTo != null) {
            EmailEnvelope(
                from = envelopeFrom.toEmailAddress(),
                to = listOf(envelopeTo.toEmailAddress())
            )
        } else null

        // Parse all headers from Message-headers JSON array
        val headers = parseMessageHeaders(formData["Message-headers"]?.firstOrNull())

        // Parse threading headers
        val inReplyTo = headers["in-reply-to"]?.firstOrNull()
            ?: headers["In-Reply-To"]?.firstOrNull()

        val references = (headers["references"]?.firstOrNull() ?: headers["References"]?.firstOrNull())
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val attachments = parseAttachments(attachmentParts)

        return ReceivedEmail(
            messageId = messageId,
            from = from,
            to = to,
            cc = cc,
            // Reply-To is genuinely optional; unlike `from` below there's no non-nullable field to
            // paper over, so an absent header must yield null rather than a fabricated address.
            replyTo = formData["Reply-To"]?.firstOrNull()?.let { parseEmailAddress(it) },
            subject = subject,
            html = html,
            plainText = plainText,
            receivedAt = receivedAt,
            headers = headers,
            attachments = attachments,
            envelope = envelope,
            spamScore = formData["X-Mailgun-Sscore"]?.firstOrNull()?.toDoubleOrNull(),
            inReplyTo = inReplyTo,
            references = references
        )
    }

    /**
     * Parses Mailgun's Message-headers JSON field.
     * Format: [["Header-Name", "value"], ["Another-Header", "value"], ...]
     */
    private fun parseMessageHeaders(messageHeadersJson: String?): Map<String, List<String>> {
        if (messageHeadersJson.isNullOrBlank()) return emptyMap()

        return try {
            val json = Json.parseToJsonElement(messageHeadersJson).jsonArray
            val result = mutableMapOf<String, MutableList<String>>()

            json.forEach { element ->
                val pair = element.jsonArray
                if (pair.size >= 2) {
                    val name = pair[0].jsonPrimitive.content
                    val value = pair[1].jsonPrimitive.content
                    result.getOrPut(name) { mutableListOf() }.add(value)
                }
            }

            result
        } catch (e: Exception) {
            logger.warn(e) { "[$name] Failed to parse Message-headers JSON" }
            emptyMap()
        }
    }

    /**
     * Parses a single email address with optional display name.
     * Format: "Display Name <email@example.com>" or "email@example.com"
     */
    private fun parseEmailAddress(address: String): EmailAddressWithName {
        if (address.isBlank()) {
            return EmailAddressWithName("unknown@example.com".toEmailAddress(), null)
        }

        val trimmed = address.trim()
        val match = Regex("""^(.+?)\s*<([^>]+)>$""").find(trimmed)

        return if (match != null) {
            val label = match.groupValues[1].trim().removeSurrounding("\"")
            val email = match.groupValues[2].trim()
            EmailAddressWithName(email.toEmailAddress(), label)
        } else {
            EmailAddressWithName(trimmed.toEmailAddress(), null)
        }
    }

    /**
     * Parses a comma-separated list of email addresses.
     */
    private fun parseEmailAddressList(addresses: String): List<EmailAddressWithName> {
        if (addresses.isBlank()) return emptyList()

        return addresses.split(",").mapNotNull { address ->
            val trimmed = address.trim()
            if (trimmed.isNotBlank()) parseEmailAddress(trimmed) else null
        }
    }

    /**
     * Builds [ReceivedAttachment]s from the file parts extracted by [parseMultipartFormData].
     * `attachmentParts` only ever contains parts that had a filename (see [parseMultipartFormData]),
     * so `filename` is always present here.
     */
    private fun parseAttachments(attachmentParts: List<MultipartPart>): List<ReceivedAttachment> {
        return attachmentParts.map { part ->
            ReceivedAttachment(
                filename = part.filename!!,
                contentType = MediaType(part.contentType.substringBefore(';').trim()),
                size = part.data.size.toLong(),
                contentId = null,
                content = Data.Bytes(part.data),
                contentUrl = null,
            )
        }
    }
}
