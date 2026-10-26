package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmModelInfo
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.LlmUsage
import com.lightningkite.services.http.client
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [LlmAccess] implementation that talks directly to Anthropic's Messages API
 * (`POST /v1/messages`) over raw ktor HTTP.
 *
 * Streaming mode is always used internally — non-streaming [com.lightningkite.services.ai.inference]
 * is derived from [stream] by the extension in `:ai`.
 *
 * @property apiKey API key sent as `x-api-key`; resolve env-var placeholders before constructing.
 * @property baseUrl API root (default `https://api.anthropic.com`).
 * @property anthropicVersion value for the `anthropic-version` header (default `2023-06-01`).
 * @property defaultMaxTokens output-token cap applied when [LlmPrompt.maxTokens] is null.
 */
public class AnthropicLlmAccess(
    override val name: String,
    override val context: SettingContext,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val anthropicVersion: String = DEFAULT_VERSION,
    private val defaultMaxTokens: Int = DEFAULT_MAX_TOKENS,
) : LlmAccess {

    private val httpClient: HttpClient = client.config {
        // Anthropic needs the api key on every request; attach once here.
        defaultRequest {
            header("x-api-key", apiKey)
            header("anthropic-version", anthropicVersion)
        }
    }

    private val module: SerializersModule get() = context.internalSerializersModule

    override suspend fun getModels(): List<LlmModelInfo> {
        // Use Anthropic's /v1/models endpoint when we can, but augment with our locally
        // curated pricing/ranking. If the call fails, fall back to [KNOWN_MODELS].
        return runCatching { fetchModelsFromApi() }.getOrElse {
            KNOWN_MODELS.map { it.copy(id = it.id.copy(access = name)) }
        }
    }

    private suspend fun fetchModelsFromApi(): List<LlmModelInfo> {
        val response = try {
            httpClient.get("$baseUrl/v1/models")
        } catch (e: LlmException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw LlmException.Transport("Network failure calling Anthropic /v1/models", e)
        }
        if (!response.status.isSuccess()) {
            throw mapAnthropicError(response.status, response.bodyAsText(), response)
        }
        val body = AnthropicWire.jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonArray ?: return KNOWN_MODELS
        return data.map { element ->
            val obj = element.jsonObject
            val id = obj["id"]!!.jsonPrimitive.content
            val displayName = obj["display_name"]?.jsonPrimitive?.content ?: id
            // Augment with our local pricing/ranking when we recognize the id family.
            // Prefer longest-prefix match so dated snapshots like `claude-sonnet-4-5-20250929`
            // still pick up the curated entry for `claude-sonnet-4-5`.
            val baseline = findKnownModel(id) ?: inferPricingFromId(id, displayName)
            baseline.copy(id = LlmModelId(id, access = name), name = displayName)
        }
    }

    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> = flow {
        val body = AnthropicWire.buildRequestBody(
            modelId = model.id,
            requested = prompt,
            module = module,
            stream = true,
            defaultMaxTokens = defaultMaxTokens,
        )
        try {
            httpClient.preparePost("$baseUrl/v1/messages") {
                contentType(ContentType.Application.Json)
                accept(ContentType("text", "event-stream"))
                setBody(body.toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw mapAnthropicError(response.status, response.bodyAsText(), response, model)
                }
                parseSseStream(response) { emit(it) }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: CancellationException) {
            // Downstream cancellation (e.g. Flow.take throwing AbortFlowException) must propagate
            // as cancellation, not be rewrapped as a transport failure.
            throw e
        } catch (e: Throwable) {
            throw LlmException.Transport("Network failure during Anthropic stream", e)
        }
    }

    /**
     * Read Anthropic SSE events line-by-line off the response channel, decode them, and
     * hand each resulting [LlmStreamEvent] to [emit]. Uses the non-blocking
     * [ByteReadChannel.readLine] so the coroutine's dispatcher is never blocked
     * waiting for bytes.
     */
    private suspend fun parseSseStream(
        response: HttpResponse,
        emit: suspend (LlmStreamEvent) -> Unit,
    ) {
        val channel: ByteReadChannel = response.bodyAsChannel()
        val state = SseState()
        while (!channel.isClosedForRead) {
            val line = channel.readLine(LineEnding.Lenient) ?: break
            val done = processSseLine(line, state, emit)
            if (done) break
        }
        // Ensure the stream terminates with Finished even if message_stop was missing.
        if (!state.finished) {
            emit(
                LlmStreamEvent.Finished(
                    stopReason = state.stopReason,
                    usage = state.usage(),
                ),
            )
        }
    }

    override suspend fun healthCheck(): HealthStatus = try {
        val response = httpClient.get("$baseUrl/v1/models")
        if (response.status == HttpStatusCode.Unauthorized) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Anthropic API rejected credentials")
        } else if (!response.status.isSuccess()) {
            HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Unexpected status ${response.status}")
        } else {
            HealthStatus(HealthStatus.Level.OK)
        }
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.anthropic.com"
        public const val DEFAULT_VERSION: String = "2023-06-01"
        public const val DEFAULT_MAX_TOKENS: Int = 4096

        init {
            // Loading AnthropicLlmAccess also loads the URL scheme registration, so code that
            // only references the class (e.g. live tests) doesn't need to call ensureRegistered.
            AnthropicLlmSettings.ensureRegistered()
        }

        /**
         * Locally curated model list used when the `/v1/models` call is skipped or fails.
         *
         * Prices are USD per million tokens, pulled from Anthropic's public pricing page.
         * [LlmModelInfo.roughIntelligenceRanking] is subjective and should be refreshed
         * whenever this list is updated.
         */
        public val KNOWN_MODELS: List<LlmModelInfo> = listOf(
            LlmModelInfo(
                id = LlmModelId("claude-fable-5-1"),
                name = "Claude Fable 5.1",
                description = "Anthropic's most capable widely released model; the hardest reasoning and long-horizon agent work.",
                usdPerMillionInputTokens = 10.0,
                usdPerMillionOutputTokens = 50.0,
                usdPerMillionCacheWriteTokens = 12.5,
                usdPerMillionCacheReadTokens = 0.25,
                roughIntelligenceRanking = 1.0,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-fable-5"),
                name = "Claude Fable 5",
                description = "Previous Fable; same tier and price as Fable 5.1.",
                usdPerMillionInputTokens = 10.0,
                usdPerMillionOutputTokens = 50.0,
                usdPerMillionCacheWriteTokens = 12.5,
                usdPerMillionCacheReadTokens = 1.0,
                roughIntelligenceRanking = 0.98,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-opus-5-5"),
                name = "Claude Opus 5.5",
                description = "Current Opus; strong reasoning, coding and agent work below Fable's price.",
                usdPerMillionInputTokens = 4.0,
                usdPerMillionOutputTokens = 20.0,
                usdPerMillionCacheWriteTokens = 5.0,
                usdPerMillionCacheReadTokens = 0.20,
                roughIntelligenceRanking = 0.97,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-opus-5"),
                name = "Claude Opus 5",
                description = "Previous Opus.",
                usdPerMillionInputTokens = 5.0,
                usdPerMillionOutputTokens = 25.0,
                usdPerMillionCacheWriteTokens = 6.25,
                usdPerMillionCacheReadTokens = 0.50,
                roughIntelligenceRanking = 0.95,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-opus-4-8"),
                name = "Claude Opus 4.8",
                description = "Older Opus.",
                usdPerMillionInputTokens = 5.0,
                usdPerMillionOutputTokens = 25.0,
                usdPerMillionCacheWriteTokens = 6.25,
                usdPerMillionCacheReadTokens = 0.50,
                roughIntelligenceRanking = 0.92,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-opus-4-7"),
                name = "Claude Opus 4.7",
                description = "Older Opus.",
                usdPerMillionInputTokens = 5.0,
                usdPerMillionOutputTokens = 25.0,
                usdPerMillionCacheWriteTokens = 6.25,
                usdPerMillionCacheReadTokens = 0.50,
                roughIntelligenceRanking = 0.91,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-opus-4-6"),
                name = "Claude Opus 4.6",
                description = "Older Opus.",
                usdPerMillionInputTokens = 5.0,
                usdPerMillionOutputTokens = 25.0,
                usdPerMillionCacheWriteTokens = 6.25,
                usdPerMillionCacheReadTokens = 0.50,
                roughIntelligenceRanking = 0.90,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-opus-4-5"),
                name = "Claude Opus 4.5",
                description = "Older Opus; thinks to a token budget.",
                usdPerMillionInputTokens = 5.0,
                usdPerMillionOutputTokens = 25.0,
                usdPerMillionCacheWriteTokens = 6.25,
                usdPerMillionCacheReadTokens = 0.50,
                roughIntelligenceRanking = 0.88,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-sonnet-5-5"),
                name = "Claude Sonnet 5.5",
                description = "Current Sonnet; fast and capable for everyday coding, agent and business work.",
                usdPerMillionInputTokens = 2.0,
                usdPerMillionOutputTokens = 10.0,
                usdPerMillionCacheWriteTokens = 2.5,
                usdPerMillionCacheReadTokens = 0.10,
                roughIntelligenceRanking = 0.93,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-sonnet-5"),
                name = "Claude Sonnet 5",
                description = "Previous Sonnet.",
                usdPerMillionInputTokens = 2.0,
                usdPerMillionOutputTokens = 10.0,
                usdPerMillionCacheWriteTokens = 2.5,
                usdPerMillionCacheReadTokens = 0.20,
                roughIntelligenceRanking = 0.90,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-sonnet-4-6"),
                name = "Claude Sonnet 4.6",
                description = "Older Sonnet.",
                usdPerMillionInputTokens = 3.0,
                usdPerMillionOutputTokens = 15.0,
                usdPerMillionCacheWriteTokens = 3.75,
                usdPerMillionCacheReadTokens = 0.30,
                roughIntelligenceRanking = 0.87,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-sonnet-4-5"),
                name = "Claude Sonnet 4.5",
                description = "Deprecated; retires 2026-11-30.",
                usdPerMillionInputTokens = 3.0,
                usdPerMillionOutputTokens = 15.0,
                usdPerMillionCacheWriteTokens = 3.75,
                usdPerMillionCacheReadTokens = 0.30,
                roughIntelligenceRanking = 0.85,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 200_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-haiku-5-5"),
                name = "Claude Haiku 5.5",
                description = "Current Haiku; fast and very cheap. Prices are for prompts up to 100K tokens; longer prompts cost 5x.",
                usdPerMillionInputTokens = 0.10,
                usdPerMillionOutputTokens = 0.50,
                usdPerMillionCacheWriteTokens = 0.125,
                usdPerMillionCacheReadTokens = 0.01,
                roughIntelligenceRanking = 0.85,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
                maxOutputTokens = 128_000,
            ),
            LlmModelInfo(
                id = LlmModelId("claude-haiku-4-5"),
                name = "Claude Haiku 4.5",
                description = "Previous Haiku; thinks to a token budget.",
                usdPerMillionInputTokens = 1.0,
                usdPerMillionOutputTokens = 5.0,
                usdPerMillionCacheWriteTokens = 1.25,
                usdPerMillionCacheReadTokens = 0.10,
                roughIntelligenceRanking = 0.75,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 200_000,
                maxOutputTokens = 64_000,
            ),
        )

        /**
         * Pick the [KNOWN_MODELS] entry whose id is the longest prefix of [id]. Returns null
         * when no curated entry matches. Preserves curated pricing/ranking for dated
         * snapshots (e.g. `claude-sonnet-4-5-20250929` resolves to `claude-sonnet-4-5`).
         */
        internal fun findKnownModel(id: String): LlmModelInfo? =
            KNOWN_MODELS
                .filter { id.startsWith(it.id.id) }
                .maxByOrNull { it.id.id.length }

        /** Fallback for unknown ids returned by /v1/models — pick defaults by family name. */
        internal fun inferPricingFromId(id: String, displayName: String): LlmModelInfo {
            val (inPrice, outPrice, ranking) = when {
                "fable" in id || "mythos" in id -> Triple(10.0, 50.0, 1.0)
                "opus" in id -> Triple(4.0, 20.0, 0.97)
                "sonnet" in id -> Triple(2.0, 10.0, 0.93)
                "haiku" in id -> Triple(0.10, 0.50, 0.85)
                else -> Triple(2.0, 10.0, 0.50)
            }
            return LlmModelInfo(
                id = LlmModelId(id),
                name = displayName,
                description = null,
                usdPerMillionInputTokens = inPrice,
                usdPerMillionOutputTokens = outPrice,
                roughIntelligenceRanking = ranking,
                supportsToolCalling = true,
                supportsImageInput = true,
                supportsReasoning = true,
                maxContextTokens = 1_000_000,
            )
        }
    }

    /**
     * Running state maintained across the lifetime of one SSE stream.
     *
     * Tool-call argument JSON arrives as a sequence of `input_json_delta` fragments that
     * must be concatenated and emitted once the corresponding content block ends.
     */
    internal class SseState {
        var inputTokens: Int = 0
        var outputTokens: Int = 0
        var cacheReadTokens: Int = 0
        var cacheWriteTokens: Int = 0
        var stopReason: LlmStopReason = LlmStopReason.EndTurn
        var finished: Boolean = false

        // Anthropic's input_tokens counts only what wasn't read from or written to the cache; LlmUsage.inputTokens is all of it.
        fun usage(): LlmUsage = LlmUsage(inputTokens + cacheReadTokens + cacheWriteTokens, outputTokens, cacheReadTokens, cacheWriteTokens)

        /** Per-index tool-call accumulator; index refers to Anthropic's content array position. */
        val toolsInFlight: HashMap<Int, ToolCallInProgress> = HashMap()
    }

    internal data class ToolCallInProgress(
        val id: String,
        val name: String,
        var argsJson: StringBuilder = StringBuilder(),
    )
}

/**
 * Handle a single SSE line. Returns true when the stream is terminated and the reader
 * should stop consuming further lines. Extracted to top level to keep the streaming
 * logic test-friendly.
 */
internal suspend fun processSseLine(
    line: String,
    state: AnthropicLlmAccess.SseState,
    emit: suspend (LlmStreamEvent) -> Unit,
): Boolean {
    val trimmed = line.trimEnd()
    // Blank lines delimit SSE events; ignore them — we key off the `data:` prefix directly.
    if (trimmed.isEmpty()) return false
    // `event:` lines are informational; the payload's own `type` field is authoritative.
    if (trimmed.startsWith("event:")) return false
    if (!trimmed.startsWith("data:")) return false

    val jsonText = trimmed.substring("data:".length).trimStart()
    if (jsonText.isEmpty() || jsonText == "[DONE]") return false

    val event = runCatching { AnthropicWire.jsonCodec.parseToJsonElement(jsonText) }
        .getOrNull() as? JsonObject ?: return false

    return handleSseEvent(event, state, emit)
}

private suspend fun handleSseEvent(
    event: JsonObject,
    state: AnthropicLlmAccess.SseState,
    emit: suspend (LlmStreamEvent) -> Unit,
): Boolean {
    return when (event["type"]?.jsonPrimitive?.content) {
        "message_start" -> {
            // Carries the model id and an initial (possibly zero) usage object. The
            // Anthropic docs warn that `message_start.usage.input_tokens` is authoritative
            // for input tokens; `output_tokens` always comes from `message_delta`.
            // `cache_read_input_tokens` reports prompt-cache hits and is absent on requests
            // that didn't touch the cache — fall back to the existing value so it defaults
            // to 0 without clobbering a prior event.
            val usage = event["message"]?.jsonObject?.get("usage")?.jsonObject
            state.inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull
                ?: state.inputTokens
            state.cacheReadTokens = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull
                ?: state.cacheReadTokens
            state.cacheWriteTokens = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull
                ?: state.cacheWriteTokens
            false
        }

        "content_block_start" -> {
            val index = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return false
            val block = event["content_block"]?.jsonObject ?: return false
            when (block["type"]?.jsonPrimitive?.content) {
                "tool_use" -> state.toolsInFlight[index] = AnthropicLlmAccess.ToolCallInProgress(
                    id = block["id"]!!.jsonPrimitive.content,
                    name = block["name"]!!.jsonPrimitive.content,
                )
                // A redacted block arrives whole here; its stop event carries nothing more.
                "redacted_thinking" -> emit(
                    LlmStreamEvent.ReasoningDelta("", redactedData = block["data"]!!.jsonPrimitive.content)
                )
            }
            false
        }

        "content_block_delta" -> {
            val delta = event["delta"]?.jsonObject ?: return false
            when (delta["type"]?.jsonPrimitive?.content) {
                "text_delta" -> {
                    val text = delta["text"]?.jsonPrimitive?.content ?: ""
                    if (text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(text))
                }
                "thinking_delta" -> {
                    val text = delta["thinking"]?.jsonPrimitive?.content ?: ""
                    if (text.isNotEmpty()) emit(LlmStreamEvent.ReasoningDelta(text))
                }
                // Sent once per thinking block, just before its content_block_stop.
                "signature_delta" -> emit(
                    LlmStreamEvent.ReasoningDelta("", signature = delta["signature"]!!.jsonPrimitive.content)
                )
                "input_json_delta" -> {
                    val index = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return false
                    val fragment = delta["partial_json"]?.jsonPrimitive?.content ?: return false
                    state.toolsInFlight[index]?.argsJson?.append(fragment)
                }
                else -> {}
            }
            false
        }

        "content_block_stop" -> {
            val index = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return false
            state.toolsInFlight.remove(index)?.let { call ->
                // Empty inputJson is valid (zero-arg tools); send "{}" not a blank string.
                val args = call.argsJson.toString().ifEmpty { "{}" }
                emit(LlmStreamEvent.ToolCallEmitted(id = call.id, name = call.name, inputJson = args))
            }
            false
        }

        "message_delta" -> {
            val delta = event["delta"]?.jsonObject
            delta?.get("stop_reason")?.jsonPrimitive?.content?.let {
                state.stopReason = AnthropicWire.parseStopReason(it)
            }
            event["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull
                ?.let { state.outputTokens = it }
            false
        }

        "message_stop" -> {
            state.finished = true
            emit(
                LlmStreamEvent.Finished(
                    stopReason = state.stopReason,
                    usage = state.usage(),
                ),
            )
            true
        }

        // "ping" and "error" events: ping is a keep-alive; error carries a provider-side
        // problem and we surface it by throwing so the flow collector sees it.
        "error" -> {
            val message = event["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: "Anthropic stream reported an error"
            throw LlmException.ServerError(message)
        }

        else -> false
    }
}

/**
 * Translate an Anthropic error response into the matching [LlmException] subclass.
 *
 * Anthropic returns a JSON envelope of the form
 * `{"type":"error","error":{"type":"...","message":"..."}}` on non-2xx responses.
 * We branch primarily on `error.type` when present, falling back to HTTP status-code
 * buckets when the envelope is missing or the type is unrecognized.
 *
 * @param modelId When known, threaded through to [LlmException.InvalidModel] for errors
 *   that reference the model (e.g. a 404 with a model-specific message).
 * @param response When supplied, used to read the `retry-after` header for rate-limit hints.
 *   Production call sites pass the live [HttpResponse]; tests can use [retryAfter] to bypass
 *   the ktor response construction.
 * @param retryAfter Explicit retry-after override; wins over [response]. Exists primarily for
 *   tests that don't want to spin up a MockEngine just to set one header.
 */
internal fun mapAnthropicError(
    status: HttpStatusCode,
    body: String,
    response: HttpResponse? = null,
    modelId: LlmModelId? = null,
    retryAfter: Duration? = null,
): LlmException {
    val envelope = runCatching { AnthropicWire.jsonCodec.parseToJsonElement(body) }
        .getOrNull() as? JsonObject
    val error = envelope?.get("error")?.jsonObject
    val errorType = error?.get("type")?.jsonPrimitive?.content
    val message = error?.get("message")?.jsonPrimitive?.content
        ?: body.take(300).ifEmpty { "Anthropic returned ${status.value} with no body" }
    val effectiveRetryAfter = retryAfter ?: response?.let(::parseRetryAfter)

    return when (errorType) {
        "authentication_error", "permission_error" -> LlmException.Auth(message)
        "not_found_error" -> {
            // Anthropic returns 404 for both unknown URLs and unknown models; the body
            // message is how the caller distinguishes. When it mentions a model, surface
            // the typed InvalidModel case so callers can handle model-selection errors
            // without parsing strings themselves.
            if (modelId != null && message.contains("model", ignoreCase = true)) {
                LlmException.InvalidModel(modelId, message)
            } else {
                LlmException.InvalidRequest(message)
            }
        }
        "invalid_request_error" -> LlmException.InvalidRequest(message)
        "rate_limit_error" -> LlmException.RateLimit(
            message = message,
            retryAfter = effectiveRetryAfter,
        )
        "api_error", "overloaded_error" -> LlmException.ServerError(message)
        else -> when {
            status.value == 401 || status.value == 403 -> LlmException.Auth(message)
            status.value == 429 -> LlmException.RateLimit(
                message = message,
                retryAfter = effectiveRetryAfter,
            )
            status.value in 400..499 -> LlmException.InvalidRequest(message)
            status.value in 500..599 -> LlmException.ServerError(message)
            else -> LlmException.InvalidRequest(message)
        }
    }
}

/**
 * Parse the `retry-after` header into a [Duration]. Anthropic sends numeric seconds;
 * the header spec also allows HTTP-date form, which we ignore (return null) because
 * callers universally treat the Duration as a simple wait interval.
 */
internal fun parseRetryAfter(response: HttpResponse): Duration? {
    val raw = response.headers["retry-after"] ?: response.headers["Retry-After"] ?: return null
    val secs = raw.trim().toLongOrNull() ?: return null
    return secs.seconds
}
