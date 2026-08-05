// by Claude
package com.lightningkite.services.email

import kotlin.test.*

/**
 * `Email.customHeaders` and `Email.Attachment.filename` both end up on the wire as raw SMTP
 * header lines (see JavaSmtpEmailService.toJavaX, which calls MimeMessage.addHeader directly for
 * both). Unlike subject/display-name, that path does NOT go through JavaMail's
 * MimeUtility.fold()/makesafe() RFC-822 folding, so an embedded CR/LF is written verbatim and lets
 * an attacker inject an arbitrary extra header (e.g. a real "Bcc:" line). Reject it at
 * construction time so no implementation can ship this hole.
 */
class EmailHeaderInjectionTest {

    @Test
    fun customHeaderValueWithCRLFIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Email(
                subject = "Test",
                to = listOf(EmailAddressWithName("user@example.com")),
                plainText = "body",
                customHeaders = mapOf("X-Custom" to listOf("value\r\nBcc: attacker@evil.com"))
            )
        }
    }

    @Test
    fun customHeaderNameWithCRLFIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Email(
                subject = "Test",
                to = listOf(EmailAddressWithName("user@example.com")),
                plainText = "body",
                customHeaders = mapOf("X-Custom\r\nBcc: attacker@evil.com" to listOf("value"))
            )
        }
    }

    @Test
    fun customHeaderWithoutCRLFIsAccepted() {
        val email = Email(
            subject = "Test",
            to = listOf(EmailAddressWithName("user@example.com")),
            plainText = "body",
            customHeaders = mapOf("X-Custom" to listOf("a normal value"))
        )
        assertEquals(listOf("a normal value"), email.customHeaders["X-Custom"])
    }

    @Test
    fun attachmentFilenameWithCRLFIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Email.Attachment(
                inline = false,
                filename = "evil.txt\r\nContent-Type: text/html",
                typedData = com.lightningkite.services.data.TypedData.text(
                    "x",
                    com.lightningkite.services.data.MediaType.Text.Plain
                )
            )
        }
    }

    @Test
    fun attachmentFilenameWithoutCRLFIsAccepted() {
        val attachment = Email.Attachment(
            inline = false,
            filename = "invoice.pdf",
            typedData = com.lightningkite.services.data.TypedData.text(
                "x",
                com.lightningkite.services.data.MediaType.Text.Plain
            )
        )
        assertEquals("invoice.pdf", attachment.filename)
    }

    /** email.copy(...) re-invokes the data class constructor, so the guard must hold across copy() too. */
    @Test
    fun copyWithInjectedCustomHeaderIsRejected() {
        val email = Email(
            subject = "Test",
            to = listOf(EmailAddressWithName("user@example.com")),
            plainText = "body",
        )
        assertFailsWith<IllegalArgumentException> {
            email.copy(customHeaders = mapOf("X-Custom" to listOf("value\r\nBcc: attacker@evil.com")))
        }
    }
}
