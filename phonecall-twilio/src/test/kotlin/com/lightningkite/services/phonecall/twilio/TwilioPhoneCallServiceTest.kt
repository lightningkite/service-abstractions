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

    // Test webhook URLs
    private val testIncomingCallUrl = "https://test.example.com/incoming"
    private val testStatusCallbackUrl = "https://test.example.com/status"

    // Sample webhook parameters (decoded form)
    private val sampleIncomingCallParams = mapOf(
        "AccountSid" to "AC1234567890",
        "ApiVersion" to "2010-04-01",
        "CallSid" to "CA1234567890abcdef1234567890abcdef",
        "CallStatus" to "ringing",
        "Called" to "+15551234567",
        "CalledCity" to "SAN FRANCISCO",
        "CalledCountry" to "US",
        "CalledState" to "CA",
        "CalledZip" to "94107",
        "Caller" to "+15559876543",
        "CallerCity" to "LOS ANGELES",
        "CallerCountry" to "US",
        "CallerState" to "CA",
        "CallerZip" to "90001",
        "Direction" to "inbound",
        "From" to "+15559876543",
        "FromCity" to "LOS ANGELES",
        "FromCountry" to "US",
        "FromState" to "CA",
        "FromZip" to "90001",
        "To" to "+15551234567",
        "ToCity" to "SAN FRANCISCO",
        "ToCountry" to "US",
        "ToState" to "CA",
        "ToZip" to "94107"
    )

    private val sampleCallStatusParams = mapOf(
        "AccountSid" to "AC1234567890",
        "ApiVersion" to "2010-04-01",
        "CallSid" to "CA1234567890abcdef1234567890abcdef",
        "CallStatus" to "completed",
        "CallDuration" to "30",
        "Direction" to "outbound-api",
        "From" to "+15551234567",
        "To" to "+15559876543"
    )

    /** Converts params map to form-url-encoded string */
    private fun Map<String, String>.toFormUrlEncoded(): String =
        entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
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

        // Set up webhook URL for testing
        service.setWebhookUrlsForTesting(incomingCallUrl = testIncomingCallUrl)

        // Compute valid signature
        val signature = service.computeSignature(testIncomingCallUrl, sampleIncomingCallParams)

        val body = TypedData(Data.Text(sampleIncomingCallParams.toFormUrlEncoded()), MediaType.Application.FormUrlEncoded)

        val result = service.onIncomingCall.parse(
            queryParameters = emptyList(),
            headers = mapOf("X-Twilio-Signature" to listOf(signature)),
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

        // Set up webhook URL for testing
        service.setWebhookUrlsForTesting(statusCallbackUrl = testStatusCallbackUrl)

        // Compute valid signature
        val signature = service.computeSignature(testStatusCallbackUrl, sampleCallStatusParams)

        val body = TypedData(Data.Text(sampleCallStatusParams.toFormUrlEncoded()), MediaType.Application.FormUrlEncoded)

        val result = service.onCallStatus.parse(
            queryParameters = emptyList(),
            headers = mapOf("X-Twilio-Signature" to listOf(signature)),
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

        // Set up webhook URL for testing
        service.setWebhookUrlsForTesting(incomingCallUrl = testIncomingCallUrl)

        val params = mapOf("From" to "+15559876543", "To" to "+15551234567")
        val signature = service.computeSignature(testIncomingCallUrl, params)

        val body = TypedData(Data.Text(params.toFormUrlEncoded()), MediaType.Application.FormUrlEncoded)

        assertFailsWith<PhoneCallException> {
            service.onIncomingCall.parse(
                queryParameters = emptyList(),
                headers = mapOf("X-Twilio-Signature" to listOf(signature)),
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
    fun testRenderInstructions_conference() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val instructions = CallInstructions.Conference(
            name = "my-conference-room",
            startOnEnter = true,
            endOnExit = false,
            muted = false,
            beep = true,
            waitUrl = "https://example.com/hold-music.mp3",
            statusCallbackUrl = "https://example.com/conference-status",
            statusCallbackEvents = listOf("join", "leave")
        )

        val twiml = service.renderInstructions(instructions)

        println("Conference TwiML: $twiml")
        assertTrue(twiml.contains("<Dial"), "Missing <Dial> tag. TwiML: $twiml")
        assertTrue(twiml.contains("<Conference"), "Missing <Conference> tag. TwiML: $twiml")
        assertTrue(twiml.contains("my-conference-room"), "Missing conference name. TwiML: $twiml")
        assertTrue(twiml.contains("startConferenceOnEnter=\"true\""), "Missing startConferenceOnEnter. TwiML: $twiml")
        assertTrue(twiml.contains("endConferenceOnExit=\"false\""), "Missing endConferenceOnExit. TwiML: $twiml")
        assertTrue(twiml.contains("waitUrl=\"https://example.com/hold-music.mp3\""), "Missing waitUrl. TwiML: $twiml")
        assertTrue(twiml.contains("statusCallback=\"https://example.com/conference-status\""), "Missing statusCallback. TwiML: $twiml")
        assertTrue(twiml.contains("statusCallbackEvent=\"join leave\""), "Missing statusCallbackEvent. TwiML: $twiml")
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

    @Test
    fun testRenderInstructions_streamAudioWithQuestionMarkInValue() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        // Test custom parameter with a URL containing ? in its value
        val instructions = CallInstructions.StreamAudio(
            websocketUrl = "wss://myserver.com/audio-stream",
            track = AudioTrack.BOTH,
            customParameters = mapOf(
                "redirectUrl" to "https://other.example.com/callback?param=value&other=123"
            )
        )

        val twiml = service.renderInstructions(instructions)
        println("StreamAudio with ? in param value TwiML: $twiml")

        // The parameter value should be XML-escaped (& becomes &amp;)
        assertTrue(twiml.contains("<Parameter"), "Missing <Parameter> tag. TwiML: $twiml")
        assertTrue(twiml.contains("name=\"redirectUrl\""), "Missing redirectUrl parameter. TwiML: $twiml")
        // The value should have the ? preserved and & escaped
        assertTrue(twiml.contains("https://other.example.com/callback?param=value&amp;other=123"),
            "Parameter value not properly XML-escaped. TwiML: $twiml")
    }

    @Test
    fun testRenderInstructions_streamAudioParameterWithComplexUrl() {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        // Test customParameter with a URL value containing ? and & characters
        // This is a common use case: passing a callback URL with its own query params
        val instructions = CallInstructions.StreamAudio(
            websocketUrl = "wss://myserver.com/stream",
            track = AudioTrack.BOTH,
            customParameters = mapOf(
                "callbackUrl" to "https://api.example.com/callback?token=abc123&session=xyz789&redirect=https://other.com?foo=bar"
            )
        )

        val twiml = service.renderInstructions(instructions)
        println("StreamAudio with complex URL parameter TwiML:\n$twiml")

        assertTrue(twiml.contains("<Connect>"), "Missing <Connect> tag. TwiML: $twiml")
        assertTrue(twiml.contains("<Stream"), "Missing <Stream> tag. TwiML: $twiml")
        assertTrue(twiml.contains("<Parameter"), "Missing <Parameter> tag. TwiML: $twiml")
        assertTrue(twiml.contains("name=\"callbackUrl\""), "Missing callbackUrl parameter name. TwiML: $twiml")
        // The & should be escaped to &amp; for valid XML
        assertTrue(twiml.contains("token=abc123&amp;session=xyz789"),
            "& not properly escaped to &amp;. TwiML: $twiml")
        // The nested ? should be preserved as-is
        assertTrue(twiml.contains("redirect=https://other.com?foo=bar"),
            "Nested ? not preserved. TwiML: $twiml")
    }

    // ==================== Audio Stream Adapter Parsing Tests ====================

    @Test
    fun testAudioStreamAdapter_parseStartEventWithUrlParams() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        val adapter = service.audioStream

        // Simulate Twilio "start" event with customParameters containing a URL with ? and &
        val startEventJson = """
            {
                "event": "start",
                "streamSid": "MZ123456789",
                "start": {
                    "callSid": "CA987654321",
                    "customParameters": {
                        "callbackUrl": "https://api.example.com/callback?token=abc123&session=xyz",
                        "redirectUrl": "https://other.com/api?key=secret&extra=value?nested=param"
                    }
                }
            }
        """.trimIndent()

        val frame = com.lightningkite.services.data.WebsocketAdapter.Frame.Text(startEventJson)
        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Connected, "Expected Connected event, got $event")
        val connected = event as AudioStreamEvent.Connected

        assertEquals("CA987654321", connected.callId)
        assertEquals("MZ123456789", connected.streamId)

        // Verify the customParameters are parsed correctly including ? and & in values
        assertEquals("https://api.example.com/callback?token=abc123&session=xyz",
            connected.customParameters["callbackUrl"],
            "callbackUrl param should preserve ? and &")
        assertEquals("https://other.com/api?key=secret&extra=value?nested=param",
            connected.customParameters["redirectUrl"],
            "redirectUrl param should preserve nested ?")
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

        // Set up webhook URL for testing
        service.setWebhookUrlsForTesting(statusCallbackUrl = testStatusCallbackUrl)

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
            val params = mapOf(
                "CallSid" to "CA123",
                "CallStatus" to twilioStatus,
                "Direction" to "outbound-api",
                "From" to "+15551234567",
                "To" to "+15559876543"
            )
            val signature = service.computeSignature(testStatusCallbackUrl, params)
            val body = TypedData(Data.Text(params.toFormUrlEncoded()), MediaType.Application.FormUrlEncoded)

            val result = service.onCallStatus.parse(
                queryParameters = emptyList(),
                headers = mapOf("X-Twilio-Signature" to listOf(signature)),
                body = body
            )

            assertEquals(expectedStatus, result.status, "Expected $twilioStatus to map to $expectedStatus")
        }
    }

    // ==================== Signature Validation Tests ====================

    @Test
    fun testSignatureValidation_missingSignature() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        service.setWebhookUrlsForTesting(incomingCallUrl = testIncomingCallUrl)

        val body = TypedData(Data.Text(sampleIncomingCallParams.toFormUrlEncoded()), MediaType.Application.FormUrlEncoded)

        assertFailsWith<SecurityException> {
            service.onIncomingCall.parse(
                queryParameters = emptyList(),
                headers = emptyMap(), // No signature header
                body = body
            )
        }
    }

    @Test
    fun testSignatureValidation_invalidSignature() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        service.setWebhookUrlsForTesting(incomingCallUrl = testIncomingCallUrl)

        val body = TypedData(Data.Text(sampleIncomingCallParams.toFormUrlEncoded()), MediaType.Application.FormUrlEncoded)

        assertFailsWith<SecurityException> {
            service.onIncomingCall.parse(
                queryParameters = emptyList(),
                headers = mapOf("X-Twilio-Signature" to listOf("invalid_signature")),
                body = body
            )
        }
    }

    @Test
    fun testSignatureValidation_webhookNotConfigured() = runTest {
        val service = TwilioPhoneCallService(
            name = "test",
            context = testContext,
            account = "AC1234567890",
            authToken = "authtoken123",
            defaultFrom = "+15551234567"
        )

        // Don't configure webhook URL

        val body = TypedData(Data.Text(sampleIncomingCallParams.toFormUrlEncoded()), MediaType.Application.FormUrlEncoded)

        assertFailsWith<SecurityException> {
            service.onIncomingCall.parse(
                queryParameters = emptyList(),
                headers = mapOf("X-Twilio-Signature" to listOf("some_signature")),
                body = body
            )
        }
    }
}
