package com.lightningkite.services.email.imap

import com.icegreen.greenmail.util.*
import com.lightningkite.services.TestSettingContext
import jakarta.activation.DataHandler
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for IMAP email service with GreenMail.
 *
 * Verifies the real pull() path end-to-end:
 *  1. Emails can be fetched from the IMAP server
 *  2. Plain text, HTML, attachments, and threading headers are parsed correctly
 *  3. Messages are marked SEEN so the next pull() doesn't return them
 */
class ImapPollingIntegrationTest {

    private val testContext = TestSettingContext()

    private lateinit var greenMail: GreenMail
    private val imapPort = 3145
    private val smtpPort = 3027

    init {
        ImapEmailInboundService
    }

    @BeforeTest
    fun setUp() {
        greenMail = GreenMail(
            arrayOf(
                ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP),
                ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP)
            )
        )
        greenMail.start()
        greenMail.setUser("inbox@test.local", "inbox@test.local", "password")
    }

    @AfterTest
    fun tearDown() {
        greenMail.stop()
    }

    @Test
    fun testFetchUnreadEmails() = runTest {
        deliverEmail(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "Test Subject",
            body = "Hello, this is a test email!"
        )

        val emails = createService().onReceived.pull()

        assertEquals(1, emails.size)
        val email = emails.first()

        assertEquals("sender@example.com", email.from.value.raw)
        assertTrue(email.to.any { it.value.raw == "inbox@test.local" })
        assertEquals("Test Subject", email.subject)
        assertEquals("Hello, this is a test email!", email.plainText?.trim())
    }

    @Test
    fun testFetchMultipleEmails() = runTest {
        repeat(3) { i ->
            deliverEmail(
                from = "sender$i@example.com",
                to = "inbox@test.local",
                subject = "Test Subject $i",
                body = "Email body $i"
            )
        }

        val emails = createService().onReceived.pull()
        assertEquals(3, emails.size)
        assertEquals(3, emails.map { it.subject }.toSet().size)
    }

    @Test
    fun testFetchHtmlEmail() = runTest {
        val htmlContent = "<html><body><h1>Hello!</h1><p>This is HTML content.</p></body></html>"

        deliverHtmlEmail(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "HTML Email Test",
            plainText = "Plain text fallback",
            html = htmlContent
        )

        val emails = createService().onReceived.pull()
        assertEquals(1, emails.size)
        val email = emails.first()

        assertNotNull(email.html)
        assertTrue(email.html!!.contains("<h1>Hello!</h1>"))
        assertNotNull(email.plainText)
    }

    @Test
    fun testFetchEmailWithAttachment() = runTest {
        deliverEmailWithAttachment(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "Email with Attachment",
            body = "Please see attached file.",
            attachmentName = "test.txt",
            attachmentContent = "This is attachment content".toByteArray(),
            attachmentContentType = "text/plain"
        )

        val emails = createService().onReceived.pull()
        assertEquals(1, emails.size)
        val email = emails.first()

        assertEquals("Email with Attachment", email.subject)
        assertTrue(email.attachments.isNotEmpty())
        assertEquals("test.txt", email.attachments.first().filename)
    }

    @Test
    fun testFetchEmailWithThreadingHeaders() = runTest {
        val originalMessageId = "<original@example.com>"
        val refMessageId = "<ref1@example.com>"

        deliverEmailWithHeaders(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "Re: Original Thread",
            body = "This is a reply.",
            headers = mapOf(
                "In-Reply-To" to originalMessageId,
                "References" to "$refMessageId $originalMessageId"
            )
        )

        val emails = createService().onReceived.pull()
        assertEquals(1, emails.size)
        val email = emails.first()

        assertEquals("original@example.com", email.inReplyTo)
        assertEquals(2, email.references.size)
    }

    @Test
    fun testMessagesMarkedAsRead() = runTest {
        deliverEmail(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "Mark As Read Test",
            body = "Test body"
        )

        val service = createService()
        assertEquals(1, service.onReceived.pull().size)
        assertEquals(0, service.onReceived.pull().size)
    }

    @Test
    fun testSpecialCharactersInEmail() = runTest {
        deliverEmail(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "Special: Émojis & Symbols",
            body = "Body with special chars: é ñ ü 中文"
        )

        val emails = createService().onReceived.pull()
        assertEquals(1, emails.size)
        assertTrue(emails.first().subject.contains("Émojis"))
    }

    @Test
    fun testServiceConnect() = runTest {
        val service = createService()
        service.connect()
        service.disconnect()
    }

    @Test
    fun testServiceHealthCheck() = runTest {
        val service = createService()
        val health = service.healthCheck()
        assertEquals(com.lightningkite.services.data.HealthStatus.Level.OK, health.level)
    }

    private fun createService(): ImapEmailInboundService {
        return ImapEmailInboundService(
            name = "test-imap",
            context = testContext,
            host = "localhost",
            port = imapPort,
            username = "inbox@test.local",
            password = "password",
            folder = "INBOX",
            useSsl = false,
            requireStartTls = false,
        )
    }

    private fun deliverEmail(from: String, to: String, subject: String, body: String) {
        val message = GreenMailUtil.createTextEmail(
            to, from, subject, body,
            greenMail.smtp.serverSetup
        )
        GreenMailUtil.sendMimeMessage(message)
    }

    private fun deliverHtmlEmail(
        from: String,
        to: String,
        subject: String,
        plainText: String,
        html: String,
    ) {
        val session = greenMail.smtp.createSession()
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(from))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.subject = subject

        val multipart = MimeMultipart("alternative")

        val textPart = MimeBodyPart()
        textPart.setText(plainText, "UTF-8", "plain")
        multipart.addBodyPart(textPart)

        val htmlPart = MimeBodyPart()
        htmlPart.setContent(html, "text/html; charset=UTF-8")
        multipart.addBodyPart(htmlPart)

        message.setContent(multipart)
        message.saveChanges()

        GreenMailUtil.sendMimeMessage(message)
    }

    private fun deliverEmailWithAttachment(
        from: String,
        to: String,
        subject: String,
        body: String,
        attachmentName: String,
        attachmentContent: ByteArray,
        attachmentContentType: String,
    ) {
        val session = greenMail.smtp.createSession()
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(from))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.subject = subject

        val multipart = MimeMultipart("mixed")

        val textPart = MimeBodyPart()
        textPart.setText(body)
        multipart.addBodyPart(textPart)

        val attachmentPart = MimeBodyPart()
        val dataSource = ByteArrayDataSource(attachmentContent, attachmentContentType)
        attachmentPart.dataHandler = DataHandler(dataSource)
        attachmentPart.fileName = attachmentName
        attachmentPart.disposition = MimeBodyPart.ATTACHMENT
        multipart.addBodyPart(attachmentPart)

        message.setContent(multipart)
        message.saveChanges()

        GreenMailUtil.sendMimeMessage(message)
    }

    private fun deliverEmailWithHeaders(
        from: String,
        to: String,
        subject: String,
        body: String,
        headers: Map<String, String>,
    ) {
        val session = greenMail.smtp.createSession()
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(from))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.subject = subject
        message.setText(body)

        headers.forEach { (name, value) ->
            message.setHeader(name, value)
        }

        message.saveChanges()
        GreenMailUtil.sendMimeMessage(message)
    }
}
