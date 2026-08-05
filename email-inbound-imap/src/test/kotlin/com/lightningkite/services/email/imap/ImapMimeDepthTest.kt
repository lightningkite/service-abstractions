package com.lightningkite.services.email.imap

import com.lightningkite.services.TestSettingContext
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression test for the unbounded MIME multipart recursion in
 * [ImapEmailInboundService.parseContent].
 *
 * IMAP mail is fully attacker-controlled — anyone can send an email to a mailbox this service
 * polls — and this recursive parser (independent from SES's [MimeParser]) had no depth limit
 * either, making it a stack-overflow DoS vector.
 */
class ImapMimeDepthTest {

    private val session = Session.getInstance(Properties())

    private fun service() = ImapEmailInboundService(
        name = "test",
        context = TestSettingContext(),
        host = "localhost",
        port = 143,
        username = "user",
        password = "pass",
        folder = "INBOX",
        useSsl = false,
        requireStartTls = false,
    )

    /** Builds [depth] levels of `multipart/mixed` nesting around a single leaf text part. */
    private fun buildDeeplyNestedMessage(depth: Int): MimeMessage {
        var innermost = MimeBodyPart().apply { setText("leaf content") }
        repeat(depth - 1) {
            val multipart = MimeMultipart("mixed")
            multipart.addBodyPart(innermost)
            innermost = MimeBodyPart().apply { setContent(multipart) }
        }
        val topMultipart = MimeMultipart("mixed")
        topMultipart.addBodyPart(innermost)

        val message = MimeMessage(session)
        message.setFrom(InternetAddress("sender@example.com"))
        message.setRecipient(jakarta.mail.Message.RecipientType.TO, InternetAddress("recipient@example.com"))
        message.subject = "Deep Nesting Test"
        message.setContent(topMultipart)
        message.saveChanges()
        return message
    }

    @Test
    fun parseContent_deeplyNestedMultipart_throwsInsteadOfOverflowing() {
        val message = buildDeeplyNestedMessage(depth = 100)

        assertFailsWith<IllegalArgumentException> {
            service().parseContent(message)
        }
    }
}
