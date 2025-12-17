package com.lightningkite.services.voiceagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Events emitted by a voice agent session.
 *
 * These events are received via [VoiceAgentSession.events] flow and represent
 * all state changes and outputs from the voice agent.
 *
 * ## Event Flow (typical conversation)
 *
 * 1. [SessionCreated] - Session established
 * 2. [SpeechStarted] - User begins speaking (if VAD enabled)
 * 3. [SpeechEnded] - User stops speaking
 * 4. [InputAudioCommitted] - User's turn committed
 * 5. [InputTranscription] - User's speech transcribed (if enabled)
 * 6. [ResponseStarted] - Agent begins generating response
 * 7. [AudioDelta] (multiple) - Audio chunks for playback
 * 8. [TextDelta] (multiple) - Transcript of agent speech
 * 9. [AudioDone] - Audio generation complete
 * 10. [ResponseDone] - Response complete
 *
 * ## Tool Calling Flow
 *
 * When the agent calls a tool:
 * 1. [ToolCallStarted] - Tool invocation begins
 * 2. [ToolCallArgumentsDelta] (multiple) - Streaming argument JSON
 * 3. [ToolCallDone] - Arguments complete, execute the tool
 * 4. Client sends result via [VoiceAgentSession.sendToolResult]
 * 5. Agent generates response incorporating tool result
 */
@Serializable
public sealed class VoiceAgentEvent {

    // ============ Session Lifecycle ============

    /**
     * Session has been created and is ready.
     *
     * This is the first event after connecting.
     */
    @Serializable
    @SerialName("session.created")
    public data class SessionCreated(
        val sessionId: String,
    ) : VoiceAgentEvent()

    /**
     * Session configuration has been updated.
     *
     * Sent in response to [VoiceAgentSession.updateSession].
     */
    @Serializable
    @SerialName("session.updated")
    public data class SessionUpdated(
        val config: VoiceAgentSessionConfig,
    ) : VoiceAgentEvent()

    // ============ Input Audio Events ============

    /**
     * User speech detected (VAD activated).
     *
     * Only sent when using server-side VAD.
     */
    @Serializable
    @SerialName("input.speech_started")
    public data class SpeechStarted(
        val audioOffsetMs: Long,
    ) : VoiceAgentEvent()

    /**
     * User speech ended (VAD detected silence).
     *
     * Only sent when using server-side VAD.
     */
    @Serializable
    @SerialName("input.speech_ended")
    public data class SpeechEnded(
        val audioOffsetMs: Long,
    ) : VoiceAgentEvent()

    /**
     * User's audio input has been committed as a conversation item.
     *
     * After this event, the audio becomes part of the conversation
     * and a response will be generated (if auto-response is enabled).
     */
    @Serializable
    @SerialName("input.audio_committed")
    public data class InputAudioCommitted(
        val itemId: String,
    ) : VoiceAgentEvent()

    /**
     * Input audio buffer has been cleared.
     */
    @Serializable
    @SerialName("input.audio_cleared")
    public data object InputAudioCleared : VoiceAgentEvent()

    // ============ Transcription Events ============

    /**
     * Transcription of user's speech.
     *
     * Only sent when [VoiceAgentSessionConfig.inputTranscription] is configured.
     */
    @Serializable
    @SerialName("input.transcription")
    public data class InputTranscription(
        val itemId: String,
        val text: String,
        val isFinal: Boolean,
    ) : VoiceAgentEvent()

    /**
     * Transcription failed.
     */
    @Serializable
    @SerialName("input.transcription_failed")
    public data class InputTranscriptionFailed(
        val itemId: String,
        val error: String,
    ) : VoiceAgentEvent()

    // ============ Response Lifecycle ============

    /**
     * Agent has started generating a response.
     */
    @Serializable
    @SerialName("response.started")
    public data class ResponseStarted(
        val responseId: String,
    ) : VoiceAgentEvent()

    /**
     * Agent's response is complete.
     */
    @Serializable
    @SerialName("response.done")
    public data class ResponseDone(
        val responseId: String,
        val status: ResponseStatus,
        val usage: UsageStats? = null,
    ) : VoiceAgentEvent()

    // ============ Audio Output Events ============

    /**
     * Chunk of audio output from the agent.
     *
     * Audio is base64-encoded in the configured output format.
     * Play these chunks sequentially for smooth audio output.
     */
    @Serializable
    @SerialName("response.audio_delta")
    public data class AudioDelta(
        val responseId: String,
        val itemId: String,
        val contentIndex: Int,
        val delta: String, // Base64-encoded audio
    ) : VoiceAgentEvent()

    /**
     * Audio output for a content part is complete.
     */
    @Serializable
    @SerialName("response.audio_done")
    public data class AudioDone(
        val responseId: String,
        val itemId: String,
        val contentIndex: Int,
    ) : VoiceAgentEvent()

    // ============ Text Output Events ============

    /**
     * Chunk of transcript for agent's speech.
     *
     * These deltas accumulate to form the full transcript.
     * Useful for displaying captions in real-time.
     */
    @Serializable
    @SerialName("response.text_delta")
    public data class TextDelta(
        val responseId: String,
        val itemId: String,
        val contentIndex: Int,
        val delta: String,
    ) : VoiceAgentEvent()

    /**
     * Text transcript for a content part is complete.
     */
    @Serializable
    @SerialName("response.text_done")
    public data class TextDone(
        val responseId: String,
        val itemId: String,
        val contentIndex: Int,
        val text: String,
    ) : VoiceAgentEvent()

    // ============ Tool Calling Events ============

    /**
     * Agent is calling a tool.
     */
    @Serializable
    @SerialName("response.tool_call_started")
    public data class ToolCallStarted(
        val responseId: String,
        val itemId: String,
        val callId: String,
        val toolName: String,
    ) : VoiceAgentEvent()

    /**
     * Streaming chunk of tool call arguments.
     *
     * Arguments are streamed as JSON. Concatenate all deltas
     * to get the complete arguments JSON.
     */
    @Serializable
    @SerialName("response.tool_call_arguments_delta")
    public data class ToolCallArgumentsDelta(
        val callId: String,
        val delta: String,
    ) : VoiceAgentEvent()

    /**
     * Tool call arguments are complete.
     *
     * The client should now:
     * 1. Parse [arguments] as JSON
     * 2. Execute the tool
     * 3. Send result via [VoiceAgentSession.sendToolResult]
     */
    @Serializable
    @SerialName("response.tool_call_done")
    public data class ToolCallDone(
        val callId: String,
        val toolName: String,
        val arguments: String, // JSON string
    ) : VoiceAgentEvent()

    // ============ Error Events ============

    /**
     * An error occurred.
     *
     * Errors may be recoverable (rate limit, temporary failure)
     * or fatal (invalid configuration, authentication failure).
     */
    @Serializable
    @SerialName("error")
    public data class Error(
        val code: String,
        val message: String,
        val details: String? = null,
    ) : VoiceAgentEvent()

    // ============ Rate Limit Events ============

    /**
     * Rate limit information updated.
     *
     * Sent at the start of each response with current rate limits.
     */
    @Serializable
    @SerialName("rate_limits")
    public data class RateLimitsUpdated(
        val limits: List<RateLimitInfo>,
    ) : VoiceAgentEvent()
}

/**
 * Status of a response.
 */
@Serializable
public enum class ResponseStatus {
    /** Response is currently being generated */
    IN_PROGRESS,
    /** Response completed successfully */
    COMPLETED,
    /** Response was interrupted (user spoke) */
    INCOMPLETE,
    /** Response was cancelled by client */
    CANCELLED,
    /** Response generation failed */
    FAILED,
}

/**
 * Token usage statistics.
 */
@Serializable
public data class UsageStats(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val inputAudioTokens: Int? = null,
    val outputAudioTokens: Int? = null,
    val inputTextTokens: Int? = null,
    val outputTextTokens: Int? = null,
)

/**
 * Rate limit information for a specific resource.
 */
@Serializable
public data class RateLimitInfo(
    val name: String,
    val limit: Int,
    val remaining: Int,
    val resetSeconds: Int,
)
