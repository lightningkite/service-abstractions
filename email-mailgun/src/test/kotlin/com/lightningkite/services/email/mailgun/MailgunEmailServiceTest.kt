// by Claude
package com.lightningkite.services.email.mailgun

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises [MailgunEmailService]'s real request-building against a [MockEngine] standing in for
 * Mailgun's Messages API, so the exact multipart fields sent to Mailgun can be asserted without a
 * live call. Mirrors the pattern used in sms-twilio's TwilioSMSTest.
 */
class MailgunEmailServiceTest {

    /** Multipart bodies stream to a channel rather than exposing their parts directly; drain it to inspect the raw wire form. */
    private suspend fun HttpRequestData.bodyAsText(): String {
        val content = body as OutgoingContent.WriteChannelContent
        val channel = ByteChannel()
        content.writeTo(channel)
        channel.close()
        return channel.readRemaining().readByteArray().decodeToString()
    }

    private fun client(
        onSend: suspend (String) -> Pair<HttpStatusCode, String>,
    ): HttpClient = HttpClient(MockEngine { request ->
        val (status, body) = onSend(request.bodyAsText())
        respond(body, status, headersOf("Content-Type", "application/json"))
    })

    private fun service(baseClient: HttpClient): MailgunEmailService = MailgunEmailService(
        name = "mailgun-test",
        context = TestSettingContext(),
        key = "key-1234567890",
        domain = "mail.example.com",
        baseClient = baseClient,
    )

    @Test
    fun send_honorsExplicitFromAddress() = runTest {
        var capturedBody: String? = null
        val svc = service(client { body ->
            capturedBody = body
            HttpStatusCode.OK to """{"id":"abc","message":"Queued"}"""
        })

        svc.send(
            Email(
                subject = "Test",
                from = EmailAddressWithName("billing@company.com", "Billing"),
                to = listOf(EmailAddressWithName("user@example.com")),
                plainText = "body",
            )
        )

        assertTrue(
            capturedBody!!.contains("billing@company.com"),
            "expected the caller-supplied from address in the request body, got: $capturedBody"
        )
    }

    @Test
    fun send_withoutExplicitFrom_fallsBackToDomainDefault() = runTest {
        var capturedBody: String? = null
        val svc = service(client { body ->
            capturedBody = body
            HttpStatusCode.OK to """{"id":"abc","message":"Queued"}"""
        })

        svc.send(
            Email(
                subject = "Test",
                to = listOf(EmailAddressWithName("user@example.com")),
                plainText = "body",
            )
        )

        assertTrue(
            capturedBody!!.contains("noreply@mail.example.com"),
            "expected the configured domain default when no from address is given, got: $capturedBody"
        )
    }

    @Test
    fun send_attachmentIncludesFilenameAndContentType() = runTest {
        var capturedBody: String? = null
        val svc = service(client { body ->
            capturedBody = body
            HttpStatusCode.OK to """{"id":"abc","message":"Queued"}"""
        })

        svc.send(
            Email(
                subject = "Test",
                to = listOf(EmailAddressWithName("user@example.com")),
                plainText = "body",
                attachments = listOf(
                    Email.Attachment(
                        inline = false,
                        filename = "invoice.pdf",
                        typedData = TypedData.text("fake-pdf-bytes", MediaType.Application.Pdf)
                    )
                )
            )
        )

        val body = capturedBody!!
        assertTrue(body.contains("filename=invoice.pdf"), "expected filename in body, got: $body")
        assertTrue(body.contains("Content-Type: application/pdf"), "expected content type in body, got: $body")
    }

    /**
     * Now that Mailgun actually forwards attachment.filename onto the wire (see above), it inherits
     * the same header-injection exposure customHeaders had — Email.Attachment's constructor-time
     * CRLF guard (see EmailHeaderInjectionTest in the shared email module) must hold here too.
     */
    @Test
    fun attachmentFilenameWithCRLFIsRejectedBeforeAnyRequestIsSent() {
        assertFailsWith<IllegalArgumentException> {
            Email.Attachment(
                inline = false,
                filename = "evil.txt\r\nContent-Type: text/html",
                typedData = TypedData.text("x", MediaType.Text.Plain)
            )
        }
    }
}
