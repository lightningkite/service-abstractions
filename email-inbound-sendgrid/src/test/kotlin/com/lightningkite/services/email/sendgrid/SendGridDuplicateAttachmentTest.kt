package com.lightningkite.services.email.sendgrid

import com.lightningkite.services.TestSettingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the duplicate-attachment behavior of SendGrid's multipart parser.
 *
 * When an inbound SendGrid webhook posts a multipart payload with multiple attachments that
 * share a filename (typical for inline images named e.g. "image.png" twice), the parsed list
 * must:
 * 1. Contain BOTH attachments — an earlier `distinctBy { filename }` silently dropped duplicates.
 * 2. Rename duplicates as "${index}_${filename}" so each is independently addressable.
 *
 * This test goes directly through `parseMultipartFormData` + `parseReceivedEmail` to avoid
 * signature-verification setup — both are `internal` to allow this.
 */
class SendGridDuplicateAttachmentTest {

    private val service = SendGridEmailInboundService(
        name = "test",
        context = TestSettingContext(),
        verificationKey = "unused-in-this-test",
    )

    /**
     * Builds a minimal multipart/form-data body. Each part is a Triple of
     * (form-field name, optional filename, body bytes). Filename = null means a non-file field.
     */
    private fun buildMultipart(boundary: String, parts: List<Triple<String, String?, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val crlf = "\r\n".toByteArray()
        for ((name, filename, body) in parts) {
            out.write("--$boundary".toByteArray())
            out.write(crlf)
            val disposition = if (filename != null) {
                "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\""
            } else {
                "Content-Disposition: form-data; name=\"$name\""
            }
            out.write(disposition.toByteArray())
            out.write(crlf)
            val contentType = if (filename != null) "Content-Type: image/png" else "Content-Type: text/plain"
            out.write(contentType.toByteArray())
            out.write(crlf)
            out.write(crlf) // end of part headers
            out.write(body)
            out.write(crlf)
        }
        out.write("--$boundary--".toByteArray())
        out.write(crlf)
        return out.toByteArray()
    }

    @Test
    fun duplicateAttachmentFilenamesArePreservedAndRenamed() {
        val boundary = "----TestBoundary12345"
        val firstImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01)
        val secondImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x02)
        val thirdImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x03)

        // Use field names that do NOT start with "attachment". The current parser also has an
        // explicit-attachments branch keyed on names starting with "attachment-prefix" which
        // would double-count parts. We're pinning the dedupe/rename behavior, not that quirk.
        val body = buildMultipart(
            boundary,
            listOf(
                Triple("from", null, "sender@example.com".toByteArray()),
                Triple("to", null, "recipient@example.com".toByteArray()),
                Triple("subject", null, "Duplicates".toByteArray()),
                Triple("inline1", "image.png", firstImage),
                Triple("inline2", "image.png", secondImage),
                Triple("inline3", "unique.png", thirdImage),
            ),
        )

        val parts = service.parseMultipartFormData(body, boundary)
        val email = service.parseReceivedEmail(parts)

        // Pin: both same-named attachments are kept (no silent dedupe)
        assertEquals(3, email.attachments.size, "All attachments must be preserved")

        val filenames = email.attachments.map { it.filename }.toSet()
        assertTrue("0_image.png" in filenames, "First duplicate must be prefixed with its index, got $filenames")
        assertTrue("1_image.png" in filenames, "Second duplicate must be prefixed with its index, got $filenames")
        assertTrue("unique.png" in filenames, "Unique filename must NOT be renamed, got $filenames")

        // Pin: each renamed attachment still carries its original distinct payload
        val first = email.attachments.first { it.filename == "0_image.png" }
        val second = email.attachments.first { it.filename == "1_image.png" }
        val third = email.attachments.first { it.filename == "unique.png" }
        assertEquals(firstImage.size.toLong(), first.size)
        assertEquals(secondImage.size.toLong(), second.size)
        assertEquals(thirdImage.size.toLong(), third.size)
    }
}
