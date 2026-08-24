package com.lightningkite.services.phonecall.twilio

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.webhooksubservice.WebSocketAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Unit tests for TwilioAudioStreamAdapter.
 */
class TwilioAudioStreamAdapterTest {

    private val adapter = TwilioAudioStreamAdapter()

    // ==================== Parse Tests ====================

    @Test
    fun testParse_connectedEvent() = runTest {
        // The "connected" event doesn't contain useful info, so it returns NoOp
        // We need to wait for the "start" event for actual stream metadata
        val json = """{"event":"connected","protocol":"Call","version":"1.0.0"}"""
        val frame = WebSocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.NoOp, "Expected NoOp for 'connected' event, got $event")
    }

    @Test
    fun testParse_startEvent() = runTest {
        val json = """{
            "event": "start",
            "streamSid": "MZ1234567890",
            "start": {
                "callSid": "CA9876543210",
                "accountSid": "AC1234567890",
                "tracks": ["inbound"],
                "customParameters": {
                    "userId": "123",
                    "sessionId": "abc"
                }
            }
        }""".trimIndent()
        val frame = WebSocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Connected, "Expected Connected, got $event")
        val connected = event
        assertEquals("CA9876543210", connected.callId)
        assertEquals("MZ1234567890", connected.streamId)
        assertEquals("123", connected.customParameters["userId"])
        assertEquals("abc", connected.customParameters["sessionId"])
    }

    @Test
    fun testParse_mediaEvent() = runTest {
        // Adapter is stateless - each event is parsed independently
        val json = """{
            "event": "media",
            "streamSid": "MZ1234567890",
            "media": {
                "payload": "SGVsbG8gV29ybGQ=",
                "timestamp": "12345",
                "chunk": "42"
            }
        }"""
        val frame = WebSocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Audio, "Expected Audio, got $event")
        val audio = event
        assertEquals("MZ1234567890", audio.streamId)
        assertEquals("SGVsbG8gV29ybGQ=", audio.payload)
        assertEquals(12345L, audio.timestamp)
        assertEquals(42L, audio.sequenceNumber)
    }

    @Test
    fun testParse_dtmfEvent() = runTest {
        // Adapter is stateless - each event is parsed independently
        val json = """{
            "event": "dtmf",
            "streamSid": "MZ1234567890",
            "dtmf": {
                "digit": "5"
            }
        }"""
        val frame = WebSocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Dtmf, "Expected Dtmf, got $event")
        val dtmf = event
        assertEquals("MZ1234567890", dtmf.streamId)
        assertEquals("5", dtmf.digit)
    }

    @Test
    fun testParse_stopEvent() = runTest {
        // Adapter is stateless - each event is parsed independently
        val json = """{"event":"stop","streamSid":"MZ1234567890"}"""
        val frame = WebSocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Stop, "Expected Stop, got $event")
        val stop = event
        assertEquals("MZ1234567890", stop.streamId)
    }

    // ==================== Render Tests ====================

    @Test
    fun testRender_audioCommand() = runTest {
        val command = AudioStreamCommand.Audio(
            streamId = "MZ1234567890",
            payload = "SGVsbG8gV29ybGQ="
        )

        val frame = adapter.render(command)

        assertTrue(frame is WebSocketAdapter.Frame.Text)
        val text = frame.text

        assertTrue(text.contains("\"event\":\"media\""), "Missing event:media. Got: $text")
        assertTrue(text.contains("\"streamSid\":\"MZ1234567890\""), "Missing streamSid. Got: $text")
        assertTrue(text.contains("\"payload\":\"SGVsbG8gV29ybGQ=\""), "Missing payload. Got: $text")
    }

    @Test
    fun testRender_clearCommand() = runTest {
        val command = AudioStreamCommand.Clear(streamId = "MZ1234567890")

        val frame = adapter.render(command)

        assertTrue(frame is WebSocketAdapter.Frame.Text)
        val text = frame.text

        assertTrue(text.contains("\"event\":\"clear\""), "Missing event:clear. Got: $text")
        assertTrue(text.contains("\"streamSid\":\"MZ1234567890\""), "Missing streamSid. Got: $text")
    }

    @Test
    fun testRender_markCommand() = runTest {
        val command = AudioStreamCommand.Mark(
            streamId = "MZ1234567890",
            name = "end-of-greeting"
        )

        val frame = adapter.render(command)

        assertTrue(frame is WebSocketAdapter.Frame.Text)
        val text = frame.text

        assertTrue(text.contains("\"event\":\"mark\""), "Missing event:mark. Got: $text")
        assertTrue(text.contains("\"streamSid\":\"MZ1234567890\""), "Missing streamSid. Got: $text")
        assertTrue(text.contains("\"name\":\"end-of-greeting\""), "Missing mark name. Got: $text")
    }

    // ==================== Signature Validation Tests ====================

    private val emptyBody = TypedData(Data.Text(""), MediaType.Application.FormUrlEncoded)

    /**
     * Computes a valid X-Twilio-Signature the same way Twilio would, reusing
     * [TwilioPhoneCallService.computeSignature] since it's the same HMAC-SHA1-over-URL-plus-sorted-params
     * algorithm as the one under test here.
     */
    private fun computeSignature(authToken: String, url: String, params: Map<String, String> = emptyMap()): String =
        TwilioPhoneCallService(
            name = "sig-helper",
            context = TestSettingContext(),
            account = "AC0000000000",
            authToken = authToken,
            defaultFrom = "+15550000000",
        ).computeSignature(url, params)

    @Test
    fun testParseStart_validSignatureIsAccepted() = runTest {
        val authToken = "authtoken123"
        val url = "wss://myserver.com/voice-ai"
        val adapter = TwilioAudioStreamAdapter(authToken)
        adapter.configureExpectedUrl(url)

        val start = adapter.parseStart(
            queryParameters = emptyList(),
            headers = mapOf("X-Twilio-Signature" to listOf(computeSignature(authToken, url))),
            body = emptyBody,
        )

        assertEquals("", start.callId)
    }

    @Test
    fun testParseStart_missingSignatureIsRejected() = runTest {
        val adapter = TwilioAudioStreamAdapter("authtoken123")
        adapter.configureExpectedUrl("wss://myserver.com/voice-ai")

        assertFailsWith<SecurityException> {
            adapter.parseStart(queryParameters = emptyList(), headers = emptyMap(), body = emptyBody)
        }
    }

    @Test
    fun testParseStart_invalidSignatureIsRejected() = runTest {
        val adapter = TwilioAudioStreamAdapter("authtoken123")
        adapter.configureExpectedUrl("wss://myserver.com/voice-ai")

        assertFailsWith<SecurityException> {
            adapter.parseStart(
                queryParameters = emptyList(),
                headers = mapOf("X-Twilio-Signature" to listOf("invalid_signature")),
                body = emptyBody,
            )
        }
    }

    @Test
    fun testParseStart_signatureComputedForWrongUrlIsRejected() = runTest {
        val authToken = "authtoken123"
        val adapter = TwilioAudioStreamAdapter(authToken)
        adapter.configureExpectedUrl("wss://myserver.com/voice-ai")

        // A signature that's valid for a different URL must not validate against the configured one.
        val signatureForOtherUrl = computeSignature(authToken, "wss://attacker.example.com/voice-ai")

        assertFailsWith<SecurityException> {
            adapter.parseStart(
                queryParameters = emptyList(),
                headers = mapOf("X-Twilio-Signature" to listOf(signatureForOtherUrl)),
                body = emptyBody,
            )
        }
    }

    @Test
    fun testParseStart_authTokenWithoutExpectedUrlFailsClosed() = runTest {
        val adapter = TwilioAudioStreamAdapter("authtoken123")
        // configureExpectedUrl() intentionally not called

        assertFailsWith<SecurityException> {
            adapter.parseStart(
                queryParameters = emptyList(),
                headers = mapOf("X-Twilio-Signature" to listOf("anything")),
                body = emptyBody,
            )
        }
    }

    @Test
    fun testParseStart_noAuthTokenSkipsValidation() = runTest {
        // Matches the adapter's original (pre-fix) behavior when authentication is intentionally omitted.
        val start = adapter.parseStart(
            queryParameters = listOf("foo" to "bar"),
            headers = emptyMap(),
            body = emptyBody,
        )

        assertEquals("bar", start.metadata["foo"])
    }
}
