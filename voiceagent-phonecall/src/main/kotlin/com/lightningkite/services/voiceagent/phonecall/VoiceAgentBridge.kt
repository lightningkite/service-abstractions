package com.lightningkite.services.voiceagent.phonecall

import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.voiceagent.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("VoiceAgentBridge")

/**
 * Creates phone call instructions to connect to a voice agent via WebSocket.
 *
 * @param websocketUrl The WebSocket URL for the voice agent
 * @param greeting Optional greeting to say before connecting (if using TTS before LLM)
 * @param customParameters Custom parameters to include in the stream request
 */
public fun createVoiceAgentStreamInstructions(
    websocketUrl: String,
    greeting: String? = null,
    customParameters: Map<String, String> = emptyMap(),
): com.lightningkite.services.phonecall.CallInstructions {
    val streamInstruction = com.lightningkite.services.phonecall.CallInstructions.StreamAudio(
        websocketUrl = websocketUrl,
        track = com.lightningkite.services.phonecall.AudioTrack.INBOUND,
        customParameters = customParameters,
    )

    return if (greeting != null) {
        com.lightningkite.services.phonecall.CallInstructions.Say(
            text = greeting,
            then = streamInstruction,
        )
    } else {
        streamInstruction
    }
}

/**
 * Role of the speaker in a transcript entry.
 */
public enum class TranscriptRole {
    USER,
    AGENT
}

/**
 * A transcript entry representing a complete message in the conversation.
 */
public data class TranscriptEntry(
    val role: TranscriptRole,
    val text: String,
)

/**
 * Buffer that smooths out audio playback by tracking accumulated audio duration
 * and delaying sends if we're ahead of real-time.
 */
public class AudioPlaybackBuffer {
    private val timeSource = TimeSource.Monotonic
    private var startMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var accumulatedAudioMs: Long = 0
    private var cleared = false

    /**
     * Queue audio for playback, returning the delay needed before sending.
     * @param audioBytes Number of µ-law bytes (duration = bytes / 8 ms)
     * @return Delay in milliseconds to wait before sending this audio
     */
    public fun queueAudio(audioBytes: Int): Long {
        val durationMs = audioBytes / 8L

        if (cleared) {
            cleared = false
            startMark = null
            accumulatedAudioMs = 0
        }

        val mark = startMark ?: timeSource.markNow().also { startMark = it }
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        val aheadMs = accumulatedAudioMs - elapsedMs
        accumulatedAudioMs += durationMs

        return maxOf(0, aheadMs)
    }

    /**
     * Clear the buffer (e.g., when user starts speaking).
     */
    public fun clear() {
        cleared = true
    }
}

/**
 * Handles a phone call voice agent session.
 *
 * This is a simplified bridge function that connects phone audio streams to voice agents.
 * All the complexity of PubSub routing and Lambda coordination is handled by the caller
 * (using CoroutineWebsocketHandler).
 *
 * @param voiceAgentService The voice agent service to create sessions from
 * @param sessionConfig Configuration for the voice agent session
 * @param streamId The stream ID from the phone provider (received in Connected event)
 * @param callId The call ID from the phone provider (received in Connected event)
 * @param phoneAudioEvents Flow of audio events from the phone provider (Twilio)
 * @param sendToPhone Function to send audio commands back to the phone
 * @param toolHandler Handler for tool calls from the agent
 * @param onTranscript Callback for transcript entries
 * @param onStreamConnected Callback when phone stream is connected (for triggering greeting)
 * @param tracer Optional OpenTelemetry tracer
 */
@OptIn(ExperimentalEncodingApi::class)
public suspend fun handlePhoneVoiceSession(
    voiceAgentService: VoiceAgentService,
    sessionConfig: VoiceAgentSessionConfig,
    streamId: String,
    callId: String,
    phoneAudioEvents: Flow<AudioStreamEvent>,
    sendToPhone: suspend (AudioStreamCommand) -> Unit,
    toolHandler: suspend (toolName: String, arguments: String) -> String,
    onTranscript: suspend (TranscriptEntry) -> Unit = {},
    onStreamConnected: suspend (VoiceAgentSession) -> Unit = {},
    tracer: Tracer? = null,
) {
    val span = tracer?.spanBuilder("voiceagent.phone_session")
        ?.setSpanKind(SpanKind.SERVER)
        ?.startSpan()

    try {
        val spanScope = span?.makeCurrent()
        try {
            val audioBuffer = AudioPlaybackBuffer()

            logger.info { "Phone stream ready: callId=$callId, streamId=$streamId" }
            span?.setAttribute("voiceagent.call_id", callId)
            span?.setAttribute("voiceagent.stream_id", streamId)

            // Create voice agent session with phone-appropriate audio format
            val effectiveConfig = sessionConfig.copy(
                inputAudioFormat = AudioFormat.PCM16_24K,
                outputAudioFormat = AudioFormat.PCM16_24K,
            )

            logger.info { "Creating voice agent session..." }
            val session = voiceAgentService.createSession(effectiveConfig)
            session.awaitConnection()
            logger.info { "Voice agent session connected: ${session.sessionId}" }
            span?.setAttribute("voiceagent.session_id", session.sessionId)

            // Trigger greeting
            onStreamConnected(session)

            // Process events
            coroutineScope {
                // Forward phone audio to agent
                launch {
                    phoneAudioEvents.collect { event ->
                        when (event) {
                            is AudioStreamEvent.Audio -> {
                                val mulawBytes = Base64.decode(event.payload)
                                val pcmBytes = AudioConverter.mulawToPcm16_24k(mulawBytes)
                                session.sendAudio(pcmBytes)
                            }
                            is AudioStreamEvent.Stop -> {
                                logger.info { "Phone stream stopped: ${event.streamId}" }
                                session.close()  // Close session to end events flow
                                cancel()
                            }
                            else -> {}
                        }
                    }
                }

                // Forward agent events to phone
                session.events.collect { event ->
                    handleAgentEvent(
                        event = event,
                        session = session,
                        streamId = streamId,
                        audioBuffer = audioBuffer,
                        sendToPhone = sendToPhone,
                        toolHandler = toolHandler,
                        onTranscript = onTranscript,
                    )
                }
            }
            span?.setStatus(StatusCode.OK)
        } finally {
            spanScope?.close()
        }
    } catch (e: CancellationException) {
        span?.setStatus(StatusCode.OK, "Session cancelled")
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Phone voice session error" }
        span?.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}

/**
 * Handles a direct voice agent session (browser/app WebSocket).
 *
 * Similar to phone sessions but without audio format conversion.
 *
 * @param voiceAgentService The voice agent service
 * @param sessionConfig Configuration for the voice agent session
 * @param incomingAudio Flow of PCM16 audio from the client (base64 encoded)
 * @param sendAudio Function to send audio back to the client (base64 encoded PCM16)
 * @param toolHandler Handler for tool calls
 * @param onTranscript Callback for transcripts
 * @param onSessionReady Callback when session is ready (for triggering greeting)
 * @param tracer Optional OpenTelemetry tracer
 */
@OptIn(ExperimentalEncodingApi::class)
public suspend fun handleDirectVoiceSession(
    voiceAgentService: VoiceAgentService,
    sessionConfig: VoiceAgentSessionConfig,
    incomingAudio: Flow<String>,  // Base64 PCM16
    sendAudio: suspend (String) -> Unit,  // Base64 PCM16
    sendClear: suspend () -> Unit,
    toolHandler: suspend (toolName: String, arguments: String) -> String,
    onTranscript: suspend (TranscriptEntry) -> Unit = {},
    onSessionReady: suspend (VoiceAgentSession) -> Unit = {},
    tracer: Tracer? = null,
) {
    val span = tracer?.spanBuilder("voiceagent.direct_session")
        ?.setSpanKind(SpanKind.SERVER)
        ?.startSpan()

    try {
        val spanScope = span?.makeCurrent()
        try {
            logger.info { "Creating direct voice agent session..." }
            val session = voiceAgentService.createSession(sessionConfig)
            session.awaitConnection()
            logger.info { "Voice agent session connected: ${session.sessionId}" }
            span?.setAttribute("voiceagent.session_id", session.sessionId)

            // Trigger greeting
            onSessionReady(session)

            // Process events
            coroutineScope {
                // Forward client audio to agent
                launch {
                    incomingAudio.collect { base64Audio ->
                        val audioBytes = Base64.decode(base64Audio)
                        session.sendAudio(audioBytes)
                    }
                }

                // Forward agent events to client
                session.events.collect { event ->
                    when (event) {
                        is VoiceAgentEvent.AudioDelta -> {
                            sendAudio(event.delta)
                        }
                        is VoiceAgentEvent.SpeechStarted -> {
                            sendClear()
                        }
                        is VoiceAgentEvent.ToolCallDone -> {
                            logger.info { "Tool call: ${event.toolName}(${event.arguments})" }
                            val result = toolHandler(event.toolName, event.arguments)
                            logger.info { "Tool result: $result" }
                            session.sendToolResult(event.callId, result)
                        }
                        is VoiceAgentEvent.InputTranscription -> {
                            if (event.isFinal && event.text.isNotBlank()) {
                                logger.info { "User said: ${event.text}" }
                                onTranscript(TranscriptEntry(TranscriptRole.USER, event.text))
                            }
                        }
                        is VoiceAgentEvent.TextDone -> {
                            if (event.text.isNotBlank()) {
                                logger.info { "Agent said: ${event.text}" }
                                onTranscript(TranscriptEntry(TranscriptRole.AGENT, event.text))
                            }
                        }
                        is VoiceAgentEvent.Error -> {
                            logger.error { "Voice agent error: ${event.code} - ${event.message}" }
                        }
                        else -> {}
                    }
                }
            }

            span?.setStatus(StatusCode.OK)
        } finally {
            spanScope?.close()
        }
    } catch (e: CancellationException) {
        span?.setStatus(StatusCode.OK, "Session cancelled")
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Direct voice session error" }
        span?.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun handleAgentEvent(
    event: VoiceAgentEvent,
    session: VoiceAgentSession,
    streamId: String,
    audioBuffer: AudioPlaybackBuffer,
    sendToPhone: suspend (AudioStreamCommand) -> Unit,
    toolHandler: suspend (toolName: String, arguments: String) -> String,
    onTranscript: suspend (TranscriptEntry) -> Unit,
) {
    when (event) {
        is VoiceAgentEvent.AudioDelta -> {
            val pcmBytes = Base64.decode(event.delta)
            val mulawBytes = AudioConverter.pcm16_24kToMulaw(pcmBytes)

            // Smooth playback timing
            val delayMs = audioBuffer.queueAudio(mulawBytes.size)
            if (delayMs > 0) {
                delay(delayMs)
            }

            sendToPhone(AudioStreamCommand.Audio(streamId, Base64.encode(mulawBytes)))
        }
        is VoiceAgentEvent.SpeechStarted -> {
            logger.debug { "User started speaking" }
            audioBuffer.clear()
            sendToPhone(AudioStreamCommand.Clear(streamId))
        }
        is VoiceAgentEvent.SpeechEnded -> {
            logger.debug { "User stopped speaking" }
        }
        is VoiceAgentEvent.ToolCallDone -> {
            logger.info { "Tool call: ${event.toolName}(${event.arguments})" }
            val result = toolHandler(event.toolName, event.arguments)
            logger.info { "Tool result: $result" }
            session.sendToolResult(event.callId, result)
        }
        is VoiceAgentEvent.InputTranscription -> {
            if (event.isFinal && event.text.isNotBlank()) {
                logger.info { "User said: ${event.text}" }
                onTranscript(TranscriptEntry(TranscriptRole.USER, event.text))
            }
        }
        is VoiceAgentEvent.TextDone -> {
            if (event.text.isNotBlank()) {
                logger.info { "Agent said: ${event.text}" }
                onTranscript(TranscriptEntry(TranscriptRole.AGENT, event.text))
            }
        }
        is VoiceAgentEvent.ResponseDone -> {
            event.usage?.let { usage ->
                logger.info { "Usage: input=${usage.inputTokens}, output=${usage.outputTokens}" }
            }
        }
        is VoiceAgentEvent.Error -> {
            logger.error { "Voice agent error: ${event.code} - ${event.message}" }
        }
        else -> {}
    }
}
