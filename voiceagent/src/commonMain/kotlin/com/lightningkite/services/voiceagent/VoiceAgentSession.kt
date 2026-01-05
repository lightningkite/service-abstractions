package com.lightningkite.services.voiceagent

import kotlinx.coroutines.flow.Flow

/**
 * A live voice agent conversation session.
 *
 * Represents an active WebSocket connection to a voice agent provider.
 * Use this to send audio input and receive audio/text responses.
 *
 * ## Lifecycle
 *
 * 1. Create via [VoiceAgentService.createSession]
 * 2. Collect [events] to receive responses
 * 3. Send audio via [sendAudio]
 * 4. Handle tool calls when received
 * 5. Close with [close] when done
 *
 * ## Audio Flow
 *
 * ```kotlin
 * // Send microphone audio
 * microphoneFlow.collect { audioChunk ->
 *     session.sendAudio(audioChunk)
 * }
 *
 * // Receive and play agent audio
 * session.events.collect { event ->
 *     when (event) {
 *         is VoiceAgentEvent.AudioDelta -> {
 *             val audio = Base64.decode(event.delta)
 *             audioPlayer.play(audio)
 *         }
 *         // ... handle other events
 *     }
 * }
 * ```
 *
 * ## Tool Calling
 *
 * When the agent wants to call a tool:
 * 1. Receive [VoiceAgentEvent.ToolCallDone]
 * 2. Execute the tool with the provided arguments
 * 3. Send result via [sendToolResult]
 *
 * ```kotlin
 * when (event) {
 *     is VoiceAgentEvent.ToolCallDone -> {
 *         val result = executeMyTool(event.toolName, event.arguments)
 *         session.sendToolResult(event.callId, result)
 *     }
 * }
 * ```
 */
public interface VoiceAgentSession {
    /**
     * Unique identifier for this session.
     */
    public val sessionId: String

    /**
     * Current session configuration.
     */
    public val config: VoiceAgentSessionConfig

    /**
     * Flow of events from the voice agent.
     *
     * Collect this flow to receive all responses, transcriptions,
     * tool calls, and status updates from the agent.
     *
     * The flow completes when the session is closed.
     */
    public val events: Flow<VoiceAgentEvent>

    // ============ Audio Input ============

    /**
     * Send audio data to the voice agent.
     *
     * Audio should be in the format specified by [config.inputAudioFormat].
     * Send audio in small chunks (e.g., 20-100ms) for low latency.
     *
     * @param audio Raw audio bytes (not base64 encoded)
     */
    public suspend fun sendAudio(audio: ByteArray)

    /**
     * Commit the input audio buffer as a user message.
     *
     * Only needed when using [TurnDetection.None] (manual mode).
     * With server VAD, the server commits automatically.
     */
    public suspend fun commitAudio()

    /**
     * Clear the input audio buffer.
     *
     * Discards any audio sent but not yet committed.
     */
    public suspend fun clearInputBuffer()

    // ============ Response Control ============

    /**
     * Cancel the current response being generated.
     *
     * Use this to implement "stop" functionality or to
     * interrupt the agent programmatically.
     */
    public suspend fun cancelResponse()

    /**
     * Trigger response generation.
     *
     * Only needed when using manual turn detection or
     * when you want to force a response without new input.
     */
    public suspend fun createResponse()

    // ============ Session Management ============

    /**
     * Update session configuration.
     *
     * Changes take effect immediately. You can update:
     * - Instructions (change agent behavior mid-conversation)
     * - Voice (switch voices)
     * - Turn detection settings
     * - Tools (add/remove available tools)
     *
     * @param config New configuration (partial updates merge with existing)
     */
    public suspend fun updateSession(config: VoiceAgentSessionConfig)

    // ============ Tool Results ============

    /**
     * Send the result of a tool call back to the agent.
     *
     * After receiving [VoiceAgentEvent.ToolCallDone], execute the tool
     * and send its result here. The agent will use the result to
     * continue generating its response.
     *
     * @param callId The call ID from [VoiceAgentEvent.ToolCallDone]
     * @param result JSON-serialized result of the tool execution
     */
    public suspend fun sendToolResult(callId: String, result: String)

    // ============ Conversation Items ============

    /**
     * Add a text message to the conversation.
     *
     * Use this to inject text into the conversation without audio.
     * Useful for:
     * - Adding context from other sources
     * - Providing information the agent should know
     * - Simulating user input for testing
     *
     * @param role Who sent this message ("user" or "assistant")
     * @param text The message content
     */
    public suspend fun addMessage(role: MessageRole, text: String)

    public enum class MessageRole {
        User, Assistant
    }

    // ============ Lifecycle ============

    /**
     * Wait for the session to fully connect to the voice agent provider.
     *
     * Call this after [VoiceAgentService.createSession] to ensure the WebSocket
     * connection is established before sending audio or messages.
     *
     * This is particularly important in serverless environments (like AWS Lambda)
     * where the function may return before an async connection completes.
     *
     * @throws Exception if connection fails
     */
    public suspend fun awaitConnection()

    /**
     * Close the session and release resources.
     *
     * After calling this, the [events] flow will complete
     * and no further operations are possible.
     */
    public suspend fun close()
}
