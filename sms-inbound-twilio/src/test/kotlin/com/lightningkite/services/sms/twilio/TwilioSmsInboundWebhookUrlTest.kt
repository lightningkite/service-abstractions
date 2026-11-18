package com.lightningkite.services.sms.twilio

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A process that never called `configureWebhook` (another server instance, a fresh Lambda) must still
 * verify webhooks: the URL Twilio signs against is the one configured on the phone number in Twilio.
 */
class TwilioSmsInboundWebhookUrlTest {
    private val authToken = "authtoken123"
    private val webhookUrl = "https://example.com/webhook"
    private val params = mapOf(
        "From" to "+15559876543",
        "To" to "+15551234567",
        "Body" to "hi",
        "NumMedia" to "0",
        "MessageSid" to "SM123",
    )
    private val body = "From=%2B15559876543&To=%2B15551234567&Body=hi&NumMedia=0&MessageSid=SM123"

    private var lookups = 0
    private fun twilio(smsUrl: String?) = HttpClient(MockEngine { request ->
        lookups++
        assertEquals("/2010-04-01/Accounts/AC1234567890/IncomingPhoneNumbers.json", request.url.encodedPath)
        val urlJson = smsUrl?.let { "\"$it\"" } ?: "null"
        respond(
            """{"incoming_phone_numbers": [{"sid": "PN1", "sms_url": $urlJson}]}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })

    private fun service(httpClient: HttpClient) = TwilioSmsInboundService(
        name = "test",
        context = TestSettingContext(),
        accountSid = "AC1234567890",
        authToken = authToken,
        phoneNumber = "+15551234567",
        httpClient = httpClient,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun signature(url: String): String {
        val data = url + params.keys.sorted().joinToString("") { it + params[it] }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.encode(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private suspend fun TwilioSmsInboundService.receive(signature: String) = onReceived.parse(
        queryParameters = emptyList(),
        headers = mapOf("X-Twilio-Signature" to listOf(signature)),
        body = TypedData(Data.Text(body), MediaType.Application.FormUrlEncoded),
    )

    @Test
    fun parse_verifiesAgainstTwilioConfiguredUrl_withoutConfigureWebhookInThisProcess() = runTest {
        val service = service(twilio(smsUrl = webhookUrl))
        assertEquals("hi", service.receive(signature(webhookUrl)).body)
        assertEquals("hi", service.receive(signature(webhookUrl)).body)
        assertEquals(1, lookups, "the configured URL is looked up once, then reused")
    }

    @Test
    fun parse_rejectsSignatureForAnotherUrl() = runTest {
        val service = service(twilio(smsUrl = webhookUrl))
        assertFailsWith<SecurityException> { service.receive(signature("https://attacker.example.com/hook")) }
    }

    @Test
    fun parse_failsClosed_whenTwilioHasNoSmsUrl() = runTest {
        val service = service(twilio(smsUrl = null))
        assertFailsWith<SecurityException> { service.receive(signature(webhookUrl)) }
    }
}
