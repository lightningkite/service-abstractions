package com.lightningkite.services.email.imap

import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailEnvelope
import com.lightningkite.services.email.ReceivedAttachment
import com.lightningkite.services.email.ReceivedEmail
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Private wire format for IMAP -> webhook delivery.
 *
 * [ReceivedEmail] is intentionally not @Serializable because its [ReceivedAttachment.content]
 * is a streaming [Data] that can't generically roundtrip via JSON. This module pays the cost
 * of converting in and out of a serializable shape so the public type stays honest. Attachment
 * bytes ride on the wire as separate multipart file parts; this envelope only carries metadata.
 */
@Serializable
internal data class ImapWebhookEnvelope(
    val messageId: String,
    val from: WireAddress,
    val to: List<WireAddress>,
    val cc: List<WireAddress> = emptyList(),
    val replyTo: WireAddress? = null,
    val subject: String,
    val html: String? = null,
    val plainText: String? = null,
    val receivedAt: Instant,
    val headers: Map<String, List<String>> = emptyMap(),
    val attachments: List<WireAttachmentMeta> = emptyList(),
    val envelope: EmailEnvelope? = null,
    val spamScore: Double? = null,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
)

@Serializable
internal data class WireAddress(val value: String, val label: String? = null)

@Serializable
internal data class WireAttachmentMeta(
    val filename: String,
    val contentType: String,  // MediaType.toString(), kept as a plain String to avoid coupling the wire shape to the MediaType serializer.
    val size: Long = -1,
    val contentId: String? = null,
    val contentUrl: String? = null,
)

internal fun EmailAddressWithName.toWire(): WireAddress = WireAddress(value.raw, label)

internal fun WireAddress.toEmailAddressWithName(): EmailAddressWithName =
    EmailAddressWithName(value.toEmailAddress(), label)

/** Drops [ReceivedAttachment.content] — attachment bytes ride on the wire as separate parts. */
internal fun ReceivedEmail.toWire(): ImapWebhookEnvelope = ImapWebhookEnvelope(
    messageId = messageId,
    from = from.toWire(),
    to = to.map { it.toWire() },
    cc = cc.map { it.toWire() },
    replyTo = replyTo?.toWire(),
    subject = subject,
    html = html,
    plainText = plainText,
    receivedAt = receivedAt,
    headers = headers,
    attachments = attachments.map {
        WireAttachmentMeta(
            filename = it.filename,
            contentType = it.contentType.toString(),
            size = it.size,
            contentId = it.contentId,
            contentUrl = it.contentUrl,
        )
    },
    envelope = envelope,
    spamScore = spamScore,
    inReplyTo = inReplyTo,
    references = references,
)

/**
 * Reconstructs a [ReceivedEmail] from a metadata envelope and the per-attachment content payloads.
 *
 * [attachmentContent] must be aligned positionally with [ImapWebhookEnvelope.attachments]: index N
 * supplies the [Data] for `attachments[N]`. Pass `null` for any attachment that arrived without a
 * file part (e.g. metadata-only references that rely on `contentUrl`).
 */
internal fun ImapWebhookEnvelope.toReceivedEmail(attachmentContent: List<Data?>): ReceivedEmail {
    require(attachmentContent.size == attachments.size) {
        "Expected ${attachments.size} attachment payload slots but got ${attachmentContent.size}"
    }
    return ReceivedEmail(
        messageId = messageId,
        from = from.toEmailAddressWithName(),
        to = to.map { it.toEmailAddressWithName() },
        cc = cc.map { it.toEmailAddressWithName() },
        replyTo = replyTo?.toEmailAddressWithName(),
        subject = subject,
        html = html,
        plainText = plainText,
        receivedAt = receivedAt,
        headers = headers,
        attachments = attachments.mapIndexed { i, meta ->
            ReceivedAttachment(
                filename = meta.filename,
                contentType = MediaType(meta.contentType),
                size = meta.size,
                contentId = meta.contentId,
                content = attachmentContent[i],
                contentUrl = meta.contentUrl,
            )
        },
        envelope = envelope,
        spamScore = spamScore,
        inReplyTo = inReplyTo,
        references = references,
    )
}
