package com.lightningkite.services.voiceagent

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.uuid.Uuid

/**
 * Console logging implementation of [VoiceAgentService] for development.
 *
 * This implementation logs all operations to the console, making it easy
 * to see what's happening during development. It generates mock responses
 * so you can test the flow without a real provider.
 *
 * ## Usage
 *
 * ```kotlin
 * val settings = VoiceAgentService.Settings("console")
 * val service = settings("dev-voice-agent", context)
 *
 * // All operations will be logged to console
 * val session = service.createSession(config)
 * session.sendAudio(audio) // Logs: [voice-agent] Received 1024 bytes of audio
 * ```
 */
public class ConsoleVoiceAgentService(
    override val name: String,
    override val context: SettingContext,
) : VoiceAgentService {

    override suspend fun createSession(config: VoiceAgentSessionConfig): VoiceAgentSession {
        val sessionId = Uuid.random().toString()
        println("[$name] Creating voice agent session: $sessionId")
        println("[$name]   Instructions: ${config.instructions?.take(100)}...")
        println("[$name]   Voice: ${config.voice}")
        println("[$name]   Input format: ${config.inputAudioFormat}")
        println("[$name]   Output format: ${config.outputAudioFormat}")
        println("[$name]   Turn detection: ${config.turnDetection}")
        println("[$name]   Tools: ${config.tools.map { it.name }}")
        return ConsoleVoiceAgentSession(name, sessionId, config)
    }

    override suspend fun healthCheck(): HealthStatus {
        println("[$name] Health check: OK")
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Console voice agent service")
    }
}

/**
 * Console logging implementation of [VoiceAgentSession].
 */
public class ConsoleVoiceAgentSession(
    private val serviceName: String,
    override val sessionId: String,
    override var config: VoiceAgentSessionConfig,
) : VoiceAgentSession {

    private val eventChannel = Channel<VoiceAgentEvent>(Channel.UNLIMITED)
    private var audioByteCount = 0
    private var isClosed = false
    private var responseCount = 0

    init {
        eventChannel.trySend(VoiceAgentEvent.SessionCreated(sessionId))
    }

    override val events: Flow<VoiceAgentEvent> = eventChannel.receiveAsFlow()

    override suspend fun sendAudio(audio: ByteArray) {
        check(!isClosed) { "Session is closed" }
        audioByteCount += audio.size
        val durationMs = (audio.size.toDouble() / config.inputAudioFormat.bytesPerSecond * 1000).toInt()
        println("[$serviceName] Received ${audio.size} bytes of audio (~${durationMs}ms), total: $audioByteCount bytes")

        // Simulate response after receiving enough audio
        if (audioByteCount >= config.inputAudioFormat.bytesPerSecond) { // ~1 second of audio
            generateMockResponse()
            audioByteCount = 0
        }
    }

    override suspend fun commitAudio() {
        check(!isClosed) { "Session is closed" }
        println("[$serviceName] Audio committed")
        eventChannel.send(VoiceAgentEvent.InputAudioCommitted(Uuid.random().toString()))
        generateMockResponse()
    }

    override suspend fun clearInputBuffer() {
        check(!isClosed) { "Session is closed" }
        println("[$serviceName] Input buffer cleared")
        audioByteCount = 0
        eventChannel.send(VoiceAgentEvent.InputAudioCleared)
    }

    override suspend fun cancelResponse() {
        check(!isClosed) { "Session is closed" }
        println("[$serviceName] Response cancelled")
    }

    override suspend fun createResponse() {
        check(!isClosed) { "Session is closed" }
        println("[$serviceName] Triggering response")
        generateMockResponse()
    }

    override suspend fun updateSession(config: VoiceAgentSessionConfig) {
        check(!isClosed) { "Session is closed" }
        this.config = config
        println("[$serviceName] Session updated")
        println("[$serviceName]   Instructions: ${config.instructions?.take(100)}...")
        eventChannel.send(VoiceAgentEvent.SessionUpdated(config))
    }

    override suspend fun sendToolResult(callId: String, result: String) {
        check(!isClosed) { "Session is closed" }
        println("[$serviceName] Tool result for $callId: ${result.take(100)}...")
    }

    override suspend fun addMessage(role: String, text: String) {
        check(!isClosed) { "Session is closed" }
        println("[$serviceName] Added message [$role]: ${text.take(100)}...")
    }

    override suspend fun close() {
        if (!isClosed) {
            isClosed = true
            println("[$serviceName] Session $sessionId closed")
            eventChannel.close()
        }
    }

    private suspend fun generateMockResponse() {
        responseCount++
        val responseId = Uuid.random().toString()
        val itemId = Uuid.random().toString()
        val text = "This is mock response #$responseCount from the console voice agent."

        println("[$serviceName] Generating mock response: $text")

        eventChannel.send(VoiceAgentEvent.ResponseStarted(responseId))
        eventChannel.send(VoiceAgentEvent.TextDelta(responseId, itemId, 0, text))
        eventChannel.send(VoiceAgentEvent.TextDone(responseId, itemId, 0, text))
        eventChannel.send(VoiceAgentEvent.ResponseDone(responseId, ResponseStatus.COMPLETED))
    }
}
