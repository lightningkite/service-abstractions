package com.lightningkite.services.email.ses

import com.lightningkite.EmailAddress
import com.lightningkite.MediaType
import com.lightningkite.services.data.Data
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.ReceivedAttachment
import com.lightningkite.toEmailAddress
import jakarta.mail.Address
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.Properties

/**
 * Utilities for parsing MIME messages using Jakarta Mail.
 */
internal object MimeParser {

    /**
     * Parse a raw MIME message into a MimeMessage object.
     */
    fun parseRawMime(rawContent: String): MimeMessage {
        val session = Session.getInstance(Properties())
        val inputStream = ByteArrayInputStream(rawContent.toByteArray(Charsets.UTF_8))
        return MimeMessage(session, inputStream)
    }

    /**
     * Extract plain text body from a MIME message.
     */
    fun extractPlainText(message: MimeMessage): String? {
        return extractTextPart(message, "text/plain")
    }

    /**
     * Extract HTML body from a MIME message.
     */
    fun extractHtml(message: MimeMessage): String? {
        return extractTextPart(message, "text/html")
    }

    /**
     * Extract a text part with the specified content type.
     */
    private fun extractTextPart(part: Part, contentType: String): String? {
        val content = part.content

        if (part.isMimeType(contentType)) {
            return content as? String
        }

        if (content is Multipart) {
            for (i in 0 until content.count) {
                val subPart = content.getBodyPart(i)
                val text = extractTextPart(subPart, contentType)
                if (text != null) return text
            }
        }

        return null
    }

    /**
     * Extract all attachments from a MIME message.
     */
    fun extractAttachments(message: MimeMessage): List<ReceivedAttachment> {
        val attachments = mutableListOf<ReceivedAttachment>()
        extractAttachmentsFromPart(message, attachments)
        return attachments
    }

    private fun extractAttachmentsFromPart(part: Part, attachments: MutableList<ReceivedAttachment>) {
        val content = part.content

        if (content is Multipart) {
            for (i in 0 until content.count) {
                extractAttachmentsFromPart(content.getBodyPart(i), attachments)
            }
        } else {
            val disposition = part.disposition
            if (Part.ATTACHMENT.equals(disposition, ignoreCase = true) ||
                Part.INLINE.equals(disposition, ignoreCase = true) ||
                part.fileName != null) {

                val filename = part.fileName ?: "attachment_${attachments.size}"
                val contentType = MediaType(part.contentType.substringBefore(';').trim())
                val contentId = part.getHeader("Content-ID")?.firstOrNull()?.trim('<', '>')

                // Read the content as bytes
                val data = when (val partContent = part.content) {
                    is ByteArray -> partContent
                    is String -> partContent.toByteArray()
                    else -> part.inputStream.readBytes()
                }

                attachments.add(
                    ReceivedAttachment(
                        filename = filename,
                        contentType = contentType,
                        size = data.size.toLong(),
                        contentId = contentId,
                        content = Data.Bytes(data),
                        contentUrl = null
                    )
                )
            }
        }
    }

    /**
     * Parse email addresses from Jakarta Mail Address array.
     */
    fun parseAddresses(addresses: Array<Address>?): List<EmailAddressWithName> {
        return addresses?.mapNotNull { address ->
            when (address) {
                is InternetAddress -> {
                    val email = address.address
                    val name = address.personal
                    try {
                        EmailAddressWithName(email.toEmailAddress(), name)
                    } catch (e: Exception) {
                        // Invalid email address, skip
                        null
                    }
                }
                else -> null
            }
        } ?: emptyList()
    }

    /**
     * Parse a single email address from a string.
     */
    fun parseEmailAddress(addressString: String?): EmailAddressWithName? {
        if (addressString.isNullOrBlank()) return null

        return try {
            val address = InternetAddress(addressString)
            val email = address.address
            val name = address.personal
            EmailAddressWithName(email.toEmailAddress(), name)
        } catch (e: Exception) {
            // Try parsing as plain email
            try {
                EmailAddressWithName(addressString.trim().toEmailAddress())
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Parse multiple email addresses from a string array.
     */
    fun parseEmailAddresses(addresses: List<String>?): List<EmailAddressWithName> {
        return addresses?.mapNotNull { parseEmailAddress(it) } ?: emptyList()
    }

    /**
     * Extract all headers from a MIME message.
     */
    fun extractHeaders(message: MimeMessage): Map<String, List<String>> {
        val headers = mutableMapOf<String, MutableList<String>>()

        val headerEnum = message.allHeaders
        while (headerEnum.hasMoreElements()) {
            val header = headerEnum.nextElement()
            headers.getOrPut(header.name) { mutableListOf() }.add(header.value)
        }

        return headers
    }

    /**
     * Get the In-Reply-To header.
     */
    fun getInReplyTo(message: MimeMessage): String? {
        return message.getHeader("In-Reply-To")?.firstOrNull()?.trim('<', '>')
    }

    /**
     * Get the References header as a list of message IDs.
     */
    fun getReferences(message: MimeMessage): List<String> {
        val referencesHeader = message.getHeader("References")?.firstOrNull() ?: return emptyList()
        return referencesHeader.split(Regex("\\s+"))
            .map { it.trim('<', '>') }
            .filter { it.isNotBlank() }
    }
}
