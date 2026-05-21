package com.lightningkite.services.email.imap

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the IMAP -> webhook loopback wire format end-to-end:
 *  1. The polling loop POSTs a `multipart/form-data` body with a JSON `email` envelope plus one
 *     file part per attachment.
 *  2. The same service's [com.lightningkite.services.webhooksubservice.WebhookSubservice.parse]
 *     reconstructs an equivalent [com.lightningkite.services.email.ReceivedEmail] from that body.
 *
 * The GreenMail IMAP server provides a real message with a binary attachment; a JDK [HttpServer]
 * captures the recorded request body and content-type so we can replay it through `parse`.
 */
class ImapWebhookMultipartTest {

    private val testContext = TestSettingContext()
    private val jsonForTest = Json { ignoreUnknownKeys = true }
    private lateinit var greenMail: GreenMail
    private val imapPort = 3165
    private val smtpPort = 3065
    private val username = "inbox@test.local"
    private val password = "password"

    private lateinit var httpServer: HttpServer
    private val capturedBody = AtomicReference<ByteArray?>(null)
    private val capturedContentType = AtomicReference<String?>(null)
    private val callCount = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        ImapEmailInboundService
    }

    @BeforeTest
    fun setUp() {
        greenMail = GreenMail(
            arrayOf(
                ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP),
                ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP),
            )
        )
        greenMail.start()
        greenMail.setUser(username, username, password)

        capturedBody.set(null)
        capturedContentType.set(null)
        callCount.set(0)

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/webhook") { exchange: HttpExchange ->
            callCount.incrementAndGet()
            // Drain body
            val sink = ByteArrayOutputStream()
            exchange.requestBody.use { it.copyTo(sink) }
            capturedBody.set(sink.toByteArray())
            capturedContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            val ok = "ok".toByteArray()
            exchange.sendResponseHeaders(200, ok.size.toLong())
            exchange.responseBody.use { it.write(ok) }
        }
        httpServer.start()
    }

    @AfterTest
    fun tearDown() {
        httpServer.stop(0)
        greenMail.stop()
    }

    @Test
    fun `webhook posts multipart with json envelope and file parts`() = runTest {
        val attachmentBytes = "Hello attachment world!".toByteArray()
        // Use application/octet-stream so the IMAP MIME parser keeps this as an attachment instead
        // of hoisting the bytes into plainText (a separate preexisting limitation in parseContent
        // that this test isn't responsible for exercising).
        deliverEmailWithAttachment(
            from = "sender@example.com",
            to = username,
            subject = "Multipart Roundtrip",
            body = "Plain body text",
            attachmentName = "hello.txt",
            attachmentContent = attachmentBytes,
            attachmentContentType = "application/octet-stream",
        )

        val service = createService()
        service.onReceived.configureWebhook(webhookUrl())
        service.onReceived.onSchedule()

        assertEquals(1, callCount.get(), "Exactly one POST should reach the webhook")

        val body = checkNotNull(capturedBody.get()) { "No body captured" }
        val ct = checkNotNull(capturedContentType.get()) { "No content-type captured" }
        assertTrue(ct.startsWith("multipart/form-data"), "Got content-type: $ct")

        // Parse the multipart body via the SUT's own parser, accessed through `parse(...)`.
        val parts = service.parseMultipartFormData(body, extractBoundary(ct))

        // exactly one `email` part
        val emailParts = parts["email"] ?: emptyList()
        assertEquals(1, emailParts.size, "Should be exactly one `email` form part")
        val envelope = jsonForTest
            .decodeFromString<ImapWebhookEnvelope>(String(emailParts.first().data, Charsets.UTF_8))
        assertEquals("Multipart Roundtrip", envelope.subject)
        assertEquals("sender@example.com", envelope.from.value)
        assertTrue(envelope.to.any { it.value == username })
        assertEquals(1, envelope.attachments.size)
        assertEquals("hello.txt", envelope.attachments.first().filename)

        // exactly one `attachment_0` file part with the original bytes
        val a0 = checkNotNull(parts["attachment_0"]?.singleOrNull()) { "Missing attachment_0 file part" }
        assertEquals("hello.txt", a0.filename)
        assertTrue(attachmentBytes.contentEquals(a0.data), "Attachment bytes must roundtrip exactly")
    }

    @Test
    fun `parse roundtrips a posted webhook body back to a ReceivedEmail`() = runTest {
        val attachmentBytes = byteArrayOf(0x01, 0x02, 0x03, 0x7F, 0x00, 0x55.toByte()) +
                "binary content with \r\n CRLF inside".toByteArray()
        deliverEmailWithAttachment(
            from = "alice@example.com",
            to = username,
            subject = "Roundtrip Verification",
            body = "Plain body",
            attachmentName = "data.bin",
            attachmentContent = attachmentBytes,
            attachmentContentType = "application/octet-stream",
        )

        val service = createService()
        service.onReceived.configureWebhook(webhookUrl())
        service.onReceived.onSchedule()

        val body = checkNotNull(capturedBody.get()) { "No body captured" }
        val ct = checkNotNull(capturedContentType.get()) { "No content-type captured" }

        // Build a TypedData with the captured content-type (including its boundary parameter)
        // and feed it back through parse(...).
        val captured = TypedData(Data.Bytes(body), MediaType(ct))
        val reconstructed = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = captured,
        )

        assertEquals("Roundtrip Verification", reconstructed.subject)
        assertEquals("alice@example.com", reconstructed.from.value.raw)
        assertTrue(reconstructed.to.any { it.value.raw == username })
        assertEquals(1, reconstructed.attachments.size)
        val att = reconstructed.attachments.single()
        assertEquals("data.bin", att.filename)
        val attBytes = checkNotNull(att.content?.bytes()) { "Reconstructed attachment has no bytes" }
        assertTrue(attachmentBytes.contentEquals(attBytes), "Reconstructed bytes must match original")
    }

    // ==================== Helpers ====================

    private fun extractBoundary(contentType: String): String {
        val match = Regex("""boundary=([^;\s]+)""", RegexOption.IGNORE_CASE).find(contentType)
            ?: error("No boundary in content-type: $contentType")
        return match.groupValues[1].trim('"')
    }

    private fun webhookUrl(): String =
        "http://127.0.0.1:${httpServer.address.port}/webhook"

    private fun createService(): ImapEmailInboundService = ImapEmailInboundService(
        name = "test-imap-multipart",
        context = testContext,
        host = "localhost",
        port = imapPort,
        username = username,
        password = password,
        folder = "INBOX",
        useSsl = false,
        requireStartTls = false,
        httpClient = HttpClient(CIO),
    )

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
        val textPart = MimeBodyPart().apply { setText(body) }
        multipart.addBodyPart(textPart)

        val attachmentPart = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(attachmentContent, attachmentContentType))
            fileName = attachmentName
            disposition = MimeBodyPart.ATTACHMENT
        }
        multipart.addBodyPart(attachmentPart)

        message.setContent(multipart)
        message.saveChanges()
        GreenMailUtil.sendMimeMessage(message)
    }
}
