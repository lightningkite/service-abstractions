package com.lightningkite.services.sms.twilio

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.sms.SmsInboundService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for TwilioSmsInboundService.
 * These tests use mock data and don't require actual Twilio credentials.
 */
class TwilioSmsInboundServiceTest {

    private val testContext = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        TwilioSmsInboundService
    }

    // Sample Twilio webhook payload (URL-encoded form data)
    // Based on https://www.twilio.com/docs/messaging/guides/webhook-request
    private val sampleSmsWebhookBody = buildString {
        append("ToCountry=US")
        append("&ToState=CA")
        append("&SmsMessageSid=SM1234567890abcdef1234567890abcdef")
        append("&NumMedia=0")
        append("&ToCity=SAN+FRANCISCO")
        append("&FromZip=94107")
        append("&SmsSid=SM1234567890abcdef1234567890abcdef")
        append("&FromState=CA")
        append("&SmsStatus=received")
        append("&FromCity=SAN+FRANCISCO")
        append("&Body=Hello%2C+this+is+a+test+message%21")
        append("&FromCountry=US")
        append("&To=%2B15551234567")
        append("&ToZip=94107")
        append("&NumSegments=1")
        append("&MessageSid=SM1234567890abcdef1234567890abcdef")
        append("&AccountSid=AC1234567890abcdef1234567890abcdef")
        append("&From=%2B15559876543")
        append("&ApiVersion=2010-04-01")
    }

    // Sample MMS webhook with media
    private val sampleMmsWebhookBody = buildString {
        append("ToCountry=US")
        append("&ToState=CA")
        append("&SmsMessageSid=MM1234567890abcdef1234567890abcdef")
        append("&NumMedia=2")
        append("&ToCity=SAN+FRANCISCO")
        append("&FromZip=94107")
        append("&SmsSid=MM1234567890abcdef1234567890abcdef")
        append("&FromState=CA")
        append("&SmsStatus=received")
        append("&FromCity=SAN+FRANCISCO")
        append("&Body=Check+out+these+photos%21")
        append("&FromCountry=US")
        append("&To=%2B15551234567")
        append("&ToZip=94107")
        append("&NumSegments=1")
        append("&MessageSid=MM1234567890abcdef1234567890abcdef")
        append("&AccountSid=AC1234567890abcdef1234567890abcdef")
        append("&From=%2B15559876543")
        append("&ApiVersion=2010-04-01")
        append("&MediaContentType0=image%2Fjpeg")
        append("&MediaUrl0=https%3A%2F%2Fapi.twilio.com%2F2010-04-01%2FAccounts%2FAC123%2FMessages%2FMM123%2FMedia%2FME123")
        append("&MediaContentType1=image%2Fpng")
        append("&MediaUrl1=https%3A%2F%2Fapi.twilio.com%2F2010-04-01%2FAccounts%2FAC123%2FMessages%2FMM123%2FMedia%2FME456")
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_validUrl() {
        val settings = SmsInboundService.Settings("twilio://AC1234567890:authtoken123@+18008008000")
        val service = settings("test-sms-inbound", testContext)

        assertNotNull(service)
        assertTrue(service is TwilioSmsInboundService)
    }

    @Test
    fun testUrlParsing_missingAuthToken() {
        val settings = SmsInboundService.Settings("twilio://AC1234567890")

        assertFailsWith<IllegalArgumentException> {
            settings("test-sms-inbound", testContext)
        }
    }

    @Test
    fun testUrlParsing_invalidScheme() {
        val settings = SmsInboundService.Settings("invalid://AC1234567890:authtoken123")

        assertFailsWith<IllegalArgumentException> {
            settings("test-sms-inbound", testContext)
        }
    }

    @Test
    fun testUrlParsing_emptyUrl() {
        val settings = SmsInboundService.Settings("twilio://")

        assertFailsWith<IllegalArgumentException> {
            settings("test-sms-inbound", testContext)
        }
    }

    @Test
    fun testHelperFunction() {
        // Use the companion object extension function
        with(TwilioSmsInboundService) {
            val settings = SmsInboundService.Settings.twilio(
                account = "AC1234567890",
                authToken = "authtoken123",
                phoneNumber = "+15551234567"
            )

            assertEquals("twilio://AC1234567890:authtoken123@+15551234567", settings.url)
        }
    }

    // ==================== Webhook Parsing Tests ====================

    @Test
    fun testparse_simpleSms() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        val body = TypedData(Data.Text(sampleSmsWebhookBody), com.lightningkite.MediaType.Application.FormUrlEncoded)

        val result = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("+15559876543", result.from.raw)
        assertEquals("+15551234567", result.to.raw)
        assertEquals("Hello, this is a test message!", result.body)
        assertEquals("SM1234567890abcdef1234567890abcdef", result.providerMessageId)
        assertEquals(0, result.mediaUrls.size)
        assertEquals(0, result.mediaContentTypes.size)
        assertNotNull(result.receivedAt)
    }

    @Test
    fun testparse_mmsWithMedia() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        val body = TypedData(Data.Text(sampleMmsWebhookBody), com.lightningkite.MediaType.Application.FormUrlEncoded)

        val result = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("+15559876543", result.from.raw)
        assertEquals("+15551234567", result.to.raw)
        assertEquals("Check out these photos!", result.body)
        assertEquals("MM1234567890abcdef1234567890abcdef", result.providerMessageId)

        // Verify media attachments
        assertEquals(2, result.mediaUrls.size)
        assertEquals(2, result.mediaContentTypes.size)

        assertEquals("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages/MM123/Media/ME123", result.mediaUrls[0])
        assertEquals("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages/MM123/Media/ME456", result.mediaUrls[1])

        assertEquals("image/jpeg", result.mediaContentTypes[0])
        assertEquals("image/png", result.mediaContentTypes[1])
    }

    @Test
    fun testparse_emptyBody() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        // Webhook with empty message body (can happen with MMS-only messages)
        val webhookBody = "From=%2B15559876543&To=%2B15551234567&Body=&NumMedia=0&MessageSid=SM123"
        val body = TypedData(Data.Text(webhookBody), com.lightningkite.MediaType.Application.FormUrlEncoded)

        val result = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("", result.body)
        assertEquals("+15559876543", result.from.raw)
        assertEquals("+15551234567", result.to.raw)
    }

    @Test
    fun testparse_missingFromField() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        val webhookBody = "To=%2B15551234567&Body=Hello&NumMedia=0&MessageSid=SM123"
        val body = TypedData(Data.Text(webhookBody), com.lightningkite.MediaType.Application.FormUrlEncoded)

        assertFailsWith<IllegalArgumentException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }
    }

    @Test
    fun testparse_missingToField() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        val webhookBody = "From=%2B15559876543&Body=Hello&NumMedia=0&MessageSid=SM123"
        val body = TypedData(Data.Text(webhookBody), com.lightningkite.MediaType.Application.FormUrlEncoded)

        assertFailsWith<IllegalArgumentException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }
    }

    @Test
    fun testparse_specialCharactersInBody() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        // URL-encoded special characters: "Hello! How are you? 😀 <test> & 'quotes'"
        val webhookBody = "From=%2B15559876543&To=%2B15551234567&Body=Hello%21+How+are+you%3F+%F0%9F%98%80+%3Ctest%3E+%26+%27quotes%27&NumMedia=0&MessageSid=SM123"
        val body = TypedData(Data.Text(webhookBody), com.lightningkite.MediaType.Application.FormUrlEncoded)

        val result = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("Hello! How are you? 😀 <test> & 'quotes'", result.body)
    }

    @Test
    fun testparse_internationalPhoneNumbers() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        // UK phone number
        val webhookBody = "From=%2B447700900123&To=%2B15551234567&Body=Hello+from+UK&NumMedia=0&MessageSid=SM123"
        val body = TypedData(Data.Text(webhookBody), com.lightningkite.MediaType.Application.FormUrlEncoded)

        val result = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("+447700900123", result.from.raw)
        assertEquals("Hello from UK", result.body)
    }

    // ==================== Signature Validation Tests ====================

    @Test
    fun testValidateSignature_validSignature() {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "12345",  // Known auth token for test
            phoneNumber = "+18008008000"
        )

        // Test our own generated signature by computing it first
        // Then validating with the same params
        val url = "https://example.com/webhook"
        val params = mapOf(
            "From" to "+15551234567",
            "To" to "+15559876543",
            "Body" to "Test message"
        )

        // Compute the expected signature manually
        val data = buildString {
            append(url)
            params.keys.sorted().forEach { key ->
                append(key)
                append(params[key] ?: "")
            }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        val keySpec = javax.crypto.spec.SecretKeySpec("12345".toByteArray(Charsets.UTF_8), "HmacSHA1")
        mac.init(keySpec)
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val computedSignature = kotlin.io.encoding.Base64.encode(rawHmac)

        val isValid = service.validateSignature(url, params, computedSignature)
        assertTrue(isValid, "Signature should be valid for computed signature")
    }

    @Test
    fun testValidateSignature_invalidSignature() {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "12345",
            phoneNumber = "+18008008000"
        )

        val url = "https://mycompany.com/myapp.php?foo=1&bar=2"
        val params = mapOf(
            "CallSid" to "CA1234567890ABCDE",
            "Caller" to "+14158675310",
            "Digits" to "1234",
            "From" to "+14158675310",
            "To" to "+18005551212"
        )
        val wrongSignature = "WrongSignatureHere123="

        val isValid = service.validateSignature(url, params, wrongSignature)
        assertFalse(isValid, "Signature should be invalid for wrong signature")
    }

    @Test
    fun testValidateSignature_differentAuthToken() {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "wrong-token",  // Different from the one used to generate signature
            phoneNumber = "+18008008000"
        )

        val url = "https://mycompany.com/myapp.php?foo=1&bar=2"
        val params = mapOf(
            "CallSid" to "CA1234567890ABCDE",
            "Caller" to "+14158675310",
            "Digits" to "1234",
            "From" to "+14158675310",
            "To" to "+18005551212"
        )
        // Signature generated with auth token "12345"
        val signature = "RSOYDt4T1cUTdK1PDd93/VVr8B8="

        val isValid = service.validateSignature(url, params, signature)
        assertFalse(isValid, "Signature should be invalid with different auth token")
    }

    @Test
    fun testValidateSignature_emptyParams() {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "test-token",
            phoneNumber = "+18008008000"
        )

        val url = "https://example.com/webhook"
        val params = emptyMap<String, String>()

        // Just verify it doesn't throw - the actual signature would depend on the auth token
        val result = service.validateSignature(url, params, "some-signature")
        // Will be false since "some-signature" is not valid
        assertFalse(result)
    }

    // ==================== Health Check Tests ====================

    @Test
    fun testHealthCheck() = runTest {
        val service = TwilioSmsInboundService(
            name = "test",
            context = testContext,
            accountSid = "AC1234567890",
            authToken = "authtoken123",
            phoneNumber = "+18008008000"
        )

        val health = service.healthCheck()

        assertEquals(com.lightningkite.services.HealthStatus.Level.OK, health.level)
        assertNotNull(health.additionalMessage)
        assertTrue(health.additionalMessage!!.contains("AC1234567890"))
    }
}
