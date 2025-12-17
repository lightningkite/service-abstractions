package com.lightningkite.services.email

import com.lightningkite.EmailAddress
import com.lightningkite.MediaType
import com.lightningkite.services.data.Data
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Represents an email received via inbound email processing.
 *
 * This data class captures all relevant information from an incoming email,
 * including envelope data (SMTP-level), headers, body content, and attachments.
 *
 * ## Envelope vs Headers
 *
 * The [envelope] contains SMTP-level recipient information, which may differ from
 * the `To`/`Cc` headers visible in the email. For example, BCC recipients appear
 * in the envelope but not in headers.
 *
 * ## Usage
 *
 * ```kotlin
 * val inboundService: EmailInboundService = ...
 *
 * // Parse from webhook
 * val email = inboundService.onReceived.parseWebhook(queryParams, headers, body)
 *
 * println("From: ${email.from}")
 * println("Subject: ${email.subject}")
 * println("Body: ${email.plainText ?: email.html}")
 *
 * // Process attachments
 * email.attachments.forEach { attachment ->
 *     println("Attachment: ${attachment.filename} (${attachment.contentType})")
 * }
 * ```
 *
 * @property messageId Unique identifier assigned by the email provider or extracted from Message-ID header
 * @property from Sender email address with optional display name
 * @property to Primary recipients from the To header
 * @property cc Carbon copy recipients from the Cc header
 * @property replyTo Reply-To address if different from sender
 * @property subject Email subject line
 * @property html HTML body content (null if not present)
 * @property plainText Plain text body content (null if not present)
 * @property receivedAt Timestamp when the email was received by the inbound service
 * @property headers All email headers as received
 * @property attachments File attachments
 * @property envelope SMTP envelope information (may differ from headers)
 * @property spamScore Spam score if provided by the email service (provider-specific scale)
 * @property inReplyTo Message-ID of the email this is replying to (for threading)
 * @property references List of Message-IDs in the email thread (for threading)
 */
public data class ReceivedEmail(
    val messageId: String,
    val from: EmailAddressWithName,
    val to: List<EmailAddressWithName>,
    val cc: List<EmailAddressWithName> = emptyList(),
    val replyTo: EmailAddressWithName? = null,
    val subject: String,
    val html: String? = null,
    val plainText: String? = null,
    val receivedAt: Instant,
    val headers: Map<String, List<String>> = emptyMap(),
    val attachments: List<ReceivedAttachment> = emptyList(),
    val envelope: EmailEnvelope? = null,
    val spamScore: Double? = null,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
)

/**
 * SMTP envelope information for a received email.
 *
 * The envelope represents the actual SMTP transaction data, which may differ
 * from the email headers. This is particularly important for:
 * - BCC recipients (appear in envelope but not headers)
 * - Forwarded emails (envelope sender differs from From header)
 * - Mailing lists (envelope may contain list address)
 *
 * @property from The SMTP MAIL FROM address (envelope sender)
 * @property to The SMTP RCPT TO addresses (envelope recipients)
 */
@Serializable
public data class EmailEnvelope(
    val from: EmailAddress,
    val to: List<EmailAddress>,
)

/**
 * Represents an attachment from a received email.
 *
 * Attachments may be provided inline (for small files) or as a URL to fetch
 * (for large files, depending on the provider).
 *
 * ## Content Access
 *
 * - **Small attachments**: [content] is populated directly with the file data
 * - **Large attachments**: [contentUrl] provides a URL to fetch the content
 * - **Both may be null**: If the provider doesn't include attachment content in webhooks
 *
 * ## Usage
 *
 * ```kotlin
 * attachment.content?.use { data ->
 *     val bytes = data.bytes()
 *     // Process attachment content
 * }
 *
 * // Or fetch from URL if content is not inline
 * attachment.contentUrl?.let { url ->
 *     // Fetch attachment from URL
 * }
 * ```
 *
 * @property filename Original filename of the attachment
 * @property contentType MIME type of the attachment (e.g., "application/pdf")
 * @property size Size in bytes (-1 if unknown)
 * @property contentId Content-ID for inline attachments (used in HTML img src="cid:...")
 * @property content Attachment data if provided inline (may be single-use, always close)
 * @property contentUrl URL to fetch attachment content (for large files or deferred loading)
 */
public data class ReceivedAttachment(
    val filename: String,
    val contentType: MediaType,
    val size: Long = -1,
    val contentId: String? = null,
    val content: Data? = null,
    val contentUrl: String? = null,
) : AutoCloseable {
    override fun close() {
        content?.close()
    }
}
