package com.lightningkite.services.voiceagent

import com.lightningkite.services.*
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Service abstraction for real-time voice AI agents.
 *
 * VoiceAgentService provides a unified interface for creating voice conversations
 * with AI models from different providers (OpenAI Realtime, Google Gemini Live,
 * ElevenLabs Agents, etc.). This enables:
 *
 * - Building voice-based AI assistants
 * - Creating phone call agents (when bridged with PhoneCallService)
 * - Implementing voice interfaces for applications
 *
 * ## Key Concepts
 *
 * - **Session**: A single conversation with bidirectional audio streaming
 * - **Turn Detection**: How the system knows when the user has finished speaking
 * - **Tool Calling**: Agent can invoke functions to take actions or get information
 *
 * ## Available Implementations
 *
 * - **TestVoiceAgentService** (`test`) - Mock for testing
 * - **ConsoleVoiceAgentService** (`console`) - Logs for development
 * - **OpenAIVoiceAgentService** (`openai-realtime://`) - OpenAI Realtime API (requires voiceagent-openai)
 *
 * ## Configuration Example
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val voiceAgent: VoiceAgentService.Settings = VoiceAgentService.Settings(
 *         "openai-realtime://gpt-4o-realtime-preview?apiKey=\${OPENAI_API_KEY}"
 *     )
 * )
 *
 * val context = SettingContext(...)
 * val voiceAgent: VoiceAgentService = settings.voiceAgent("voice-agent", context)
 * ```
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Create a session
 * val session = voiceAgent.createSession(
 *     VoiceAgentSessionConfig(
 *         instructions = "You are a helpful assistant.",
 *         voice = VoiceConfig(name = "alloy"),
 *         tools = listOf(myToolDescriptor),
 *     )
 * )
 *
 * // Handle events in a coroutine
 * launch {
 *     session.events.collect { event ->
 *         when (event) {
 *             is VoiceAgentEvent.AudioDelta -> playAudio(event.delta)
 *             is VoiceAgentEvent.ToolCallDone -> {
 *                 val result = executeTool(event.toolName, event.arguments)
 *                 session.sendToolResult(event.callId, result)
 *             }
 *             is VoiceAgentEvent.Error -> handleError(event)
 *             else -> { /* log or ignore */ }
 *         }
 *     }
 * }
 *
 * // Send audio from microphone
 * microphoneFlow.collect { audio ->
 *     session.sendAudio(audio)
 * }
 *
 * // Close when done
 * session.close()
 * ```
 *
 * ## Phone Call Integration
 *
 * To use voice agents with phone calls, use the voiceagent-phonecall-bridge module
 * which handles audio format conversion and stream bridging:
 *
 * ```kotlin
 * val bridge = VoiceAgentPhoneBridge(voiceAgentService, phoneCallService)
 *
 * // When a call comes in with audio streaming enabled:
 * phoneCallService.audioStream.handleConnection { start, incoming, outgoing ->
 *     bridge.handlePhoneAudioStream(start, sessionConfig, incoming, outgoing)
 * }
 * ```
 *
 * @see VoiceAgentSession for session operations
 * @see VoiceAgentSessionConfig for configuration options
 * @see VoiceAgentEvent for event types
 */
public interface VoiceAgentService : Service {

    /**
     * Configuration for instantiating a VoiceAgentService.
     *
     * ## URL Format
     *
     * Uses standard URI format: `scheme://model?param=value`
     *
     * The URL scheme determines the provider:
     * - `test` - Mock service for testing (default)
     * - `console` - Logging service for development
     * - `openai-realtime://model?apiKey=...` - OpenAI Realtime API
     * - `gemini-live://model?apiKey=...` - Google Gemini Live (planned)
     * - `elevenlabs://agentId?apiKey=...` - ElevenLabs Agents (planned)
     *
     * ## Examples
     *
     * ```
     * test                                                      // Mock
     * openai-realtime://gpt-4o-realtime-preview                 // OpenAI with env key
     * openai-realtime://gpt-4o-realtime-preview?apiKey=sk-...   // OpenAI with explicit key
     * ```
     *
     * @property url Connection string defining the provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "test"
    ) : Setting<VoiceAgentService> {
        public companion object : UrlSettingParser<VoiceAgentService>() {
            /**
             * Creates settings for OpenAI Realtime API.
             *
             * @param model Model to use (e.g., "gpt-4o-realtime-preview")
             * @param apiKey API key. Uses OPENAI_API_KEY env var if null.
             */
            public fun openaiRealtime(
                model: String = "gpt-4o-realtime-preview",
                apiKey: String? = null,
            ): Settings = Settings(
                "openai-realtime://$model" + (apiKey?.let { "?apiKey=$it" } ?: "")
            )

            init {
                register("test") { name, _, context -> TestVoiceAgentService(name, context) }
                register("console") { name, _, context -> ConsoleVoiceAgentService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): VoiceAgentService {
            return parse(name, url, context)
        }
    }

    /**
     * Create a new voice agent session.
     *
     * This establishes a WebSocket connection to the voice agent provider
     * and returns a session for bidirectional audio streaming.
     *
     * @param config Session configuration (instructions, voice, tools, etc.)
     * @return A live session for voice interaction
     * @throws VoiceAgentException if session creation fails
     */
    public suspend fun createSession(config: VoiceAgentSessionConfig = VoiceAgentSessionConfig()): VoiceAgentSession

    override val healthCheckFrequency: Duration get() = 5.minutes

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Voice agent service ready")
    }
}

/**
 * Exception thrown when voice agent operations fail.
 *
 * Common causes:
 * - Connection failure
 * - Authentication error
 * - Invalid configuration
 * - Rate limit exceeded
 * - Provider error
 */
public class VoiceAgentException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
