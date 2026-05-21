package com.lightningkite.services.sms.twilio

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.test.runTest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Verifies that [TwilioSmsInboundService] fails closed when no webhook URL has been
 * configured. Prior to 1.0.0, an unconfigured webhook URL caused signature validation
 * to be silently skipped, accepting any inbound POST as authentic. The current
 * behavior must throw [SecurityException] instead.
 */
class TwilioSmsInboundFailClosedTest {

    private val testContext = TestSettingContext()

    private val sampleBody =
        "From=%2B15559876543&To=%2B15551234567&Body=hi&NumMedia=0&MessageSid=SM123"

    @Test
    fun parse_throwsSecurityException_whenWebhookUrlNotConfigured() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000",
        )

        val body = TypedData(Data.Text(sampleBody), MediaType.Application.FormUrlEncoded)

        // Even with a plausible-looking signature header, validation must reject the
        // request because we have no configured URL to reconstruct the signed string.
        val headers = mapOf("X-Twilio-Signature" to listOf("anything-here="))

        val ex = assertFailsWith<SecurityException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = headers,
                body = body,
            )
        }
        // Message should mention that the webhook URL is not configured so operators
        // can diagnose the misconfiguration quickly.
        assertNotNull(ex.message)
        assert(ex.message!!.contains("webhook URL not configured", ignoreCase = true)) {
            "Expected message to explain missing webhook URL, got: ${ex.message}"
        }
    }

    /**
     * Sanity counterpoint: after [configureWebhook]-equivalent setup, a request with a
     * correctly-computed signature is accepted. We can't call [configureWebhook]
     * directly here (it makes a real Twilio API call), but the parse path uses the
     * same `httpUrl` field, so we exercise the success path via a wrapper service
     * that pre-populates it.
     */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parse_accepts_whenWebhookUrlConfiguredAndSignatureValid() = runTest {
        val authToken = "authtoken123"
        val webhookUrl = "https://example.com/webhook"

        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = authToken,
            phoneNumber = "+18008008000",
        )

        // Compute the signature Twilio would send for these params against webhookUrl.
        // This mirrors the format used inside TwilioSmsInboundService.validateSignature.
        val params = mapOf(
            "From" to "+15559876543",
            "To" to "+15551234567",
            "Body" to "hi",
            "NumMedia" to "0",
            "MessageSid" to "SM123",
        )
        val data = buildString {
            append(webhookUrl)
            params.keys.sorted().forEach { k ->
                append(k); append(params[k])
            }
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = Base64.encode(mac.doFinal(data.toByteArray(Charsets.UTF_8)))

        // Populate httpUrl without invoking the real Twilio API. We mirror what
        // configureWebhook() does to its private `httpUrl` field via reflection.
        val onReceived = service.onReceived
        val httpUrlField = onReceived::class.java.getDeclaredField("httpUrl")
        httpUrlField.isAccessible = true
        httpUrlField.set(onReceived, webhookUrl)

        val body = TypedData(Data.Text(sampleBody), MediaType.Application.FormUrlEncoded)
        val result = onReceived.parse(
            queryParameters = emptyList(),
            headers = mapOf("X-Twilio-Signature" to listOf(signature)),
            body = body,
        )

        assertEquals("+15559876543", result.from.raw)
        assertEquals("+15551234567", result.to.raw)
        assertEquals("hi", result.body)
    }
}
