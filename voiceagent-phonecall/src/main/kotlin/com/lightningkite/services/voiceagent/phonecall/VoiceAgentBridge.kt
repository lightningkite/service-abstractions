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
 * @param jitterBufferMs Size of the jitter buffer in milliseconds. Higher values add latency
 *   but smooth out irregular audio delivery (e.g., from DynamoDB PubSub polling). Set to 0
 *   to disable jitter buffering. Default is 300ms.
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
    jitterBufferMs: Long = 300L,
    tracer: Tracer? = null,
) {
    val span = tracer?.spanBuilder("voiceagent.phone_session")
        ?.setSpanKind(SpanKind.SERVER)
        ?.startSpan()

    try {
        val spanScope = span?.makeCurrent()
        try {
            val jitterBuffer = if (jitterBufferMs > 0) AudioJitterBuffer(targetBufferMs = jitterBufferMs) else null

            logger.info { "Phone stream ready: callId=$callId, streamId=$streamId, jitterBuffer=${jitterBufferMs}ms" }
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

            // Track current response ID for interleaving detection
            val currentResponseId = java.util.concurrent.atomic.AtomicReference<String?>(null)
            // Our own sequence counter since OpenAI doesn't provide one
            val audioSeqCounter = java.util.concurrent.atomic.AtomicInteger(0)

            // Process events
            coroutineScope {
                // Run jitter buffer playback (if enabled)
                val jitterJob = jitterBuffer?.let { buffer ->
                    launch {
                        buffer.runPlayback { audio ->
                            sendToPhone(AudioStreamCommand.Audio(streamId, Base64.encode(audio)))
                        }
                    }
                }

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
                                jitterBuffer?.stop()
                                session.close()  // Close session to end events flow
                                cancel()
                            }
                            else -> {}
                        }
                    }
                }

                // Forward agent events to phone (via jitter buffer if enabled)
                session.events.collect { event ->
                    handleAgentEvent(
                        event = event,
                        session = session,
                        streamId = streamId,
                        jitterBuffer = jitterBuffer,
                        sendToPhone = sendToPhone,
                        toolHandler = toolHandler,
                        onTranscript = onTranscript,
                        currentResponseId = currentResponseId,
                        audioSeqCounter = audioSeqCounter,
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
    jitterBuffer: AudioJitterBuffer?,
    sendToPhone: suspend (AudioStreamCommand) -> Unit,
    toolHandler: suspend (toolName: String, arguments: String) -> String,
    onTranscript: suspend (TranscriptEntry) -> Unit,
    currentResponseId: java.util.concurrent.atomic.AtomicReference<String?>,
    audioSeqCounter: java.util.concurrent.atomic.AtomicInteger,
) {
    when (event) {
        is VoiceAgentEvent.AudioDelta -> {
            // Track response ID changes to detect interleaving
            val prevResponseId = currentResponseId.get()
            if (prevResponseId == null) {
                logger.info { "AUDIO new response started: ${event.responseId}" }
            } else if (prevResponseId != event.responseId) {
                // Response ID changed mid-stream - clear buffer to avoid mixing audio from different responses
                logger.warn { "AUDIO responseId changed mid-stream: $prevResponseId -> ${event.responseId}, clearing buffer" }
                jitterBuffer?.clear()
                audioSeqCounter.set(0)
                sendToPhone(AudioStreamCommand.Clear(streamId))
            }
            currentResponseId.set(event.responseId)

            val pcmBytes = Base64.decode(event.delta)
            val mulawBytes = AudioConverter.pcm16_24kToMulaw(pcmBytes)

            if (jitterBuffer != null) {
                // Add to jitter buffer with our own sequence number for ordering
                // (OpenAI doesn't provide a sequence number - contentIndex is always 0)
                val seq = audioSeqCounter.getAndIncrement()
                jitterBuffer.add(seq, mulawBytes)
            } else {
                // No jitter buffer - send immediately
                sendToPhone(AudioStreamCommand.Audio(streamId, Base64.encode(mulawBytes)))
            }
        }
        is VoiceAgentEvent.SpeechStarted -> {
            logger.debug { "User started speaking" }
            currentResponseId.set(null)  // Reset - new response expected
            audioSeqCounter.set(0)  // Reset sequence for new response
            jitterBuffer?.clear()
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
