package com.lightningkite.services.voiceagent.phonecall

import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.voiceagent.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val logger = KotlinLogging.logger("PhoneCallVoiceAgentBridge")

/**
 * Bridges a phone call audio stream with a voice agent session.
 *
 * This bridge handles:
 * - Audio format conversion (μ-law 8kHz ↔ PCM16 24kHz)
 * - Bidirectional audio streaming
 * - Tool call handling (if a handler is provided)
 * - Graceful session lifecycle management
 *
 * ## Architecture
 *
 * ```
 * Phone Call                    Bridge                      Voice Agent
 * ─────────────────────────────────────────────────────────────────────
 *
 * AudioStreamEvent.Audio  →  [μ-law → PCM16]  →  session.sendAudio()
 *        (8kHz μ-law)                               (24kHz PCM16)
 *
 * AudioStreamCommand.Audio ←  [PCM16 → μ-law] ←  VoiceAgentEvent.AudioDelta
 *        (8kHz μ-law)                               (24kHz PCM16 base64)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // In your WebSocket handler for phone call audio streaming
 * val bridge = PhoneCallVoiceAgentBridge(
 *     voiceAgentService = voiceAgentService,
 *     sessionConfig = VoiceAgentSessionConfig(
 *         instructions = "You are a helpful phone assistant.",
 *         voice = VoiceConfig(name = "alloy"),
 *     ),
 *     toolHandler = { toolName, arguments ->
 *         // Execute tools and return JSON result
 *         executeMyTool(toolName, arguments)
 *     }
 * )
 *
 * // Start the bridge when call connects
 * val commands: Flow<AudioStreamCommand> = bridge.start(incomingAudioEvents)
 *
 * // Send commands back to the phone call
 * commands.collect { command ->
 *     val frame = phoneService.audioStream!!.render(command)
 *     websocket.send(frame)
 * }
 * ```
 *
 * @property voiceAgentService The voice agent service to create sessions from
 * @property sessionConfig Configuration for the voice agent session
 * @property toolHandler Optional handler for tool calls. Returns JSON result string.
 */
@OptIn(ExperimentalEncodingApi::class)
public class PhoneCallVoiceAgentBridge(
    private val voiceAgentService: VoiceAgentService,
    private val sessionConfig: VoiceAgentSessionConfig,
    private val toolHandler: (suspend (toolName: String, arguments: String) -> String)? = null,
) {
    private var session: VoiceAgentSession? = null
    private var streamId: String? = null

    /**
     * Starts the bridge, connecting phone call audio to the voice agent.
     *
     * @param phoneAudioEvents Flow of audio events from the phone call
     * @param scope CoroutineScope for managing the bridge lifecycle
     * @return Flow of commands to send back to the phone call
     */
    public fun start(
        phoneAudioEvents: Flow<AudioStreamEvent>,
        scope: CoroutineScope
    ): Flow<AudioStreamCommand> {
        val commandChannel = Channel<AudioStreamCommand>(Channel.UNLIMITED)

        scope.launch {
            try {
                // Create voice agent session
                val agentSession = voiceAgentService.createSession(sessionConfig.copy(
                    // Ensure we use formats that match our conversion
                    inputAudioFormat = AudioFormat.PCM16_24K,
                    outputAudioFormat = AudioFormat.PCM16_24K,
                ))
                session = agentSession

                logger.info { "Voice agent session created: ${agentSession.sessionId}" }

                // Launch job to handle voice agent events (sends audio back to phone)
                val agentEventsJob = launch {
                    handleAgentEvents(agentSession, commandChannel)
                }

                // Process incoming phone audio
                phoneAudioEvents.collect { event ->
                    handlePhoneEvent(event, agentSession, commandChannel)
                }

                // Phone stream ended
                logger.info { "Phone audio stream ended" }
                agentEventsJob.cancel()
                agentSession.close()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Bridge error" }
            } finally {
                commandChannel.close()
                session?.close()
            }
        }

        return commandChannel.consumeAsFlow()
    }

    /**
     * Handles a single audio event from the phone call.
     */
    private suspend fun handlePhoneEvent(
        event: AudioStreamEvent,
        session: VoiceAgentSession,
        commands: Channel<AudioStreamCommand>
    ) {
        when (event) {
            is AudioStreamEvent.Connected -> {
                streamId = event.streamId
                logger.info { "Phone stream connected: callId=${event.callId}, streamId=${event.streamId}" }
            }

            is AudioStreamEvent.Audio -> {
                // Decode base64 μ-law audio
                val mulawBytes = Base64.decode(event.payload)

                // Convert μ-law 8kHz → PCM16 24kHz
                val pcm16Bytes = AudioConverter.mulawToPcm16_24k(mulawBytes)

                // Send to voice agent
                session.sendAudio(pcm16Bytes)
            }

            is AudioStreamEvent.Dtmf -> {
                logger.debug { "DTMF received: ${event.digit}" }
                // Could add the digit to the conversation via session.addMessage()
                // or handle it specially based on the application
            }

            is AudioStreamEvent.Stop -> {
                logger.info { "Phone stream stopping: ${event.streamId}" }
                // The flow will complete, ending the bridge
            }
        }
    }

    /**
     * Handles events from the voice agent, converting and sending audio back to the phone.
     */
    private suspend fun handleAgentEvents(
        session: VoiceAgentSession,
        commands: Channel<AudioStreamCommand>
    ) {
        session.events.collect { event ->
            when (event) {
                is VoiceAgentEvent.SessionCreated -> {
                    logger.info { "Voice agent session ready" }
                }

                is VoiceAgentEvent.AudioDelta -> {
                    val currentStreamId = streamId ?: return@collect

                    // Decode base64 PCM16 audio from agent
                    val pcm16Bytes = Base64.decode(event.delta)

                    // Convert PCM16 24kHz → μ-law 8kHz
                    val mulawBytes = AudioConverter.pcm16_24kToMulaw(pcm16Bytes)

                    // Encode to base64 for phone
                    val mulawBase64 = Base64.encode(mulawBytes)

                    // Send to phone
                    commands.send(AudioStreamCommand.Audio(currentStreamId, mulawBase64))
                }

                is VoiceAgentEvent.SpeechStarted -> {
                    logger.debug { "User started speaking" }
                    // Could clear agent audio queue if barge-in is desired
                    streamId?.let { commands.send(AudioStreamCommand.Clear(it)) }
                }

                is VoiceAgentEvent.SpeechEnded -> {
                    logger.debug { "User stopped speaking" }
                }

                is VoiceAgentEvent.InputTranscription -> {
                    logger.info { "User said: ${event.text}" }
                }

                is VoiceAgentEvent.TextDelta -> {
                    // Agent is generating text (for logging/display purposes)
                }

                is VoiceAgentEvent.ResponseStarted -> {
                    logger.debug { "Agent starting response" }
                }

                is VoiceAgentEvent.ResponseDone -> {
                    logger.debug { "Agent finished response" }
                    event.usage?.let { usage ->
                        logger.info { "Usage: input=${usage.inputTokens}, output=${usage.outputTokens}" }
                    }
                }

                is VoiceAgentEvent.ToolCallStarted -> {
                    logger.info { "Tool call started: ${event.toolName}" }
                }

                is VoiceAgentEvent.ToolCallDone -> {
                    logger.info { "Tool call: ${event.toolName}(${event.arguments})" }

                    // Execute tool if handler is provided
                    val result = toolHandler?.invoke(event.toolName, event.arguments)
                        ?: """{"error": "No tool handler configured"}"""

                    logger.info { "Tool result: $result" }
                    session.sendToolResult(event.callId, result)
                }

                is VoiceAgentEvent.Error -> {
                    logger.error { "Voice agent error: ${event.code} - ${event.message}" }
                }

                is VoiceAgentEvent.RateLimitsUpdated -> {
                    // Track rate limits if needed
                }

                is VoiceAgentEvent.SessionUpdated,
                is VoiceAgentEvent.InputAudioCommitted,
                is VoiceAgentEvent.InputAudioCleared,
                is VoiceAgentEvent.InputTranscriptionFailed,
                is VoiceAgentEvent.AudioDone,
                is VoiceAgentEvent.TextDone,
                is VoiceAgentEvent.ToolCallArgumentsDelta -> {
                    // Internal events we don't need to surface
                }
            }
        }
    }

    /**
     * Closes the bridge and underlying session.
     */
    public suspend fun close() {
        session?.close()
        session = null
    }
}
