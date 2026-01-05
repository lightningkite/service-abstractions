package com.lightningkite.services.voiceagent

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.uuid.Uuid

/**
 * Test implementation of [VoiceAgentService] for unit testing.
 *
 * This implementation:
 * - Records all operations for verification
 * - Allows injecting mock responses
 * - Never makes real network calls
 *
 * ## Usage in Tests
 *
 * ```kotlin
 * val service = TestVoiceAgentService("test", context)
 *
 * // Configure mock behavior
 * service.mockResponses.add("Hello! How can I help you?")
 *
 * // Run your code
 * val session = service.createSession(config)
 * session.sendAudio(audioData)
 *
 * // Verify
 * assertEquals(1, service.sessionsCreated.size)
 * assertEquals(audioData, service.audioReceived.first())
 * ```
 */
public class TestVoiceAgentService(
    override val name: String,
    override val context: SettingContext,
) : VoiceAgentService {

    /** Sessions created via [createSession] */
    public val sessionsCreated: MutableList<VoiceAgentSessionConfig> = mutableListOf()

    /** Mock responses to emit when audio is received */
    public val mockResponses: MutableList<String> = mutableListOf()

    /** Mock tool calls to emit */
    public val mockToolCalls: MutableList<MockToolCall> = mutableListOf()

    override suspend fun createSession(config: VoiceAgentSessionConfig): VoiceAgentSession {
        sessionsCreated.add(config)
        return TestVoiceAgentSession(
            sessionId = Uuid.random().toString(),
            config = config,
            mockResponses = mockResponses.toList(),
            mockToolCalls = mockToolCalls.toList(),
        )
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Test voice agent service")
    }

    /** Reset all recorded data */
    public fun reset() {
        sessionsCreated.clear()
        mockResponses.clear()
        mockToolCalls.clear()
    }
}

/**
 * Mock tool call for testing.
 */
public data class MockToolCall(
    val toolName: String,
    val arguments: String,
)

/**
 * Test implementation of [VoiceAgentSession].
 */
public class TestVoiceAgentSession(
    override val sessionId: String,
    override val config: VoiceAgentSessionConfig,
    private val mockResponses: List<String>,
    private val mockToolCalls: List<MockToolCall>,
) : VoiceAgentSession {

    private val eventChannel = Channel<VoiceAgentEvent>(Channel.UNLIMITED)
    private var responseIndex = 0
    private var toolCallIndex = 0

    /** Audio chunks received via [sendAudio] */
    public val audioReceived: MutableList<ByteArray> = mutableListOf()

    /** Tool results received via [sendToolResult] */
    public val toolResults: MutableList<Pair<String, String>> = mutableListOf()

    /** Messages added via [addMessage] */
    public val messagesAdded: MutableList<Pair<VoiceAgentSession.MessageRole, String>> = mutableListOf()

    /** Whether [commitAudio] was called */
    public var audioCommitted: Boolean = false

    /** Whether [cancelResponse] was called */
    public var responseCancelled: Boolean = false

    /** Whether the session is closed */
    public var isClosed: Boolean = false

    init {
        // Emit session created event
        eventChannel.trySend(VoiceAgentEvent.SessionCreated(sessionId))
    }

    override val events: Flow<VoiceAgentEvent> = eventChannel.receiveAsFlow()

    override suspend fun sendAudio(audio: ByteArray) {
        check(!isClosed) { "Session is closed" }
        audioReceived.add(audio)

        // Emit mock response if available
        if (responseIndex < mockResponses.size) {
            val text = mockResponses[responseIndex++]
            val responseId = Uuid.random().toString()
            val itemId = Uuid.random().toString()

            eventChannel.send(VoiceAgentEvent.ResponseStarted(responseId))
            eventChannel.send(VoiceAgentEvent.TextDelta(responseId, itemId, 0, text))
            eventChannel.send(VoiceAgentEvent.TextDone(responseId, itemId, 0, text))
            eventChannel.send(VoiceAgentEvent.ResponseDone(responseId, ResponseStatus.COMPLETED))
        }

        // Emit mock tool call if available
        if (toolCallIndex < mockToolCalls.size) {
            val toolCall = mockToolCalls[toolCallIndex++]
            val responseId = Uuid.random().toString()
            val itemId = Uuid.random().toString()
            val callId = Uuid.random().toString()

            eventChannel.send(VoiceAgentEvent.ResponseStarted(responseId))
            eventChannel.send(VoiceAgentEvent.ToolCallStarted(responseId, itemId, callId, toolCall.toolName))
            eventChannel.send(VoiceAgentEvent.ToolCallDone(callId, toolCall.toolName, toolCall.arguments))
        }
    }

    override suspend fun commitAudio() {
        check(!isClosed) { "Session is closed" }
        audioCommitted = true
        eventChannel.send(VoiceAgentEvent.InputAudioCommitted(Uuid.random().toString()))
    }

    override suspend fun clearInputBuffer() {
        check(!isClosed) { "Session is closed" }
        eventChannel.send(VoiceAgentEvent.InputAudioCleared)
    }

    override suspend fun cancelResponse() {
        check(!isClosed) { "Session is closed" }
        responseCancelled = true
    }

    override suspend fun createResponse() {
        check(!isClosed) { "Session is closed" }
        // Emit a default response if none configured
        val responseId = Uuid.random().toString()
        val itemId = Uuid.random().toString()
        eventChannel.send(VoiceAgentEvent.ResponseStarted(responseId))
        eventChannel.send(VoiceAgentEvent.TextDelta(responseId, itemId, 0, "Test response"))
        eventChannel.send(VoiceAgentEvent.TextDone(responseId, itemId, 0, "Test response"))
        eventChannel.send(VoiceAgentEvent.ResponseDone(responseId, ResponseStatus.COMPLETED))
    }

    override suspend fun updateSession(config: VoiceAgentSessionConfig) {
        check(!isClosed) { "Session is closed" }
        eventChannel.send(VoiceAgentEvent.SessionUpdated(config))
    }

    override suspend fun sendToolResult(callId: String, result: String) {
        check(!isClosed) { "Session is closed" }
        toolResults.add(callId to result)
    }

    override suspend fun addMessage(role: VoiceAgentSession.MessageRole, text: String) {
        check(!isClosed) { "Session is closed" }
        messagesAdded.add(role to text)
    }

    override suspend fun awaitConnection() {
        // Test service is always "connected" - no-op
    }

    override suspend fun close() {
        if (!isClosed) {
            isClosed = true
            eventChannel.close()
        }
    }

    /**
     * Manually emit an event for testing.
     */
    public suspend fun emitEvent(event: VoiceAgentEvent) {
        eventChannel.send(event)
    }
}
