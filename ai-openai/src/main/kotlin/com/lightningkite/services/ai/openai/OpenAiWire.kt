@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.lightningkite.services.ai.openai

import com.lightningkite.MediaType
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmUsage
import com.lightningkite.services.ai.toJsonSchema
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JSON codec shared across wire mapping helpers.
 *
 * `encodeDefaults = true` so our request DTOs emit their sensible defaults (e.g. `stream: true`),
 * `ignoreUnknownKeys = true` so provider-specific extra fields on responses don't break decoding.
 */
internal val openAiJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val wireLogger = KotlinLogging.logger("OpenAiWire")

// --- Response / streaming DTOs ---------------------------------------------------

/** Top-level chunk of a `chat.completions` SSE stream. */
@Serializable
internal data class ChatCompletionChunk(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChunkUsage? = null,
)

@Serializable
internal data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChunkToolCall> = emptyList(),
    /**
     * Model chain-of-thought, used by reasoning models surfaced through OpenAI-compatible
     * endpoints (DeepSeek R1, Gemma reasoning variants via LM Studio, etc.). Standard OpenAI
     * chat completions omit this; we decode it opportunistically and forward as
     * [LlmStreamEvent.ReasoningDelta].
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
internal data class ChunkToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: ChunkFunction? = null,
)

@Serializable
internal data class ChunkFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class ChunkUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
)

/**
 * Breakdown of input-token categories returned on the final streaming chunk when the request
 * sets `stream_options.include_usage: true`. Older OpenAI-compatible servers may omit it; all
 * fields default to null/0 so absence is handled gracefully.
 */
@Serializable
internal data class PromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
    @SerialName("audio_tokens") val audioTokens: Int = 0,
)

/** Response from `GET /v1/models`. */
@Serializable
internal data class ModelListResponse(
    val data: List<ModelListEntry> = emptyList(),
)

@Serializable
internal data class ModelListEntry(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)

// --- Request building ------------------------------------------------------------

/**
 * Build the full request JSON body for `POST /v1/chat/completions`.
 *
 * @param stream Adds `stream: true` and `stream_options.include_usage: true` when set.
 */
internal fun buildRequestBody(
    model: String,
    prompt: LlmPrompt,
    stream: Boolean,
    module: SerializersModule,
): JsonObject = buildJsonObject {
    put("model", model)
    putJsonArray("messages") {
        prompt.collectSharedContext()?.let { ctx ->
            add(buildJsonObject {
                put("role", "system")
                put("content", ctx)
            })
        }
        prompt.messages.forEach { message ->
            buildOpenAiMessages(message).forEach { add(it) }
        }
    }
    if (prompt.tools.isNotEmpty()) {
        putJsonArray("tools") {
            prompt.tools.forEach { tool ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.toJsonSchema(module))
                    }
                }
            }
        }
        // Tool choice is only meaningful when tools are present; OpenAI rejects the field otherwise.
        put("tool_choice", encodeToolChoice(prompt.toolChoice))
    }
    prompt.maxTokens?.let {
        // Emit both the modern and the legacy key so OpenAI-compatible servers that only
        // recognise one of them (older LM Studio, vLLM, Groq, Together, llama.cpp server,
        // Ollama's OpenAI shim) still see a token cap. OpenAI's current API silently
        // ignores `max_tokens` when `max_completion_tokens` is present.
        put("max_completion_tokens", it)
        put("max_tokens", it)
    }
    prompt.temperature?.let { put("temperature", it) }
    if (prompt.stopSequences.isNotEmpty()) {
        putJsonArray("stop") { prompt.stopSequences.forEach { add(it) } }
    }
    if (stream) {
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
    }
}

private fun encodeToolChoice(choice: LlmToolChoice): JsonElement = when (choice) {
    is LlmToolChoice.Auto -> JsonPrimitive("auto")
    is LlmToolChoice.None -> JsonPrimitive("none")
    is LlmToolChoice.Required -> JsonPrimitive("required")
    is LlmToolChoice.Specific -> buildJsonObject {
        put("type", "function")
        putJsonObject("function") { put("name", choice.name) }
    }
}

/**
 * Map an [LlmMessage] to one or more OpenAI messages.
 *
 * - System/User/Agent → exactly one message.
 * - Tool → one message per [LlmContent.ToolResult] (OpenAI has no multi-result tool message).
 */
internal fun buildOpenAiMessages(message: LlmMessage): List<JsonObject> {
    return when (message.source) {
        LlmMessageSource.System -> listOf(buildSystemOrUser("system", message.content))
        LlmMessageSource.User -> listOf(buildSystemOrUser("user", message.content))
        LlmMessageSource.Agent -> listOf(buildAssistantMessage(message.content))
        LlmMessageSource.Tool -> message.content.mapNotNull { part ->
            (part as? LlmContent.ToolResult)?.let { tr ->
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", tr.toolCallId)
                    // OpenAI doesn't have a dedicated is_error; prefix on error to signal it to the model.
                    put("content", if (tr.isError) "ERROR: ${tr.content}" else tr.content)
                }
            }
        }
    }
}

/**
 * Build a system/user message. Uses a plain string for text-only content and a content-array
 * (with `{type:"text",...}` / `{type:"image_url",...}` parts) when attachments are present.
 */
private fun buildSystemOrUser(role: String, content: List<LlmContent>): JsonObject {
    val hasAttachment = content.any { it is LlmContent.Attachment }
    return buildJsonObject {
        put("role", role)
        if (hasAttachment) {
            putJsonArray("content") {
                content.forEach { part ->
                    when (part) {
                        is LlmContent.Text -> addJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        }
                        is LlmContent.Attachment -> add(encodeAttachment(part.attachment))
                        is LlmContent.ToolCall,
                        is LlmContent.ToolResult,
                        // Reasoning is receive-only in v1. Providers MUST NOT fail when sent
                        // a Reasoning block, so we silently drop it from outgoing requests.
                        is LlmContent.Reasoning -> Unit
                    }
                }
            }
        } else {
            val text = content.filterIsInstance<LlmContent.Text>().joinToString("") { it.text }
            put("content", text)
        }
    }
}

private fun buildAssistantMessage(content: List<LlmContent>): JsonObject = buildJsonObject {
    put("role", "assistant")
    val text = content.filterIsInstance<LlmContent.Text>().joinToString("") { it.text }
    val toolCalls = content.filterIsInstance<LlmContent.ToolCall>()
    // OpenAI requires `content` on assistant messages. null is accepted when tool_calls is
    // present, but with neither text nor tool_calls (e.g. attachment-only or fully-stripped
    // assistant messages) the API rejects null/absent content — use empty string instead.
    put(
        "content",
        when {
            text.isNotEmpty() -> JsonPrimitive(text)
            toolCalls.isNotEmpty() -> JsonPrimitive(null as String?)
            else -> JsonPrimitive("")
        },
    )
    if (toolCalls.isNotEmpty()) {
        putJsonArray("tool_calls") {
            toolCalls.forEach { call ->
                addJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", call.name)
                        put("arguments", call.inputJson)
                    }
                }
            }
        }
    }
}

private fun encodeAttachment(attachment: LlmAttachment): JsonObject {
    requireImageMediaType(attachment.mediaType)
    return buildJsonObject {
        when (attachment) {
            is LlmAttachment.Url -> {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", attachment.url)
                }
            }
            is LlmAttachment.Base64 -> {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", "data:${attachment.mediaType};base64,${attachment.base64}")
                }
            }
        }
    }
}

/**
 * OpenAI Chat Completions only accepts image attachments (via `image_url`). Non-image types
 * (audio, video, files, etc.) must be handled by the Responses API or by providing a text
 * transcription/summary. Fail fast rather than silently sending the wrong wire format.
 */
private fun requireImageMediaType(mediaType: MediaType) {
    require(mediaType.type == "image") {
        "OpenAI Chat Completions only accepts image attachments; got media type $mediaType. " +
            "Use the Responses API for audio/file inputs, or provide a text transcription."
    }
}

// --- Stream parsing --------------------------------------------------------------

/**
 * Parse an OpenAI Chat Completions SSE stream into [LlmStreamEvent]s.
 *
 * Assumptions:
 * - Each SSE event has exactly one `data:` line (OpenAI's current format).
 * - A `data: [DONE]` line terminates the stream.
 * - Tool call arguments arrive in pieces, identified by `choices[0].delta.tool_calls[i].index`.
 * - The optional final `usage` chunk (requested via `stream_options.include_usage`) has
 *   empty `choices` and carries `usage`.
 */
internal class OpenAiStreamParser {
    private val toolCalls = HashMap<Int, ToolCallAccumulator>()
    private var finishReason: String? = null
    private var usage: LlmUsage = LlmUsage(0, 0)
    private val emittedToolIndices = HashSet<Int>()
    private var finishedEmitted = false

    /**
     * Consume one SSE data payload. Returns events to emit in order. A terminal [LlmStreamEvent.Finished]
     * is emitted when the stream naturally ends (via [drain]) rather than from a specific data line.
     */
    fun consume(dataLine: String): List<LlmStreamEvent> {
        val trimmed = dataLine.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed == "[DONE]") return emptyList()
        val chunk = try {
            openAiJson.decodeFromString(ChatCompletionChunk.serializer(), trimmed)
        } catch (e: Exception) {
            // Bogus payload — ignore rather than breaking the stream.
            return emptyList()
        }
        val out = mutableListOf<LlmStreamEvent>()
        chunk.usage?.let {
            usage = LlmUsage(
                inputTokens = it.promptTokens,
                outputTokens = it.completionTokens,
                cacheReadTokens = it.promptTokensDetails?.cachedTokens ?: 0,
            )
        }
        val choice = chunk.choices.firstOrNull()
        if (choice != null) {
            // Emit reasoning before text so accumulators see them in the order the model
            // produced them. In practice providers send these as separate stream frames, but
            // handle same-frame presence here for completeness.
            choice.delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                out.add(LlmStreamEvent.ReasoningDelta(it))
            }
            choice.delta.content?.takeIf { it.isNotEmpty() }?.let {
                out.add(LlmStreamEvent.TextDelta(it))
            }
            choice.delta.toolCalls.forEach { tc ->
                val acc = toolCalls.getOrPut(tc.index) { ToolCallAccumulator(tc.index) }
                tc.id?.let { acc.id = it }
                tc.function?.name?.let { acc.name = it }
                tc.function?.arguments?.let { acc.argumentsBuffer.append(it) }
            }
            choice.finishReason?.let { fr ->
                finishReason = fr
                // On tool_calls, arguments are fully streamed for all calls in this choice; emit them now.
                if (fr == "tool_calls") {
                    emitFinishedToolCalls(out)
                }
            }
        }
        return out
    }

    /**
     * Called once at stream end. Emits any still-unemitted tool calls and exactly one
     * [LlmStreamEvent.Finished] frame with the accumulated stop reason + usage.
     */
    fun drain(): List<LlmStreamEvent> {
        if (finishedEmitted) return emptyList()
        finishedEmitted = true
        val out = mutableListOf<LlmStreamEvent>()
        emitFinishedToolCalls(out)
        out.add(LlmStreamEvent.Finished(mapStopReason(finishReason), usage))
        return out
    }

    private fun emitFinishedToolCalls(out: MutableList<LlmStreamEvent>) {
        toolCalls.values
            .sortedBy { it.index }
            .filter { it.index !in emittedToolIndices && it.name != null && it.id != null }
            .forEach { acc ->
                emittedToolIndices.add(acc.index)
                val args = acc.argumentsBuffer.toString().ifEmpty { "{}" }
                out.add(LlmStreamEvent.ToolCallEmitted(id = acc.id!!, name = acc.name!!, inputJson = args))
            }
    }

    private class ToolCallAccumulator(val index: Int) {
        var id: String? = null
        var name: String? = null
        val argumentsBuffer = StringBuilder()
    }
}

internal fun mapStopReason(openAi: String?): LlmStopReason = when (openAi) {
    "stop" -> LlmStopReason.EndTurn
    "tool_calls" -> LlmStopReason.ToolUse
    "function_call" -> LlmStopReason.ToolUse
    "length" -> LlmStopReason.MaxTokens
    "content_filter" -> {
        // There is no dedicated LlmStopReason for moderation; fall back to EndTurn but
        // surface the event so callers can notice (e.g. retry with a softer prompt).
        wireLogger.warn { "OpenAI returned finish_reason=content_filter; response was moderated." }
        LlmStopReason.EndTurn
    }
    null -> LlmStopReason.EndTurn
    else -> LlmStopReason.EndTurn
}

// --- Error mapping ---------------------------------------------------------------

/**
 * Translate an OpenAI HTTP error response into a typed [LlmException] subclass.
 *
 * Prefers the `error.type` / `error.code` fields from the body (the authoritative provider
 * signal) and falls back to HTTP status when the body is missing or unparseable.
 *
 * @param modelId When known, threaded through to [LlmException.InvalidModel] so callers can
 *   tell which model id was rejected.
 * @param response When supplied, used to read the `retry-after` header for rate-limit hints.
 *   Production call sites pass the live [HttpResponse]; tests can invoke [mapOpenAiError]
 *   with an explicit [retryAfter] instead to bypass the ktor response construction.
 * @param retryAfter Explicit retry-after override; wins over [response]. Exists primarily for
 *   tests that don't want to spin up a MockEngine just to set one header.
 */
internal fun mapOpenAiError(
    status: HttpStatusCode,
    body: String,
    response: HttpResponse? = null,
    modelId: LlmModelId? = null,
    retryAfter: Duration? = null,
): LlmException {
    val error = runCatching {
        openAiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject
    }.getOrNull()
    val type = error?.get("type")?.jsonPrimitive?.content
    val code = error?.get("code")?.jsonPrimitive?.content
    val providerMessage = error?.get("message")?.jsonPrimitive?.content
    val message = providerMessage ?: "OpenAI request failed with status $status"
    val effectiveRetryAfter = retryAfter ?: response?.let { parseRetryAfter(it) }

    // Provider type is authoritative when present. Values documented at
    // https://platform.openai.com/docs/guides/error-codes/api-errors.
    return when (type) {
        "authentication_error" -> LlmException.Auth(message)
        // Per-resource permission errors and account-wide permission errors both map to Auth
        // here; a more granular split would require parsing the free-form message.
        "permission_error" -> LlmException.Auth(message)
        "invalid_request_error" -> {
            if (code == "model_not_found" && modelId != null) {
                LlmException.InvalidModel(modelId, message)
            } else {
                LlmException.InvalidRequest(message)
            }
        }
        "rate_limit_error", "insufficient_quota" -> LlmException.RateLimit(
            message = message,
            retryAfter = effectiveRetryAfter,
        )
        "api_error", "server_error" -> LlmException.ServerError(message)
        else -> when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                LlmException.Auth(message)
            status == HttpStatusCode.NotFound ->
                if (modelId != null) LlmException.InvalidModel(modelId, message)
                else LlmException.InvalidRequest(message)
            status == HttpStatusCode.TooManyRequests ->
                LlmException.RateLimit(message, retryAfter = effectiveRetryAfter)
            status.value in 400..499 -> LlmException.InvalidRequest(message)
            status.value in 500..599 -> LlmException.ServerError(message)
            else -> LlmException.ServerError(message)
        }
    }
}

/**
 * Parse the standard HTTP `Retry-After` header, supporting the "delta-seconds" form only.
 *
 * RFC 7231 also allows an HTTP-date; we return null in that case rather than pulling in a
 * date parser for a rarely used variant — callers handle null as "unknown".
 */
internal fun parseRetryAfter(response: HttpResponse): Duration? {
    val header = response.headers["retry-after"] ?: return null
    return header.trim().toLongOrNull()?.seconds
}
