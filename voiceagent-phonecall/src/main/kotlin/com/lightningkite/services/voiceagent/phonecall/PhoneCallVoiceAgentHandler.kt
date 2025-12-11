package com.lightningkite.services.voiceagent.phonecall

import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.phonecall.AudioStreamStart
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.voiceagent.VoiceAgentService
import com.lightningkite.services.voiceagent.VoiceAgentSession
import com.lightningkite.services.voiceagent.VoiceAgentSessionConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("PhoneCallVoiceAgentHandler")

/**
 * High-level handler for phone call → voice agent integration.
 *
 * This class manages the complete lifecycle of bridging phone calls with voice agents,
 * including WebSocket message parsing/rendering.
 *
 * ## Usage with Ktor WebSocket
 *
 * ```kotlin
 * // Configuration
 * val voiceAgentService = VoiceAgentService.Settings("openai-realtime://...")("agent", context)
 * val phoneService = PhoneCallService.Settings("twilio://...")("phone", context)
 *
 * // WebSocket endpoint
 * webSocket("/voice-ai") {
 *     val handler = PhoneCallVoiceAgentHandler(
 *         voiceAgentService = voiceAgentService,
 *         audioStreamAdapter = phoneService.audioStream!!,
 *         sessionConfig = VoiceAgentSessionConfig(
 *             instructions = "You are a helpful phone assistant.",
 *             voice = VoiceConfig(name = "alloy"),
 *             tools = myTools,
 *         ),
 *         toolHandler = { name, args -> executeMyTool(name, args) }
 *     )
 *
 *     handler.handle(
 *         scope = this,
 *         incomingFrames = incoming.consumeAsFlow().mapNotNull {
 *             (it as? Frame.Text)?.let { WebsocketAdapter.Frame.Text(it.readText()) }
 *         },
 *         sendFrame = { frame ->
 *             when (frame) {
 *                 is WebsocketAdapter.Frame.Text -> send(Frame.Text(frame.text))
 *                 is WebsocketAdapter.Frame.Binary -> send(Frame.Binary(true, frame.bytes))
 *             }
 *         }
 *     )
 * }
 * ```
 */
public class PhoneCallVoiceAgentHandler(
    private val voiceAgentService: VoiceAgentService,
    private val audioStreamAdapter: WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand>,
    private val sessionConfig: VoiceAgentSessionConfig,
    private val toolHandler: (suspend (toolName: String, arguments: String) -> String)? = null,
) {
    /**
     * Handles a WebSocket connection, bridging phone audio with the voice agent.
     *
     * @param scope Coroutine scope for the connection lifecycle
     * @param incomingFrames Flow of raw WebSocket frames from the phone provider
     * @param sendFrame Function to send frames back to the phone provider
     */
    public suspend fun handle(
        scope: CoroutineScope,
        incomingFrames: Flow<WebsocketAdapter.Frame>,
        sendFrame: suspend (WebsocketAdapter.Frame) -> Unit,
    ) {
        // Channel for parsed audio events
        val audioEventChannel = Channel<AudioStreamEvent>(Channel.UNLIMITED)

        // Create the bridge
        val bridge = PhoneCallVoiceAgentBridge(
            voiceAgentService = voiceAgentService,
            sessionConfig = sessionConfig,
            toolHandler = toolHandler,
        )

        // Start the bridge and get commands flow
        val commands = bridge.start(
            phoneAudioEvents = audioEventChannel.consumeAsFlow(),
            scope = scope,
        )

        // Job to send commands back to phone
        val sendJob = scope.launch {
            commands.collect { command ->
                val frame = audioStreamAdapter.render(command)
                sendFrame(frame)
            }
        }

        // Process incoming frames
        try {
            incomingFrames.collect { frame ->
                val event = audioStreamAdapter.parse(frame)
                audioEventChannel.send(event)
            }
        } finally {
            audioEventChannel.close()
            sendJob.cancel()
            bridge.close()
        }
    }
}

/**
 * Creates call instructions to start a voice agent session via audio streaming.
 *
 * This is a convenience function for setting up the incoming call webhook response.
 *
 * ## Usage
 *
 * ```kotlin
 * // In your incoming call webhook handler
 * post("/webhooks/incoming-call") {
 *     val event = phoneService.onIncomingCall.parseWebhook(...)
 *
 *     val instructions = createVoiceAgentStreamInstructions(
 *         websocketUrl = "wss://myserver.com/voice-ai",
 *         greeting = "Hello! I'm your AI assistant. How can I help you today?",
 *     )
 *
 *     call.respondText(
 *         phoneService.renderInstructions(instructions),
 *         ContentType.Application.Xml
 *     )
 * }
 * ```
 *
 * @param websocketUrl WebSocket URL for the audio stream (wss://)
 * @param greeting Optional greeting message spoken before connecting to AI
 * @param customParameters Additional parameters passed to the WebSocket connection
 */
public fun createVoiceAgentStreamInstructions(
    websocketUrl: String,
    greeting: String? = null,
    customParameters: Map<String, String> = emptyMap(),
): com.lightningkite.services.phonecall.CallInstructions {
    // Note: When using <Connect><Stream>, Twilio only supports "inbound_track".
    // The bidirectional audio is achieved by sending audio back through the same WebSocket.
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
 * Lambda-compatible handler for phone call → voice agent integration using PubSub.
 *
 * This handler is designed for serverless environments (AWS Lambda + API Gateway WebSockets)
 * where each WebSocket event may be handled by a different Lambda instance. It uses PubSub
 * to route audio from `onMessage` calls to the session started in `onConnect`.
 *
 * ## Architecture
 *
 * ```
 * Lambda Instance A              PubSub                Lambda Instance B
 * ┌────────────────┐     ┌─────────────────┐     ┌────────────────────┐
 * │ onConnect()    │     │                 │     │ onMessage()        │
 * │                │     │ phone-audio:id  │     │                    │
 * │ Creates        │◄────┤ (audio payload) │◄────┤ Publishes audio    │
 * │ VoiceAgent     │     │                 │     │ to PubSub          │
 * │ session        │     └─────────────────┘     └────────────────────┘
 * │                │
 * │ Subscribes to  │
 * │ PubSub channel │
 * └────────────────┘
 * ```
 *
 * ## Usage with Lightning Server
 *
 * ```kotlin
 * val handler = PubSubVoiceAgentHandler(
 *     voiceAgentService = voiceAgentService,
 *     pubsub = pubsub,
 *     audioStreamAdapter = phoneService.audioStream!!,
 *     sessionConfig = VoiceAgentSessionConfig(...),
 *     toolHandler = { name, args -> executeTool(name, args) },
 *     onTranscript = { entry ->
 *         println("${entry.role}: ${entry.text}")
 *         // Save to database, send to webhook, etc.
 *     }
 * )
 *
 * val voiceAgentWebSocket = path.path("voice-ai") bind WebSocketHandler(
 *     willConnect = { handler.createState() },
 *     didConnect = { handler.onConnect(currentState) { send(it) } },
 *     messageFromClient = { handler.onMessage(currentState, frameData.text) },
 *     disconnect = { handler.onDisconnect(currentState) }
 * )
 * ```
 *
 * @property voiceAgentService The voice agent service to create sessions from
 * @property pubsub PubSub service for cross-instance communication
 * @property audioStreamAdapter Adapter for parsing/rendering phone audio stream messages
 * @property sessionConfig Configuration for the voice agent session
 * @property toolHandler Optional handler for tool calls
 * @property onTranscript Optional callback for transcript entries (user and agent messages).
 *   If provided, input transcription is automatically enabled.
 */
/**
 * Role of the speaker in a transcript entry.
 */
public enum class TranscriptRole {
    /** The user/caller */
    USER,
    /** The AI agent */
    AGENT
}

/**
 * A transcript entry representing a complete message in the conversation.
 */
public data class TranscriptEntry(
    /** Who said this */
    val role: TranscriptRole,
    /** The transcribed text */
    val text: String,
)

@OptIn(ExperimentalEncodingApi::class)
public class PubSubVoiceAgentHandler(
    private val voiceAgentService: VoiceAgentService,
    private val pubsub: PubSub,
    private val audioStreamAdapter: WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand>,
    private val sessionConfig: VoiceAgentSessionConfig,
    private val toolHandler: (suspend (toolName: String, arguments: String) -> String)? = null,
    /**
     * Optional callback invoked when a complete transcript entry is available.
     * Called for both user speech (after transcription) and agent responses.
     */
    private val onTranscript: (suspend (TranscriptEntry) -> Unit)? = null,
) {
    /**
     * State tracked for each WebSocket connection.
     */
    public class ConnectionState(
        /** Unique identifier for this connection, used as PubSub channel key */
        public val connectionId: String = Uuid.random().toString(),
        /** Stream ID from the phone provider (set when Connected event received) */
        public var streamId: String? = null,
        /** Call ID from the phone provider */
        public var callId: String? = null,
        /** The voice agent session */
        public var session: VoiceAgentSession? = null,
        /** Job listening to PubSub for incoming audio */
        public var audioJob: Job? = null,
        /** Job handling voice agent events */
        public var agentJob: Job? = null,
        /** Coroutine scope for this connection */
        public val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    )

    /**
     * Creates initial connection state. Call this in `willConnect`.
     */
    public fun createState(): ConnectionState {
        val state = ConnectionState()
        logger.info { "Voice agent WebSocket connecting: connectionId=${state.connectionId}" }
        return state
    }

    /**
     * Handles WebSocket connection establishment. Call this in `didConnect`.
     *
     * This creates the voice agent session and starts listening for:
     * 1. Phone audio via PubSub (from onMessage calls, possibly on other instances)
     * 2. Voice agent events (to send audio back to phone)
     *
     * @param state The connection state from createState()
     * @param sendFrame Function to send WebSocket frames back to the phone
     */
    public suspend fun onConnect(
        state: ConnectionState,
        sendFrame: suspend (String) -> Unit,
    ) {
        val connectionId = state.connectionId
        logger.info { "Voice agent WebSocket connected: connectionId=$connectionId" }

        // Create voice agent session
        // Enable input transcription if transcript callback is provided and not already configured
        val effectiveConfig = sessionConfig.copy(
            inputAudioFormat = com.lightningkite.services.voiceagent.AudioFormat.PCM16_24K,
            outputAudioFormat = com.lightningkite.services.voiceagent.AudioFormat.PCM16_24K,
            inputTranscription = sessionConfig.inputTranscription
                ?: if (onTranscript != null) com.lightningkite.services.voiceagent.TranscriptionConfig() else null,
        )
        val session = voiceAgentService.createSession(effectiveConfig)
        state.session = session
        logger.info { "Voice agent session created: ${session.sessionId}" }

        // Subscribe to phone audio via PubSub
        val audioChannel = pubsub.string("phone-audio:$connectionId")
        state.audioJob = state.scope.launch {
            audioChannel.collect { audioBase64 ->
                val mulawBytes = Base64.decode(audioBase64)
                val pcm16Bytes = AudioConverter.mulawToPcm16_24k(mulawBytes)
                session.sendAudio(pcm16Bytes)
            }
        }

        // Handle voice agent events
        state.agentJob = state.scope.launch {
            session.events.collect { event ->
                handleAgentEvent(event, state, session, sendFrame)
            }
        }
    }

    /**
     * Handles an incoming WebSocket message. Call this in `messageFromClient`.
     *
     * Parses the audio stream event and publishes audio to PubSub for the
     * session handler (which may be on a different Lambda instance) to receive.
     *
     * @param state The connection state
     * @param frameText The raw WebSocket frame text
     */
    public suspend fun onMessage(state: ConnectionState, frameText: String) {
        val frame = WebsocketAdapter.Frame.Text(frameText)
        try {
            when (val event = audioStreamAdapter.parse(frame)) {
                is AudioStreamEvent.Connected -> {
                    logger.info { "Audio stream connected: callId=${event.callId}, streamId=${event.streamId}" }
                    state.streamId = event.streamId
                    state.callId = event.callId
                }
                is AudioStreamEvent.Audio -> {
                    // Publish to PubSub - the onConnect handler will receive it
                    pubsub.string("phone-audio:${state.connectionId}").emit(event.payload)
                }
                is AudioStreamEvent.Dtmf -> {
                    logger.debug { "DTMF received: ${event.digit}" }
                }
                is AudioStreamEvent.Stop -> {
                    logger.info { "Audio stream stopping: ${event.streamId}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error parsing audio stream message" }
        }
    }

    /**
     * Handles WebSocket disconnection. Call this in `disconnect`.
     *
     * @param state The connection state
     */
    public fun onDisconnect(state: ConnectionState) {
        logger.info { "Voice agent WebSocket disconnected: connectionId=${state.connectionId}" }
        state.agentJob?.cancel()
        state.audioJob?.cancel()
        state.scope.launch { state.session?.close() }
        state.scope.cancel()
    }

    private suspend fun handleAgentEvent(
        event: com.lightningkite.services.voiceagent.VoiceAgentEvent,
        state: ConnectionState,
        session: VoiceAgentSession,
        sendFrame: suspend (String) -> Unit,
    ) {
        when (event) {
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.SessionCreated -> {
                logger.info { "Voice agent session ready" }
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.AudioDelta -> {
                val streamId = state.streamId ?: return
                val pcm16Bytes = Base64.decode(event.delta)
                val mulawBytes = AudioConverter.pcm16_24kToMulaw(pcm16Bytes)
                val mulawBase64 = Base64.encode(mulawBytes)
                val command = AudioStreamCommand.Audio(streamId, mulawBase64)
                val frame = audioStreamAdapter.render(command)
                sendFrame((frame as WebsocketAdapter.Frame.Text).text)
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.SpeechStarted -> {
                logger.debug { "User started speaking" }
                val streamId = state.streamId ?: return
                val command = AudioStreamCommand.Clear(streamId)
                val frame = audioStreamAdapter.render(command)
                sendFrame((frame as WebsocketAdapter.Frame.Text).text)
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.SpeechEnded -> {
                logger.debug { "User stopped speaking" }
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.InputTranscription -> {
                logger.info { "User said: ${event.text}" }
                if (event.isFinal && event.text.isNotBlank()) {
                    onTranscript?.invoke(TranscriptEntry(TranscriptRole.USER, event.text))
                }
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.TextDone -> {
                logger.info { "Agent said: ${event.text}" }
                if (event.text.isNotBlank()) {
                    onTranscript?.invoke(TranscriptEntry(TranscriptRole.AGENT, event.text))
                }
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.ResponseStarted -> {
                logger.debug { "Agent starting response" }
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.ResponseDone -> {
                logger.debug { "Agent finished response" }
                event.usage?.let { usage ->
                    logger.info { "Usage: input=${usage.inputTokens}, output=${usage.outputTokens}" }
                }
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.ToolCallStarted -> {
                logger.info { "Tool call started: ${event.toolName}" }
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.ToolCallDone -> {
                logger.info { "Tool call: ${event.toolName}(${event.arguments})" }
                val result = toolHandler?.invoke(event.toolName, event.arguments)
                    ?: """{"error": "No tool handler configured"}"""
                logger.info { "Tool result: $result" }
                session.sendToolResult(event.callId, result)
            }
            is com.lightningkite.services.voiceagent.VoiceAgentEvent.Error -> {
                logger.error { "Voice agent error: ${event.code} - ${event.message}" }
            }
            else -> {
                // Internal events we don't need to surface
            }
        }
    }
}
