package com.lightningkite.services.phonecall.twilio

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.phonecall.*
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for TwilioPhoneCallService.
 * These tests use mock data and don't require actual Twilio credentials for most cases.
 */
class TwilioPhoneCallServiceTest {

    private val testContext = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        TwilioPhoneCallService
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_authTokenFormat() {
        val settings = PhoneCallService.Settings("twilio://AC1234567890:authtoken123@+15551234567")
        val service = settings("test-phonecall", testContext)

        assertNotNull(service)
        assertTrue(service is TwilioPhoneCallService)
    }

    @Test
    fun testUrlParsing_apiKeyFormat() {
        val settings = PhoneCallService.Settings("twilio://AC1234567890-SK1234567890:keysecret123@+15551234567")
        val service = settings("test-phonecall", testContext)

        assertNotNull(service)
        assertTrue(service is TwilioPhoneCallService)
    }

    @Test
    fun testUrlParsing_missingPhoneNumber() {
        val settings = PhoneCallService.Settings("twilio://AC1234567890:authtoken123")

        assertFailsWith<IllegalArgumentException> {
            settings("test-phonecall", testContext)
        }
    }

    @Test
    fun testUrlParsing_invalidScheme() {
        val settings = PhoneCallService.Settings("invalid://AC1234567890:authtoken123@+15551234567")

        assertFailsWith<IllegalArgumentException> {
            settings("test-phonecall", testContext)
        }
    }

    @Test
    fun testHelperFunction_authToken() {
        with(TwilioPhoneCallService) {
            val settings = PhoneCallService.Settings.twilio(
                account = "AC1234567890",
                authToken = "authtoken123",
                from = "+15551234567"
            )

            assertEquals("twilio://AC1234567890:authtoken123@+15551234567", settings.url)
        }
    }

    @Test
    fun testHelperFunction_apiKey() {
        with(TwilioPhoneCallService) {
            val settings = PhoneCallService.Settings.twilioApiKey(
                account = "AC1234567890",
                keySid = "SK1234567890",
                keySecret = "keysecret123",
                from = "+15551234567"
            )

            assertEquals("twilio://AC1234567890-SK1234567890:keysecret123@+15551234567", settings.url)
        }
    }

    // ==================== Webhook Parsing Tests ====================

    // Sample Twilio voice webhook payload
    private val sampleIncomingCallWebhookBody = buildString {
        append("AccountSid=AC1234567890")
        append("&ApiVersion=2010-04-01")
        append("&CallSid=CA1234567890abcdef1234567890abcdef")
        append("&CallStatus=ringing")
        append("&Called=%2B15551234567")
        append("&CalledCity=SAN+FRANCISCO")
        append("&CalledCountry=US")
        append("&CalledState=CA")
        append("&CalledZip=94107")
        append("&Caller=%2B15559876543")
        append("&CallerCity=LOS+ANGELES")
        append("&CallerCountry=US")
        append("&CallerState=CA")
        append("&CallerZip=90001")
        append("&Direction=inbound")
        append("&From=%2B15559876543")
        append("&FromCity=LOS+ANGELES")
        append("&FromCountry=US")
        append("&FromState=CA")
        append("&FromZip=90001")
        append("&To=%2B15551234567")
        append("&ToCity=SAN+FRANCISCO")
        append("&ToCountry=US")
        append("&ToState=CA")
        append("&ToZip=94107")
    }

    private val sampleCallStatusWebhookBody = buildString {
        append("AccountSid=AC1234567890")
        append("&ApiVersion=2010-04-01")
        append("&CallSid=CA1234567890abcdef1234567890abcdef")
        append("&CallStatus=completed")
        append("&CallDuration=30")
        append("&Direction=outbound-api")
        append("&From=%2B15551234567")
        append("&To=%2B15559876543")
    }

    @Test
    fun testparse_incomingCall() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val body = TypedData(Data.Text(sampleIncomingCallWebhookBody), MediaType.Application.FormUrlEncoded)

        val result = service.onIncomingCall.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("CA1234567890abcdef1234567890abcdef", result.callId)
        assertEquals("+15559876543", result.from.raw)
        assertEquals("+15551234567", result.to.raw)
        assertEquals(CallDirection.INBOUND, result.direction)
    }

    @Test
    fun testparse_callStatus() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val body = TypedData(Data.Text(sampleCallStatusWebhookBody), MediaType.Application.FormUrlEncoded)

        val result = service.onCallStatus.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("CA1234567890abcdef1234567890abcdef", result.callId)
        assertEquals(CallStatus.COMPLETED, result.status)
        assertEquals(CallDirection.OUTBOUND, result.direction)
        assertEquals(30, result.duration?.inWholeSeconds)
    }

    @Test
    fun testparse_missingCallSid() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val body = TypedData(Data.Text("From=%2B15559876543&To=%2B15551234567"), MediaType.Application.FormUrlEncoded)

        assertFailsWith<PhoneCallException> {
            service.onIncomingCall.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }
    }

    // ==================== TwiML Rendering Tests ====================

    @Test
    fun testRenderInstructions_say() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.Say(
            text = "Hello, welcome to our service!",
            then = CallInstructions.Hangup
        )

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("<Response>"))
        assertTrue(twiml.contains("</Response>"))
        assertTrue(twiml.contains("<Say"))
        assertTrue(twiml.contains("Hello, welcome to our service!"))
        assertTrue(twiml.contains("<Hangup/>"))
    }

    @Test
    fun testRenderInstructions_gather() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.Gather(
            prompt = "Press 1 for sales, 2 for support",
            numDigits = 1,
            actionUrl = "https://example.com/handle-input"
        )

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("<Gather"))
        assertTrue(twiml.contains("numDigits=\"1\""))
        assertTrue(twiml.contains("action=\"https://example.com/handle-input\""))
        assertTrue(twiml.contains("Press 1 for sales, 2 for support"))
    }

    @Test
    fun testRenderInstructions_accept() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.Accept(
            transcriptionEnabled = true,
            initialMessage = "Hello, how can I help you?"
        )

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("<Response>"))
        assertTrue(twiml.contains("<Say"))
        assertTrue(twiml.contains("Hello, how can I help you?"))
    }

    @Test
    fun testRenderInstructions_reject() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.Reject

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("<Reject"))
    }

    @Test
    fun testRenderInstructions_forward() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.Forward(
            to = "+15559999999".toPhoneNumber()
        )

        val twiml = service.renderInstructions(instructions)

        println("Forward TwiML: $twiml")
        assertTrue(twiml.contains("<Dial"), "Missing <Dial tag. TwiML: $twiml")
        assertTrue(twiml.contains("<Number>"), "Missing <Number> tag. TwiML: $twiml")
        assertTrue(twiml.contains("+15559999999"), "Missing phone number. TwiML: $twiml")
    }

    @Test
    fun testRenderInstructions_complexFlow() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        // Complex flow: Say -> Gather -> Say -> Hangup
        val instructions = CallInstructions.Say(
            text = "Welcome!",
            then = CallInstructions.Gather(
                prompt = "Press 1 for sales",
                numDigits = 1,
                actionUrl = "https://example.com/gather",
                then = CallInstructions.Say(
                    text = "Goodbye!",
                    then = CallInstructions.Hangup
                )
            )
        )

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("Welcome!"))
        assertTrue(twiml.contains("<Gather"))
        assertTrue(twiml.contains("Press 1 for sales"))
        assertTrue(twiml.contains("Goodbye!"))
        assertTrue(twiml.contains("<Hangup/>"))
    }

    // ==================== StreamAudio Tests ====================

    @Test
    fun testRenderInstructions_streamAudio() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.StreamAudio(
            websocketUrl = "wss://myserver.com/audio-stream",
            track = AudioTrack.BOTH
        )

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("<Connect>"), "Missing <Connect> tag. TwiML: $twiml")
        assertTrue(twiml.contains("<Stream"), "Missing <Stream> tag. TwiML: $twiml")
        assertTrue(twiml.contains("url=\"wss://myserver.com/audio-stream\""), "Missing url attribute. TwiML: $twiml")
        assertTrue(twiml.contains("track=\"both_tracks\""), "Missing track attribute. TwiML: $twiml")
    }

    @Test
    fun testRenderInstructions_streamAudioWithParameters() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.StreamAudio(
            websocketUrl = "wss://myserver.com/audio-stream",
            track = AudioTrack.INBOUND,
            customParameters = mapOf("userId" to "123", "sessionId" to "abc")
        )

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("<Connect>"), "Missing <Connect> tag. TwiML: $twiml")
        assertTrue(twiml.contains("<Stream"), "Missing <Stream> tag. TwiML: $twiml")
        assertTrue(twiml.contains("track=\"inbound_track\""), "Missing inbound_track. TwiML: $twiml")
        assertTrue(twiml.contains("<Parameter"), "Missing <Parameter> tag. TwiML: $twiml")
        assertTrue(twiml.contains("name=\"userId\""), "Missing userId parameter. TwiML: $twiml")
        assertTrue(twiml.contains("value=\"123\""), "Missing userId value. TwiML: $twiml")
        assertTrue(twiml.contains("name=\"sessionId\""), "Missing sessionId parameter. TwiML: $twiml")
    }

    @Test
    fun testRenderInstructions_sayThenStreamAudio() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.Say(
            text = "Connecting you to our AI assistant.",
            then = CallInstructions.StreamAudio(
                websocketUrl = "wss://ai.example.com/voice",
                track = AudioTrack.BOTH
            )
        )

        val twiml = service.renderInstructions(instructions)

        assertTrue(twiml.contains("<Say"), "Missing <Say> tag. TwiML: $twiml")
        assertTrue(twiml.contains("Connecting you to our AI assistant."), "Missing say text. TwiML: $twiml")
        assertTrue(twiml.contains("<Connect>"), "Missing <Connect> tag. TwiML: $twiml")
        assertTrue(twiml.contains("<Stream"), "Missing <Stream> tag. TwiML: $twiml")
    }

    // ==================== Call Status Mapping Tests ====================

    @Test
    fun testCallStatusMapping() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val statusMappings = listOf(
            "queued" to CallStatus.QUEUED,
            "ringing" to CallStatus.RINGING,
            "in-progress" to CallStatus.IN_PROGRESS,
            "completed" to CallStatus.COMPLETED,
            "busy" to CallStatus.BUSY,
            "no-answer" to CallStatus.NO_ANSWER,
            "canceled" to CallStatus.CANCELED,
            "failed" to CallStatus.FAILED
        )

        for ((twilioStatus, expectedStatus) in statusMappings) {
            val webhookBody = "CallSid=CA123&CallStatus=$twilioStatus&Direction=outbound-api&From=%2B15551234567&To=%2B15559876543"
            val body = TypedData(Data.Text(webhookBody), MediaType.Application.FormUrlEncoded)

            val result = service.onCallStatus.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )

            assertEquals(expectedStatus, result.status, "Expected $twilioStatus to map to $expectedStatus")
        }
    }
}
