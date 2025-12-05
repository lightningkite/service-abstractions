package com.lightningkite.services.email.imap

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.EmailInboundService
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ImapEmailInboundService using GreenMail mock server.
 *
 * GreenMail provides an embedded IMAP/SMTP server for testing email functionality
 * without external dependencies.
 */
class ImapEmailInboundServiceTest {

    private val testContext = TestSettingContext()

    private lateinit var greenMail: GreenMail
    private val imapPort = 3143
    private val smtpPort = 3025

    init {
        // Ensure companion object init block runs to register URL handlers
        ImapEmailInboundService
    }

    @BeforeTest
    fun setUp() {
        // Create GreenMail with both IMAP and SMTP
        greenMail = GreenMail(
            arrayOf(
                ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP),
                ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP)
            )
        )
        greenMail.start()

        // Create a test user
        greenMail.setUser("test@localhost", "test@localhost", "password123")
    }

    @AfterTest
    fun tearDown() {
        greenMail.stop()
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_imaps() {
        val settings = EmailInboundService.Settings("imaps://user:pass@imap.example.com:993/INBOX")
        val service = settings("test-imap", testContext) as ImapEmailInboundService

        assertEquals("imap.example.com", service.host)
        assertEquals(993, service.port)
        assertEquals("user", service.username)
        assertEquals("pass", service.password)
        assertEquals("INBOX", service.folder)
        assertTrue(service.useSsl)
    }

    @Test
    fun testUrlParsing_imap() {
        val settings = EmailInboundService.Settings("imap://user:pass@imap.example.com:143/INBOX")
        val service = settings("test-imap", testContext) as ImapEmailInboundService

        assertEquals("imap.example.com", service.host)
        assertEquals(143, service.port)
        assertEquals("user", service.username)
        assertEquals("pass", service.password)
        assertEquals("INBOX", service.folder)
        assertTrue(!service.useSsl)
    }

    @Test
    fun testUrlParsing_defaultPort_imaps() {
        val settings = EmailInboundService.Settings("imaps://user:pass@imap.example.com/INBOX")
        val service = settings("test-imap", testContext) as ImapEmailInboundService

        assertEquals(993, service.port)  // Default IMAPS port
    }

    @Test
    fun testUrlParsing_defaultPort_imap() {
        val settings = EmailInboundService.Settings("imap://user:pass@imap.example.com/INBOX")
        val service = settings("test-imap", testContext) as ImapEmailInboundService

        assertEquals(143, service.port)  // Default IMAP port
    }

    @Test
    fun testUrlParsing_defaultFolder() {
        val settings = EmailInboundService.Settings("imaps://user:pass@imap.example.com:993")
        val service = settings("test-imap", testContext) as ImapEmailInboundService

        assertEquals("INBOX", service.folder)  // Default folder
    }

    @Test
    fun testUrlParsing_urlEncodedCredentials() {
        // Password with special characters: p@ss:word!
        val settings = EmailInboundService.Settings("imaps://user%40domain.com:p%40ss%3Aword%21@imap.example.com:993/INBOX")
        val service = settings("test-imap", testContext) as ImapEmailInboundService

        assertEquals("user@domain.com", service.username)
        assertEquals("p@ss:word!", service.password)
    }

    @Test
    fun testUrlParsing_invalidUrl() {
        val settings = EmailInboundService.Settings("imaps://invalid")

        assertFailsWith<IllegalStateException> {
            settings("test-imap", testContext)
        }
    }

    @Test
    fun testHelperFunction() {
        with(ImapEmailInboundService) {
            val settings = EmailInboundService.Settings.imap(
                username = "user@example.com",
                password = "secret",
                host = "imap.example.com",
                port = 993,
                folder = "INBOX",
                ssl = true
            )

            assertEquals("imaps://user@example.com:secret@imap.example.com:993/INBOX", settings.url)
        }
    }

    // ==================== Connection Tests ====================

    @Test
    fun testConnect_success() = runTest {
        val service = ImapEmailInboundService(
            name = "test",
            context = testContext,
            host = "localhost",
            port = imapPort,
            username = "test@localhost",
            password = "password123",
            folder = "INBOX",
            useSsl = false,
            requireStartTls = false  // GreenMail doesn't support STARTTLS
        )

        // Should not throw
        service.connect()
        service.disconnect()
    }

    @Test
    fun testConnect_wrongPassword() = runTest {
        val service = ImapEmailInboundService(
            name = "test",
            context = testContext,
            host = "localhost",
            port = imapPort,
            username = "test@localhost",
            password = "wrong-password",
            folder = "INBOX",
            useSsl = false,
            requireStartTls = false
        )

        assertFailsWith<Exception> {
            service.connect()
        }
    }

    @Test
    fun testHealthCheck_success() = runTest {
        val service = ImapEmailInboundService(
            name = "test",
            context = testContext,
            host = "localhost",
            port = imapPort,
            username = "test@localhost",
            password = "password123",
            folder = "INBOX",
            useSsl = false,
            requireStartTls = false
        )

        val health = service.healthCheck()
        assertEquals(HealthStatus.Level.OK, health.level)
    }

    @Test
    fun testHealthCheck_wrongCredentials() = runTest {
        val service = ImapEmailInboundService(
            name = "test",
            context = testContext,
            host = "localhost",
            port = imapPort,
            username = "test@localhost",
            password = "wrong-password",
            folder = "INBOX",
            useSsl = false,
            requireStartTls = false
        )

        val health = service.healthCheck()
        assertEquals(HealthStatus.Level.ERROR, health.level)
    }

    @Test
    fun testHealthCheck_nonExistentFolder() = runTest {
        val service = ImapEmailInboundService(
            name = "test",
            context = testContext,
            host = "localhost",
            port = imapPort,
            username = "test@localhost",
            password = "password123",
            folder = "NonExistentFolder",
            useSsl = false,
            requireStartTls = false
        )

        val health = service.healthCheck()
        assertEquals(HealthStatus.Level.WARNING, health.level)
        assertTrue(health.additionalMessage?.contains("does not exist") == true)
    }

    // ==================== Webhook Not Supported Test ====================

    @Test
    fun testparse_throwsUnsupportedOperation() = runTest {
        val service = ImapEmailInboundService(
            name = "test",
            context = testContext,
            host = "localhost",
            port = imapPort,
            username = "test@localhost",
            password = "password123",
            folder = "INBOX",
            useSsl = false,
            requireStartTls = false
        )

        assertFailsWith<UnsupportedOperationException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = com.lightningkite.services.data.TypedData.text("test", com.lightningkite.MediaType.Text.Plain)
            )
        }
    }

    // ==================== Email Delivery Helper ====================

    /**
     * Delivers an email to the GreenMail server for testing.
     */
    private fun deliverEmail(
        from: String,
        to: String,
        subject: String,
        body: String,
        html: String? = null,
        inReplyTo: String? = null,
        references: List<String>? = null
    ) {
        val message = GreenMailUtil.createTextEmail(
            to,
            from,
            subject,
            body,
            greenMail.smtp.serverSetup
        )

        // Add optional headers
        if (inReplyTo != null) {
            message.setHeader("In-Reply-To", inReplyTo)
        }
        if (references != null && references.isNotEmpty()) {
            message.setHeader("References", references.joinToString(" "))
        }

        // If HTML is provided, create multipart/alternative
        if (html != null) {
            val multipart = MimeMultipart("alternative")

            val textPart = MimeBodyPart()
            textPart.setText(body, "UTF-8", "plain")
            multipart.addBodyPart(textPart)

            val htmlPart = MimeBodyPart()
            htmlPart.setContent(html, "text/html; charset=UTF-8")
            multipart.addBodyPart(htmlPart)

            message.setContent(multipart)
        }

        GreenMailUtil.sendMimeMessage(message)
    }

    /**
     * Delivers an email with attachment to the GreenMail server.
     */
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

        // Text body
        val textPart = MimeBodyPart()
        textPart.setText(body)
        multipart.addBodyPart(textPart)

        // Attachment
        val attachmentPart = MimeBodyPart()
        attachmentPart.setContent(attachmentContent, attachmentContentType)
        attachmentPart.fileName = attachmentName
        attachmentPart.disposition = MimeBodyPart.ATTACHMENT
        multipart.addBodyPart(attachmentPart)

        message.setContent(multipart)
        message.saveChanges()

        GreenMailUtil.sendMimeMessage(message)
    }
}
