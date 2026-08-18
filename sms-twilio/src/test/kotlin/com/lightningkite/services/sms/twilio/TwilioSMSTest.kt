package com.lightningkite.services.sms.twilio

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.sms.SMSException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises [TwilioSMS]'s real request-building against a [MockEngine] standing in for Twilio's
 * Messages API, so the exact form parameters sent to Twilio can be asserted without a live call.
 */
class TwilioSMSTest {

    /** Builds a mock-backed client and captures every request the service sends. */
    private fun client(
        onSend: (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ): HttpClient = HttpClient(MockEngine { request ->
        val (status, body) = onSend(request)
        respond(body, status, headersOf("Content-Type", "application/json"))
    })

    private fun service(baseClient: HttpClient): TwilioSMS = TwilioSMS(
        name = "twilio-test",
        context = TestSettingContext(),
        account = "AC1234567890",
        key = "authtoken123",
        from = "+15551234567",
        baseClient = baseClient,
    )

    /** Regression test for the BLOCKER: `to.toString()` display-formats +1 numbers, which Twilio rejects. */
    @Test
    fun send_toUsNumber_sendsRawE164FormatNotDisplayFormat() = runTest {
        var capturedTo: String? = null
        val svc = service(client { request ->
            capturedTo = (request.body as FormDataContent).formData["To"]
            HttpStatusCode.Created to """{"sid":"SM123","status":"queued"}"""
        })

        svc.send("+15559876543".toPhoneNumber(), "Your code is 123456")

        // Twilio's API requires strict E.164; PhoneNumber.toString() would have sent
        // "+1 (555) 987-6543" (display formatting) instead of this raw wire format.
        assertEquals("+15559876543", capturedTo)
    }

    @Test
    fun send_toInternationalNumber_sendsRawFormat() = runTest {
        var capturedTo: String? = null
        val svc = service(client { request ->
            capturedTo = (request.body as FormDataContent).formData["To"]
            HttpStatusCode.Created to """{"sid":"SM123","status":"queued"}"""
        })

        svc.send("+442071838750".toPhoneNumber(), "Hello")

        assertEquals("+442071838750", capturedTo)
    }

    @Test
    fun send_fromAndBody_areSentUnmodified() = runTest {
        var capturedFrom: String? = null
        var capturedBody: String? = null
        val svc = service(client { request ->
            val formData = (request.body as FormDataContent).formData
            capturedFrom = formData["From"]
            capturedBody = formData["Body"]
            HttpStatusCode.Created to """{"sid":"SM123","status":"queued"}"""
        })

        svc.send("+15559876543".toPhoneNumber(), "Your code is 123456")

        assertEquals("+15551234567", capturedFrom)
        assertEquals("Your code is 123456", capturedBody)
    }

    @Test
    fun send_invalidNumberResponse_throwsSmsExceptionWithoutRetry() = runTest {
        var attempts = 0
        val svc = service(client { request ->
            attempts++
            HttpStatusCode.BadRequest to """{"code":21211,"message":"Invalid 'To' Phone Number"}"""
        })

        assertFailsWith<SMSException> {
            svc.send("+15559876543".toPhoneNumber(), "Hello")
        }
        assertEquals(1, attempts, "A 400 is not retried")
    }
}