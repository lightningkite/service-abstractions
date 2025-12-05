package com.lightningkite.services.phonecall.twilio

import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for TwilioAudioStreamAdapter.
 */
class TwilioAudioStreamAdapterTest {

    private val adapter = TwilioAudioStreamAdapter()

    // ==================== Parse Tests ====================

    @Test
    fun testParse_connectedEvent() = runTest {
        val json = """{"event":"connected","protocol":"Call","version":"1.0.0"}"""
        val frame = WebsocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Connected)
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
        val frame = WebsocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Connected, "Expected Connected, got $event")
        val connected = event as AudioStreamEvent.Connected
        assertEquals("CA9876543210", connected.callId)
        assertEquals("MZ1234567890", connected.streamId)
        assertEquals("123", connected.customParameters["userId"])
        assertEquals("abc", connected.customParameters["sessionId"])
    }

    @Test
    fun testParse_mediaEvent() = runTest {
        // First send a start event to set up state
        val startJson = """{
            "event": "start",
            "streamSid": "MZ1234567890",
            "start": {
                "callSid": "CA9876543210",
                "customParameters": {}
            }
        }"""
        adapter.parse(WebsocketAdapter.Frame.Text(startJson))

        // Now parse media event
        val json = """{
            "event": "media",
            "streamSid": "MZ1234567890",
            "media": {
                "payload": "SGVsbG8gV29ybGQ=",
                "timestamp": "12345",
                "chunk": "42"
            }
        }"""
        val frame = WebsocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Audio, "Expected Audio, got $event")
        val audio = event as AudioStreamEvent.Audio
        assertEquals("MZ1234567890", audio.streamId)
        assertEquals("SGVsbG8gV29ybGQ=", audio.payload)
        assertEquals(12345L, audio.timestamp)
        assertEquals(42L, audio.sequenceNumber)
    }

    @Test
    fun testParse_dtmfEvent() = runTest {
        // First send a start event to set up state
        val startJson = """{
            "event": "start",
            "streamSid": "MZ1234567890",
            "start": {
                "callSid": "CA9876543210",
                "customParameters": {}
            }
        }"""
        adapter.parse(WebsocketAdapter.Frame.Text(startJson))

        val json = """{
            "event": "dtmf",
            "streamSid": "MZ1234567890",
            "dtmf": {
                "digit": "5"
            }
        }"""
        val frame = WebsocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Dtmf, "Expected Dtmf, got $event")
        val dtmf = event as AudioStreamEvent.Dtmf
        assertEquals("MZ1234567890", dtmf.streamId)
        assertEquals("5", dtmf.digit)
    }

    @Test
    fun testParse_stopEvent() = runTest {
        // First send a start event to set up state
        val startJson = """{
            "event": "start",
            "streamSid": "MZ1234567890",
            "start": {
                "callSid": "CA9876543210",
                "customParameters": {}
            }
        }"""
        adapter.parse(WebsocketAdapter.Frame.Text(startJson))

        val json = """{"event":"stop","streamSid":"MZ1234567890"}"""
        val frame = WebsocketAdapter.Frame.Text(json)

        val event = adapter.parse(frame)

        assertTrue(event is AudioStreamEvent.Stop, "Expected Stop, got $event")
        val stop = event as AudioStreamEvent.Stop
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

        assertTrue(frame is WebsocketAdapter.Frame.Text)
        val text = (frame as WebsocketAdapter.Frame.Text).text

        assertTrue(text.contains("\"event\":\"media\""), "Missing event:media. Got: $text")
        assertTrue(text.contains("\"streamSid\":\"MZ1234567890\""), "Missing streamSid. Got: $text")
        assertTrue(text.contains("\"payload\":\"SGVsbG8gV29ybGQ=\""), "Missing payload. Got: $text")
    }

    @Test
    fun testRender_clearCommand() = runTest {
        val command = AudioStreamCommand.Clear(streamId = "MZ1234567890")

        val frame = adapter.render(command)

        assertTrue(frame is WebsocketAdapter.Frame.Text)
        val text = (frame as WebsocketAdapter.Frame.Text).text

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

        assertTrue(frame is WebsocketAdapter.Frame.Text)
        val text = (frame as WebsocketAdapter.Frame.Text).text

        assertTrue(text.contains("\"event\":\"mark\""), "Missing event:mark. Got: $text")
        assertTrue(text.contains("\"streamSid\":\"MZ1234567890\""), "Missing streamSid. Got: $text")
        assertTrue(text.contains("\"name\":\"end-of-greeting\""), "Missing mark name. Got: $text")
    }
}
