package com.lightningkite.services.email.imap

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.ReceivedEmail
import com.lightningkite.toEmailAddress
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for IMAP email service with GreenMail.
 *
 * These tests verify:
 * 1. Emails can be fetched from the IMAP server
 * 2. Email content is correctly parsed (plain text, HTML, attachments)
 * 3. Email headers are correctly extracted
 * 4. Messages are correctly marked as read
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

    // ==================== Email Fetch and Parse Tests ====================

    @Test
    fun testFetchUnreadEmails() = runTest {
        // Deliver an email
        deliverEmail(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "Test Subject",
            body = "Hello, this is a test email!"
        )

        // Fetch emails directly (simulating what the service does)
        val emails = fetchEmailsViaImap()

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

        val emails = fetchEmailsViaImap()
        assertEquals(3, emails.size)

        val subjects = emails.map { it.subject }.toSet()
        assertEquals(3, subjects.size)
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

        val emails = fetchEmailsViaImap()
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

        val emails = fetchEmailsViaImap()
        assertEquals(1, emails.size)
        val email = emails.first()

        assertEquals("Email with Attachment", email.subject)
        assertTrue(email.attachments.isNotEmpty())

        val attachment = email.attachments.first()
        assertEquals("test.txt", attachment.filename)
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

        val emails = fetchEmailsViaImap()
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

        // First fetch - should get 1 email and mark as read
        val firstFetch = fetchEmailsViaImap(markAsRead = true)
        assertEquals(1, firstFetch.size)

        // Second fetch - should get 0 emails (already read)
        val secondFetch = fetchEmailsViaImap()
        assertEquals(0, secondFetch.size)
    }

    @Test
    fun testSpecialCharactersInEmail() = runTest {
        deliverEmail(
            from = "sender@example.com",
            to = "inbox@test.local",
            subject = "Special: Émojis & Symbols",
            body = "Body with special chars: é ñ ü 中文"
        )

        val emails = fetchEmailsViaImap()
        assertEquals(1, emails.size)
        val email = emails.first()

        assertTrue(email.subject.contains("Émojis"))
    }

    // ==================== Service Integration Tests ====================

    @Test
    fun testServiceConnect() = runTest {
        val service = createService()

        // Should connect without error
        service.connect()
        service.disconnect()
    }

    @Test
    fun testServiceHealthCheck() = runTest {
        val service = createService()
        val health = service.healthCheck()
        assertEquals(com.lightningkite.services.HealthStatus.Level.OK, health.level)
    }

    // ==================== Helpers ====================

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
            httpClient = HttpClient(CIO)
        )
    }

    /**
     * Fetches emails via IMAP and converts them to ReceivedEmail.
     * This simulates what the ImapEmailInboundService does internally.
     */
    private fun fetchEmailsViaImap(markAsRead: Boolean = true): List<ReceivedEmail> {
        val session = Session.getInstance(Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "localhost")
            put("mail.imap.port", imapPort)
        })

        val store = session.getStore("imap")
        store.connect("localhost", imapPort, "inbox@test.local", "password")

        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_WRITE)

        val unreadMessages = inbox.search(
            jakarta.mail.search.FlagTerm(Flags(Flags.Flag.SEEN), false)
        )

        val results = unreadMessages.filterIsInstance<MimeMessage>().map { msg ->
            val result = parseMessageToReceivedEmail(msg)

            if (markAsRead) {
                msg.setFlag(Flags.Flag.SEEN, true)
            }

            result
        }

        inbox.close(false)
        store.close()

        return results
    }

    private fun parseMessageToReceivedEmail(msg: MimeMessage): ReceivedEmail {
        val from = (msg.from?.firstOrNull() as? InternetAddress)?.let {
            com.lightningkite.services.email.EmailAddressWithName(
                value = it.address.toEmailAddress(),
                label = it.personal
            )
        } ?: com.lightningkite.services.email.EmailAddressWithName(
            value = "unknown@unknown.com".toEmailAddress()
        )

        val to = (msg.getRecipients(Message.RecipientType.TO) ?: emptyArray())
            .filterIsInstance<InternetAddress>()
            .map {
                com.lightningkite.services.email.EmailAddressWithName(
                    value = it.address.toEmailAddress(),
                    label = it.personal
                )
            }

        val cc = (msg.getRecipients(Message.RecipientType.CC) ?: emptyArray())
            .filterIsInstance<InternetAddress>()
            .map {
                com.lightningkite.services.email.EmailAddressWithName(
                    value = it.address.toEmailAddress(),
                    label = it.personal
                )
            }

        val contentResult = parseContent(msg)

        val headers = mutableMapOf<String, List<String>>()
        msg.allHeaders.asIterator().forEach { header ->
            headers[header.name] = (headers[header.name] ?: emptyList()) + header.value
        }

        val inReplyTo = msg.getHeader("In-Reply-To")?.firstOrNull()?.trim('<', '>')
        val references = msg.getHeader("References")?.firstOrNull()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.map { it.trim('<', '>') }
            ?: emptyList()

        return ReceivedEmail(
            messageId = msg.getHeader("Message-ID")?.firstOrNull()?.trim('<', '>') ?: "unknown",
            from = from,
            to = to,
            cc = cc,
            replyTo = null,
            subject = msg.subject ?: "",
            html = contentResult.html,
            plainText = contentResult.plainText,
            receivedAt = kotlin.time.Instant.fromEpochMilliseconds((msg.sentDate ?: msg.receivedDate ?: java.util.Date()).time),
            headers = headers,
            attachments = contentResult.attachments,
            envelope = null,
            spamScore = null,
            inReplyTo = inReplyTo,
            references = references
        )
    }

    private data class ContentResult(
        val plainText: String?,
        val html: String?,
        val attachments: List<com.lightningkite.services.email.ReceivedAttachment>
    )

    private fun parseContent(part: jakarta.mail.Part): ContentResult {
        var plainText: String? = null
        var html: String? = null
        val attachments = mutableListOf<com.lightningkite.services.email.ReceivedAttachment>()

        // Check for attachments FIRST (before text/plain check), since attachments may have text/plain content type
        val isAttachment = jakarta.mail.Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
            jakarta.mail.Part.INLINE.equals(part.disposition, ignoreCase = true) ||
            (part.fileName != null && !part.isMimeType("multipart/*"))

        when {
            isAttachment -> {
                val filename = part.fileName ?: "attachment"
                val bytes = part.inputStream.use { it.readBytes() }
                attachments.add(
                    com.lightningkite.services.email.ReceivedAttachment(
                        filename = filename,
                        contentType = com.lightningkite.MediaType.Application.OctetStream,
                        size = bytes.size.toLong(),
                        contentId = null,
                        content = com.lightningkite.services.data.Data.Bytes(bytes),
                        contentUrl = null
                    )
                )
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as jakarta.mail.Multipart
                for (i in 0 until multipart.count) {
                    val bodyPart = multipart.getBodyPart(i)
                    val result = parseContent(bodyPart)
                    plainText = plainText ?: result.plainText
                    html = html ?: result.html
                    attachments.addAll(result.attachments)
                }
            }
            part.isMimeType("text/plain") -> {
                plainText = part.content as? String
            }
            part.isMimeType("text/html") -> {
                html = part.content as? String
            }
        }

        return ContentResult(plainText, html, attachments)
    }

    // ==================== Email Delivery Helpers ====================

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
        html: String
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
        attachmentContentType: String
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
        headers: Map<String, String>
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
