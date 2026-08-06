@file:OptIn(Untested::class)

package com.lightningkite.services.email.mailgun

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.Untested
import com.lightningkite.services.data.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mailgun's inbound webhook reports `attachment-count` accurately but the previous parser
 * unconditionally returned `emptyList()` for attachments — callers saw "N attachments" and got
 * none. The hand-rolled text-splitting parser it replaced also corrupted binary data by round
 * tripping the whole body through `String`.
 *
 * This test goes directly through the internal multipart parser (bypassing signature
 * verification, same pattern as [MailgunVerifySignatureTest]) and posts a binary payload that
 * contains a byte sequence which is invalid UTF-8 — round-tripping it through `String(bytes)` /
 * `.toByteArray()` (what the old parser did) corrupts those bytes via the U+FFFD replacement
 * character, so an exact byte match here proves the parser never decodes attachment bytes as text.
 */
class MailgunAttachmentTest {

    private val service = MailgunEmailInboundService(
        name = "test",
        context = TestSettingContext(),
        apiKey = "key-test",
        domain = "example.com",
    )

    /** Builds a minimal multipart/form-data body from (name, filename, contentType, bytes) parts. */
    private fun buildMultipart(boundary: String, parts: List<Quadruple>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val crlf = "\r\n".toByteArray()
        for ((fieldName, filename, contentType, bytes) in parts) {
            out.write("--$boundary".toByteArray())
            out.write(crlf)
            val disposition = if (filename != null) {
                "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\""
            } else {
                "Content-Disposition: form-data; name=\"$fieldName\""
            }
            out.write(disposition.toByteArray())
            out.write(crlf)
            out.write("Content-Type: $contentType".toByteArray())
            out.write(crlf)
            out.write(crlf)
            out.write(bytes)
            out.write(crlf)
        }
        out.write("--$boundary--".toByteArray())
        out.write(crlf)
        return out.toByteArray()
    }

    private data class Quadruple(
        val fieldName: String,
        val filename: String?,
        val contentType: String,
        val bytes: ByteArray,
    )

    @Test
    fun binaryAttachmentRoundTripsWithCorrectBytesFilenameAndContentType() = runTest {
        val boundary = "----MailgunTestBoundary987"

        // Bytes crafted to break naive text-based splitting: a lone continuation byte (0x80) and a
        // lone leading byte (0xFF) are both invalid UTF-8 on their own, so decoding+re-encoding via
        // String corrupts them to the U+FFFD replacement character (which is 3 bytes in UTF-8,
        // changing the length too). Also embeds a CR/LF pair mid-payload to stress boundary scanning.
        val binaryContent = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
            0x80.toByte(), 0xFF.toByte(), 0x00, 0x01, 0x02, 0xFE.toByte(),
        )

        val rawBody = buildMultipart(
            boundary,
            listOf(
                Quadruple("from", null, "text/plain", "sender@example.com".toByteArray()),
                Quadruple("To", null, "text/plain", "recipient@example.com".toByteArray()),
                Quadruple("subject", null, "text/plain", "Binary attachment test".toByteArray()),
                Quadruple("attachment-count", null, "text/plain", "1".toByteArray()),
                Quadruple("attachment-1", "photo.png", "image/png", binaryContent),
            ),
        )

        val body = com.lightningkite.services.data.TypedData.bytes(
            rawBody,
            MediaType.MultiPart.FormData.copy(parameters = mapOf("boundary" to boundary)),
        )

        val parsed = service.parseMultipartFormData(body)
        val email = service.parseMailgunEmail(parsed.fields, body.mediaType, parsed.attachmentParts)

        assertEquals(1, email.attachments.size)
        val attachment = email.attachments.first()
        assertEquals("photo.png", attachment.filename)
        assertEquals(MediaType.Image.PNG.toString(), attachment.contentType.toString())
        assertEquals(binaryContent.size.toLong(), attachment.size)

        val roundTrippedBytes = attachment.content!!.bytes()
        assertTrue(
            binaryContent.contentEquals(roundTrippedBytes),
            "Expected exact byte match, got ${roundTrippedBytes.toList()} vs ${binaryContent.toList()}",
        )
    }
}
