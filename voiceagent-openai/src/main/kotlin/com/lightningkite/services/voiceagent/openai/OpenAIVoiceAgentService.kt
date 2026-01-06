package com.lightningkite.services.voiceagent.openai

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.voiceagent.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.crac.Context
import org.crac.Core
import org.crac.Resource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * OpenAI Realtime API implementation of [VoiceAgentService].
 *
 * Connects to OpenAI's WebSocket-based Realtime API for real-time
 * voice conversations with GPT models.
 *
 * ## URL Format
 *
 * ```
 * openai-realtime://model?apiKey=sk-...
 * ```
 *
 * Parameters:
 * - `model`: The model ID (e.g., "gpt-4o-realtime-preview")
 * - `apiKey`: OpenAI API key (optional, defaults to OPENAI_API_KEY env var)
 *
 * ## Example
 *
 * ```kotlin
 * val settings = VoiceAgentService.Settings("openai-realtime://gpt-4o-realtime-preview")
 * val service = settings("voice-agent", context)
 *
 * val session = service.createSession(VoiceAgentSessionConfig(
 *     instructions = "You are a helpful assistant.",
 *     voice = VoiceConfig(name = "alloy"),
 * ))
 * ```
 */
public class OpenAIVoiceAgentService(
    override val name: String,
    override val context: SettingContext,
    private val model: String,
    private val apiKey: String,
) : VoiceAgentService, Resource {

    @Volatile
    private var httpClient: HttpClient? = null
    private val clientLock = Any()

    init {
        Core.getGlobalContext().register(this)
    }

    override fun beforeCheckpoint(context: Context<out Resource>) {
        // Close HTTP client before checkpoint
        synchronized(clientLock) {
            httpClient?.close()
            httpClient = null
        }
    }

    override fun afterRestore(context: Context<out Resource>) {
        // Client will be recreated on next access
    }

    private fun getHttpClient(): HttpClient {
        return httpClient ?: synchronized(clientLock) {
            httpClient ?: HttpClient(CIO) {
                install(WebSockets) {
                    pingIntervalMillis = 30_000
                }
            }.also { httpClient = it }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true  // Required so tool.type = "function" is always serialized
        explicitNulls = false  // Don't serialize null values - OpenAI rejects them
        classDiscriminator = "type"
    }

    override suspend fun createSession(config: VoiceAgentSessionConfig): VoiceAgentSession {
        logger.info { "[$name] Creating OpenAI Realtime session with model: $model" }
        return OpenAIVoiceAgentSession(
            serviceName = name,
            model = model,
            apiKey = apiKey,
            initialConfig = config,
            httpClient = getHttpClient(),
            json = json,
        )
    }

    override suspend fun disconnect() {
        synchronized(clientLock) {
            httpClient?.close()
            httpClient = null
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        // Could potentially make a test connection, but for now just return OK
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "OpenAI Realtime service ready")
    }

    public companion object {
        init {
            VoiceAgentService.Settings.register("openai-realtime") { name, url, context ->
                val params = parseUrlParams(url)
                val model = url.substringAfter("://").substringBefore("?").ifEmpty { "gpt-4o-realtime-preview" }
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("OPENAI_API_KEY")
                    ?: throw IllegalArgumentException("OpenAI API key not provided in URL or OPENAI_API_KEY environment variable")

                OpenAIVoiceAgentService(name, context, model, apiKey)
            }
        }
    }
}

/**
 * OpenAI Realtime API session implementation.
 */
internal class OpenAIVoiceAgentSession(
    private val serviceName: String,
    private val model: String,
    private val apiKey: String,
    private val initialConfig: VoiceAgentSessionConfig,
    private val httpClient: HttpClient,
    private val json: Json,
) : VoiceAgentSession {

    override val sessionId: String = Uuid.random().toString()
    override var config: VoiceAgentSessionConfig = initialConfig
        private set

    private val eventChannel = Channel<VoiceAgentEvent>(Channel.UNLIMITED)
    override val events: Flow<VoiceAgentEvent> = eventChannel.receiveAsFlow()

    private var webSocketSession: DefaultWebSocketSession? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isConnected = false
    private var openaiSessionId: String? = null

    // Track current function call being built (per-session, cleaned up after each call completes)
    private val functionCallArguments = mutableMapOf<String, StringBuilder>()
    private val functionCallNames = mutableMapOf<String, String>()

    init {
        // Start connection in background
        connectionJob = scope.launch {
            connect()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun connect() {
        try {
            val wsUrl = "wss://api.openai.com/v1/realtime?model=$model"
            logger.info { "[$serviceName] Connecting to OpenAI Realtime: $wsUrl" }

            httpClient.webSocket(
                urlString = wsUrl,
                request = {
                    headers.append("Authorization", "Bearer $apiKey")
                    headers.append("OpenAI-Beta", "realtime=v1")
                }
            ) {
                webSocketSession = this
                isConnected = true
                logger.info { "[$serviceName] WebSocket connected" }

                // Process incoming messages
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                handleServerMessage(text)
                            }
                            is Frame.Close -> {
                                logger.info { "[$serviceName] WebSocket closed: ${frame.readReason()}" }
                                break
                            }
                            else -> { /* ignore */ }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "[$serviceName] Error receiving WebSocket messages" }
                    eventChannel.send(VoiceAgentEvent.Error("websocket_error", e.message ?: "WebSocket error"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[$serviceName] Failed to connect to OpenAI Realtime" }
            eventChannel.send(VoiceAgentEvent.Error("connection_error", e.message ?: "Connection failed"))
        } finally {
            isConnected = false
            eventChannel.close()
        }
    }

    private suspend fun handleServerMessage(text: String) {
        try {
            logger.trace { "[$serviceName] Raw message: ${text.take(500)}..." }
            val event = json.decodeFromString<ServerEvent>(text)
            logger.debug { "[$serviceName] Received: ${event::class.simpleName}" }

            when (event) {
                is ServerEvent.Error -> {
                    logger.error { "[$serviceName] OpenAI error: code=${event.error.code ?: event.error.type}, message=${event.error.message}, param=${event.error.param}" }
                    eventChannel.send(VoiceAgentEvent.Error(
                        code = event.error.code ?: event.error.type,
                        message = event.error.message,
                        details = event.error.param,
                    ))
                }

                is ServerEvent.SessionCreated -> {
                    openaiSessionId = event.session.id
                    eventChannel.send(VoiceAgentEvent.SessionCreated(event.session.id))
                    // Send initial configuration
                    sendSessionUpdate(config)
                }

                is ServerEvent.SessionUpdated -> {
                    logger.debug { "[$serviceName] Session updated - tools: ${event.session.tools?.map { it.name }}" }
                    eventChannel.send(VoiceAgentEvent.SessionUpdated(config))
                }

                is ServerEvent.InputAudioBufferCommitted -> {
                    eventChannel.send(VoiceAgentEvent.InputAudioCommitted(event.itemId))
                }

                is ServerEvent.InputAudioBufferCleared -> {
                    eventChannel.send(VoiceAgentEvent.InputAudioCleared)
                }

                is ServerEvent.InputAudioBufferSpeechStarted -> {
                    eventChannel.send(VoiceAgentEvent.SpeechStarted(event.audioStartMs))
                }

                is ServerEvent.InputAudioBufferSpeechStopped -> {
                    eventChannel.send(VoiceAgentEvent.SpeechEnded(event.audioEndMs))
                }

                is ServerEvent.InputAudioTranscriptionCompleted -> {
                    eventChannel.send(VoiceAgentEvent.InputTranscription(
                        itemId = event.itemId,
                        text = event.transcript,
                        isFinal = true,
                    ))
                }

                is ServerEvent.InputAudioTranscriptionFailed -> {
                    eventChannel.send(VoiceAgentEvent.InputTranscriptionFailed(
                        itemId = event.itemId,
                        error = event.error.message,
                    ))
                }

                is ServerEvent.ResponseCreated -> {
                    logger.info { "[$serviceName] Response started: ${event.response.id}" }
                    eventChannel.send(VoiceAgentEvent.ResponseStarted(event.response.id))
                }

                is ServerEvent.ResponseDone -> {
                    val status = when (event.response.status) {
                        "completed" -> ResponseStatus.COMPLETED
                        "cancelled" -> ResponseStatus.CANCELLED
                        "failed" -> ResponseStatus.FAILED
                        "incomplete" -> ResponseStatus.INCOMPLETE
                        else -> ResponseStatus.IN_PROGRESS
                    }
                    val usage = event.response.usage?.let {
                        UsageStats(
                            inputTokens = it.inputTokens ?: 0,
                            outputTokens = it.outputTokens ?: 0,
                            totalTokens = it.totalTokens ?: 0,
                            inputAudioTokens = it.inputTokenDetails?.audioTokens,
                            outputAudioTokens = it.outputTokenDetails?.audioTokens,
                            inputTextTokens = it.inputTokenDetails?.textTokens,
                            outputTextTokens = it.outputTokenDetails?.textTokens,
                        )
                    }

                    if (status == ResponseStatus.FAILED) {
                        logger.error { "[$serviceName] Response FAILED: ${event.response.id}, statusDetails=${event.response.statusDetails}" }
                    } else {
                        logger.info { "[$serviceName] Response done: ${event.response.id}, status=$status" }
                        logger.debug { "[$serviceName] Usage: input=${usage?.inputTokens} (text=${usage?.inputTextTokens}, audio=${usage?.inputAudioTokens}), output=${usage?.outputTokens} (text=${usage?.outputTextTokens}, audio=${usage?.outputAudioTokens})" }
                    }
                    eventChannel.send(VoiceAgentEvent.ResponseDone(event.response.id, status, usage))
                }

                is ServerEvent.ResponseAudioDelta -> {
                    eventChannel.send(VoiceAgentEvent.AudioDelta(
                        responseId = event.responseId,
                        itemId = event.itemId,
                        contentIndex = event.contentIndex,
                        delta = event.delta,
                    ))
                }

                is ServerEvent.ResponseAudioDone -> {
                    eventChannel.send(VoiceAgentEvent.AudioDone(
                        responseId = event.responseId,
                        itemId = event.itemId,
                        contentIndex = event.contentIndex,
                    ))
                }

                is ServerEvent.ResponseAudioTranscriptDelta -> {
                    eventChannel.send(VoiceAgentEvent.TextDelta(
                        responseId = event.responseId,
                        itemId = event.itemId,
                        contentIndex = event.contentIndex,
                        delta = event.delta,
                    ))
                }

                is ServerEvent.ResponseAudioTranscriptDone -> {
                    eventChannel.send(VoiceAgentEvent.TextDone(
                        responseId = event.responseId,
                        itemId = event.itemId,
                        contentIndex = event.contentIndex,
                        text = event.transcript,
                    ))
                }

                is ServerEvent.ResponseTextDelta -> {
                    eventChannel.send(VoiceAgentEvent.TextDelta(
                        responseId = event.responseId,
                        itemId = event.itemId,
                        contentIndex = event.contentIndex,
                        delta = event.delta,
                    ))
                }

                is ServerEvent.ResponseTextDone -> {
                    eventChannel.send(VoiceAgentEvent.TextDone(
                        responseId = event.responseId,
                        itemId = event.itemId,
                        contentIndex = event.contentIndex,
                        text = event.text,
                    ))
                }

                is ServerEvent.ResponseOutputItemAdded -> {
                    // Check if this is a function call
                    if (event.item.type == "function_call") {
                        val callId = event.item.callId ?: return
                        val name = event.item.name ?: return
                        logger.info { "[$serviceName] Tool call started: $name (callId=$callId)" }
                        functionCallNames[callId] = name
                        functionCallArguments[callId] = StringBuilder()
                        eventChannel.send(VoiceAgentEvent.ToolCallStarted(
                            responseId = event.responseId,
                            itemId = event.item.id ?: "",
                            callId = callId,
                            toolName = name,
                        ))
                    }
                }

                is ServerEvent.ResponseFunctionCallArgumentsDelta -> {
                    functionCallArguments[event.callId]?.append(event.delta)
                    eventChannel.send(VoiceAgentEvent.ToolCallArgumentsDelta(
                        callId = event.callId,
                        delta = event.delta,
                    ))
                }

                is ServerEvent.ResponseFunctionCallArgumentsDone -> {
                    val name = functionCallNames[event.callId] ?: event.name
                    logger.info { "[$serviceName] Tool call ready: $name (callId=${event.callId}), args=${event.arguments}" }
                    eventChannel.send(VoiceAgentEvent.ToolCallDone(
                        callId = event.callId,
                        toolName = name,
                        arguments = event.arguments,
                    ))
                    // Clean up tracking
                    functionCallArguments.remove(event.callId)
                    functionCallNames.remove(event.callId)
                }

                is ServerEvent.RateLimitsUpdated -> {
                    eventChannel.send(VoiceAgentEvent.RateLimitsUpdated(
                        limits = event.rateLimits.map {
                            RateLimitInfo(
                                name = it.name,
                                limit = it.limit,
                                remaining = it.remaining,
                                resetSeconds = it.resetSeconds.toInt(),
                            )
                        }
                    ))
                }

                // Events we don't need to forward
                is ServerEvent.ConversationItemCreated,
                is ServerEvent.ResponseOutputItemDone,
                is ServerEvent.ResponseContentPartAdded,
                is ServerEvent.ResponseContentPartDone -> {
                    // These are informational; we track state via other events
                }
            }
        } catch (e: Exception) {
            // Try to extract the event type from the raw message for better logging
            val eventType = try {
                json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }

            // Check if this is an unregistered event type (intentional, not an error)
            if (e is SerializationException && eventType != null) {
                // Unregistered event type - log at DEBUG without stack trace (not parsing every event type is intentional)
                logger.debug { "[$serviceName] Ignoring unregistered event type: $eventType" }
            } else {
                // Actual parsing error - log at ERROR with stack trace
                logger.error(e) { "[$serviceName] Error parsing server message: ${text.take(500)}" }
            }
        }
    }

    private suspend fun sendEvent(event: ClientEvent) {
        val ws = webSocketSession
        if (ws == null || !isConnected) {
            logger.warn { "[$serviceName] Cannot send event: not connected (ws=$ws, isConnected=$isConnected)" }
            return
        }

        try {
            val text = json.encodeToString(event)
            ws.send(Frame.Text(text))
        } catch (e: Exception) {
            logger.error(e) { "[$serviceName] Error sending event" }
        }
    }

    private suspend fun sendSessionUpdate(config: VoiceAgentSessionConfig) {
        val tools = config.tools.map { it.toOpenAIToolDefinition() }.takeIf { it.isNotEmpty() }

        // Log session config sizes for token analysis
        val instructionsChars = config.instructions?.length ?: 0
        val instructionsEstTokens = instructionsChars / 4  // rough estimate
        val toolsJson = tools?.let { json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ToolDefinition.serializer()), it) }
        val toolsChars = toolsJson?.length ?: 0
        val toolsEstTokens = toolsChars / 4

        logger.info { "[$serviceName] SESSION UPDATE - Instructions: ${instructionsChars} chars (~${instructionsEstTokens} tokens), Tools: ${toolsChars} chars (~${toolsEstTokens} tokens), Total: ~${instructionsEstTokens + toolsEstTokens} tokens" }
        if (tools != null) {
            logger.debug { "[$serviceName] Tool names: ${tools.map { it.name }}" }
            tools.forEach { tool ->
                val toolJson = json.encodeToString(ToolDefinition.serializer(), tool)
                logger.debug { "[$serviceName] Tool '${tool.name}': ${toolJson.length} chars" }
            }
        }

        val sessionConfig = SessionConfig(
            modalities = listOf("text", "audio"),
            instructions = config.instructions,
            voice = config.voice?.name,
            inputAudioFormat = config.inputAudioFormat.toOpenAIFormat(),
            outputAudioFormat = config.outputAudioFormat.toOpenAIFormat(),
            inputAudioTranscription = config.inputTranscription?.let {
                TranscriptionConfigOpenAI(
                    model = it.model ?: "whisper-1",
                    language = it.language,
                    prompt = it.prompt,
                )
            },
            turnDetection = config.turnDetection.toOpenAIConfig(),
            tools = tools,
            toolChoice = if (config.tools.isNotEmpty()) "auto" else null,
            temperature = config.temperature,
            maxResponseOutputTokens = config.maxResponseTokens?.let { kotlinx.serialization.json.JsonPrimitive(it) },
        )

        sendEvent(ClientEvent.SessionUpdate(session = sessionConfig))
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun sendAudio(audio: ByteArray) {
        // Wait for connection if needed
        var waited = 0
        while (!isConnected && waited < 5000) {
            delay(50)
            waited += 50
        }

        if (!isConnected) {
            logger.warn { "[$serviceName] Cannot send audio: not connected after 5s" }
            return
        }

        val base64Audio = Base64.encode(audio)
        sendEvent(ClientEvent.InputAudioBufferAppend(audio = base64Audio))
    }

    override suspend fun commitAudio() {
        sendEvent(ClientEvent.InputAudioBufferCommit())
    }

    override suspend fun clearInputBuffer() {
        sendEvent(ClientEvent.InputAudioBufferClear())
    }

    override suspend fun cancelResponse() {
        sendEvent(ClientEvent.ResponseCancel())
    }

    override suspend fun createResponse() {
        sendEvent(ClientEvent.ResponseCreate())
    }

    override suspend fun updateSession(config: VoiceAgentSessionConfig) {
        this.config = config
        sendSessionUpdate(config)
    }

    override suspend fun sendToolResult(callId: String, result: String) {
        logger.info { "[$serviceName] Sending tool result for callId=$callId: ${result.take(200)}${if (result.length > 200) "..." else ""}" }
        val item = ConversationItem(
            type = "function_call_output",
            callId = callId,
            output = result,
        )
        sendEvent(ClientEvent.ConversationItemCreate(item = item))
        // Trigger response after tool result
        sendEvent(ClientEvent.ResponseCreate())
    }

    override suspend fun addMessage(role: VoiceAgentSession.MessageRole, text: String) {
        val item = ConversationItem(
            type = "message",
            role = when(role) {
                VoiceAgentSession.MessageRole.User -> "user"
                VoiceAgentSession.MessageRole.Assistant -> "assistant"
            },
            content = listOf(ContentPart(type = "input_text", text = text)),
        )
        sendEvent(ClientEvent.ConversationItemCreate(item = item))
    }

    override suspend fun close() {
        logger.info { "[$serviceName] Closing session $sessionId" }
        connectionJob?.cancel()
        webSocketSession?.close()
        scope.cancel()
    }

    override suspend fun awaitConnection() {
        // Wait for WebSocket connection to be established
        var waited = 0
        while (!isConnected && waited < 10000) {
            delay(50)
            waited += 50
        }

        if (!isConnected) {
            throw IllegalStateException("Failed to connect to OpenAI Realtime after 10 seconds")
        }

        logger.info { "[$serviceName] Connection ready after ${waited}ms" }
    }
}

// Helper functions

private fun AudioFormat.toOpenAIFormat(): String = when (this) {
    AudioFormat.PCM16_24K -> "pcm16"
    AudioFormat.PCM16_16K -> "pcm16" // OpenAI expects 24kHz, may need resampling
    AudioFormat.PCM16_8K -> "pcm16"
    AudioFormat.G711_ULAW -> "g711_ulaw"
    AudioFormat.G711_ALAW -> "g711_alaw"
}

private fun TurnDetection.toOpenAIConfig(): TurnDetectionConfigOpenAI? = when (this) {
    is TurnDetection.None -> null
    is TurnDetection.ServerVAD -> TurnDetectionConfigOpenAI(
        type = "server_vad",
        threshold = threshold,
        prefixPaddingMs = prefixPaddingMs,
        silenceDurationMs = silenceDurationMs,
        createResponse = createResponse,
        interruptResponse = interruptResponse,
    )
    is TurnDetection.SemanticVAD -> TurnDetectionConfigOpenAI(
        type = "semantic_vad",
        eagerness = when (eagerness) {
            TurnDetection.Eagerness.LOW -> "low"
            TurnDetection.Eagerness.AUTO -> "auto"
            TurnDetection.Eagerness.HIGH -> "high"
        },
        createResponse = createResponse,
        interruptResponse = interruptResponse,
    )
}

private fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()

    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
}

private fun resolveEnvVars(value: String): String {
    val envVarPattern = """\$\{([^}]+)\}""".toRegex()
    return envVarPattern.replace(value) { matchResult ->
        val envVar = matchResult.groupValues[1]
        System.getenv(envVar) ?: matchResult.value
    }
}
