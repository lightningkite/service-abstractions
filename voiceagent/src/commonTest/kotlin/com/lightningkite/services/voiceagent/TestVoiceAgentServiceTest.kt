package com.lightningkite.services.voiceagent

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestVoiceAgentServiceTest {

    private val context = TestSettingContext()
    private val service = TestVoiceAgentService("test", context)

    @Test
    fun createSession_returnsSession() = runTest {
        val config = VoiceAgentSessionConfig(
            instructions = "You are a helpful assistant.",
        )

        val session = service.createSession(config)

        assertNotNull(session.sessionId)
        assertEquals(config, session.config)

        session.close()
    }

    @Test
    fun session_emitsSessionCreatedEvent() = runTest {
        val session = service.createSession()

        val event = session.events.first()

        assertIs<VoiceAgentEvent.SessionCreated>(event)

        session.close()
    }

    @Test
    fun sendAudio_recordsAudioReceived() = runTest {
        service.mockResponses.add("Hello!")

        val session = service.createSession() as TestVoiceAgentSession

        // Wait for session created
        session.events.first { it is VoiceAgentEvent.SessionCreated }

        val audio = ByteArray(100) { it.toByte() }
        session.sendAudio(audio)

        assertEquals(1, session.audioReceived.size)
        assertTrue(audio.contentEquals(session.audioReceived.first()))

        session.close()
    }

    @Test
    fun sendAudio_emitsMockResponse() = runTest {
        service.mockResponses.add("This is a test response")

        val session = service.createSession() as TestVoiceAgentSession

        // Collect events
        val events = mutableListOf<VoiceAgentEvent>()

        // Get session created event
        events.add(session.events.first())

        // Send audio
        val audio = ByteArray(100) { it.toByte() }
        session.sendAudio(audio)

        // Get response events
        events.addAll(session.events.take(3).toList())

        // Verify response events
        assertIs<VoiceAgentEvent.SessionCreated>(events[0])
        assertIs<VoiceAgentEvent.ResponseStarted>(events[1])
        assertIs<VoiceAgentEvent.TextDelta>(events[2])
        assertEquals("This is a test response", (events[2] as VoiceAgentEvent.TextDelta).delta)

        session.close()
    }

    @Test
    fun mockToolCall_emitsToolCallEvents() = runTest {
        service.mockToolCalls.add(MockToolCall("test_function", """{"arg": "value"}"""))

        val session = service.createSession(
            VoiceAgentSessionConfig(
                tools = listOf(
                    SerializableToolDescriptor(
                        name = "test_function",
                        description = "A test function",
                    )
                )
            )
        ) as TestVoiceAgentSession

        // Get session created
        session.events.first()

        // Send audio to trigger tool call
        session.sendAudio(ByteArray(10))

        // Get tool call events
        val events = session.events.take(3).toList()

        assertIs<VoiceAgentEvent.ResponseStarted>(events[0])
        assertIs<VoiceAgentEvent.ToolCallStarted>(events[1])
        assertIs<VoiceAgentEvent.ToolCallDone>(events[2])

        val toolCallDone = events[2] as VoiceAgentEvent.ToolCallDone
        assertEquals("test_function", toolCallDone.toolName)
        assertEquals("""{"arg": "value"}""", toolCallDone.arguments)

        session.close()
    }

    @Test
    fun sendToolResult_recordsToolResult() = runTest {
        val session = service.createSession() as TestVoiceAgentSession

        session.events.first() // Session created

        session.sendToolResult("call-123", """{"result": "success"}""")

        assertEquals(1, session.toolResults.size)
        assertEquals("call-123" to """{"result": "success"}""", session.toolResults.first())

        session.close()
    }

    @Test
    fun commitAudio_setsAudioCommitted() = runTest {
        val session = service.createSession(
            VoiceAgentSessionConfig(turnDetection = TurnDetection.None)
        ) as TestVoiceAgentSession

        session.events.first() // Session created

        session.sendAudio(ByteArray(10))
        session.commitAudio()

        assertTrue(session.audioCommitted)

        session.close()
    }

    @Test
    fun cancelResponse_setsCancelled() = runTest {
        val session = service.createSession() as TestVoiceAgentSession

        session.events.first() // Session created

        session.cancelResponse()

        assertTrue(session.responseCancelled)

        session.close()
    }

    @Test
    fun addMessage_recordsMessage() = runTest {
        val session = service.createSession() as TestVoiceAgentSession

        session.events.first() // Session created

        session.addMessage(VoiceAgentSession.MessageRole.User, "Hello!")

        assertEquals(1, session.messagesAdded.size)
        assertEquals(VoiceAgentSession.MessageRole.User to "Hello!", session.messagesAdded.first())

        session.close()
    }

    @Test
    fun close_marksSessionClosed() = runTest {
        val session = service.createSession() as TestVoiceAgentSession

        session.events.first() // Session created

        session.close()

        assertTrue(session.isClosed)
    }
}
