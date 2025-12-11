package com.lightningkite.services.email.mailgun

import com.lightningkite.EmailAddress
import com.lightningkite.services.Untested
import com.lightningkite.MediaType
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailEnvelope
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.ReceivedAttachment
import com.lightningkite.services.email.ReceivedEmail
import com.lightningkite.toEmailAddress
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger("MailgunEmailInboundService")

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
 * @property context Service context containing SerializersModule, OpenTelemetry, etc.
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
            domain: String? = null
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

    override val onReceived: WebhookSubservice<ReceivedEmail> = object : WebhookSubservice<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {
            logger.info { "[$name] Webhook URL configured: $httpUrl" }
            logger.info { "[$name] Configure this URL in Mailgun dashboard: Receiving > Routes" }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): ReceivedEmail {
            // Parse form data from body
            val formData = parseFormData(body)

            // Verify signature (required for all webhooks)
            verifySignature(formData, apiKey)

            // Parse email fields
            return parseMailgunEmail(formData, body.mediaType)
        }

        override suspend fun onSchedule() {
            // Mailgun is webhook-only, no polling needed
            logger.debug { "[$name] onSchedule called (no-op for webhook-based Mailgun)" }
        }
    }

    override suspend fun connect() {
        logger.info { "[$name] Mailgun inbound service connected (webhook-based, no persistent connection)" }
    }

    override suspend fun disconnect() {
        logger.info { "[$name] Mailgun inbound service disconnected" }
    }

    /**
     * Parses form-urlencoded data from the request body.
     */
    private fun parseFormData(body: TypedData): Map<String, List<String>> {
        val contentType = body.mediaType.toString()

        return when {
            contentType.startsWith("application/x-www-form-urlencoded", ignoreCase = true) -> {
                parseUrlEncoded(body.data.text())
            }
            contentType.startsWith("multipart/form-data", ignoreCase = true) -> {
                // For multipart, we'll parse the simpler fields first
                // Attachments will be handled separately if needed
                parseMultipartFormData(body)
            }
            else -> {
                logger.warn { "[$name] Unexpected content type: $contentType, attempting urlencoded parsing" }
                parseUrlEncoded(body.data.text())
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
     * Parses multipart form data.
     * Note: This is a simplified parser. For production use with attachments,
     * consider using a proper multipart parser library.
     */
    private fun parseMultipartFormData(body: TypedData): Map<String, List<String>> {
        // For now, we'll parse the text fields only
        // A full implementation would parse attachments as well
        val result = mutableMapOf<String, MutableList<String>>()

        val contentType = body.mediaType.toString()
        val boundary = contentType.substringAfter("boundary=", "").trim().removeSurrounding("\"")

        if (boundary.isEmpty()) {
            logger.warn { "[$name] No boundary found in multipart content-type" }
            return emptyMap()
        }

        val bodyText = body.data.text()
        val parts = bodyText.split("--$boundary")

        for (part in parts) {
            if (part.trim().isEmpty() || part.trim() == "--") continue

            // Split headers and content
            val sections = part.split("\r\n\r\n", "\n\n", limit = 2)
            if (sections.size != 2) continue

            val headersText = sections[0]
            val content = sections[1].trim()

            // Parse Content-Disposition header to get field name
            val dispositionLine = headersText.lines().find {
                it.trim().startsWith("Content-Disposition:", ignoreCase = true)
            }

            if (dispositionLine != null) {
                val nameMatch = Regex("""name="([^"]+)"""").find(dispositionLine)
                val name = nameMatch?.groupValues?.get(1)

                if (name != null && !name.startsWith("attachment-")) {
                    // Only process non-attachment fields for now
                    result.getOrPut(name) { mutableListOf() }.add(content.trimEnd('-', '\r', '\n'))
                }
            }
        }

        return result
    }

    /**
     * Verifies Mailgun webhook signature.
     * Mailgun signs webhooks with HMAC-SHA256(timestamp + token).
     */
    private fun verifySignature(formData: Map<String, List<String>>, apiKey: String) {
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

        // Compute expected signature
        val data = "$timestamp$token"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKey = javax.crypto.spec.SecretKeySpec(apiKey.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val expectedSignature = mac.doFinal(data.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

        if (signature.lowercase() != expectedSignature.lowercase()) {
            throw SecurityException("Invalid Mailgun webhook signature")
        }

        logger.debug { "[$name] Webhook signature verified successfully" }
    }

    /**
     * Parses Mailgun form data into ReceivedEmail.
     */
    private fun parseMailgunEmail(formData: Map<String, List<String>>, contentType: MediaType): ReceivedEmail {
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

        // Parse attachments (basic support)
        val attachments = parseAttachments(formData)

        return ReceivedEmail(
            messageId = messageId,
            from = from,
            to = to,
            cc = cc,
            replyTo = parseEmailAddress(formData["Reply-To"]?.firstOrNull() ?: ""),
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
     * Parses attachment metadata from form data.
     * Note: Actual attachment content is not parsed in this basic implementation.
     * In production, you'd want to handle multipart file uploads properly.
     */
    private fun parseAttachments(formData: Map<String, List<String>>): List<ReceivedAttachment> {
        val attachmentCount = formData["attachment-count"]?.firstOrNull()?.toIntOrNull() ?: 0
        if (attachmentCount == 0) return emptyList()

        // In a real implementation, you'd parse the actual attachment files from multipart data
        // For now, we'll just return empty list
        logger.debug { "[$name] Email has $attachmentCount attachments (parsing not fully implemented)" }
        return emptyList()
    }
}
