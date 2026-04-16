package com.lightningkite.services.ai.bedrock

import com.lightningkite.MediaType
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.toJsonSchema
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.SerializersModule

/**
 * Marshalling between the abstract [LlmPrompt] / [com.lightningkite.services.ai.LlmStreamEvent]
 * types in `:ai` and the concrete JSON shapes of the Bedrock Converse API.
 *
 * The mapping is kept pure and side-effect free so it can be unit-tested without HTTP.
 */
internal object BedrockWire {

    private val wireLogger = KotlinLogging.logger("BedrockWire")

    val jsonCodec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * Turn an [LlmPrompt] into the JSON body for `POST /model/{id}/converse` or
     * `…/converse-stream`.
     *
     * Bedrock splits system messages out of the main message list: they go in the top-level
     * `system` array. All other messages map 1:1 to Converse `messages`.
     */
    fun buildRequestBody(
        prompt: LlmPrompt,
        module: SerializersModule,
    ): JsonObject = buildJsonObject {
        // Bedrock's Converse ToolChoice union is {auto, any, tool} — there is no `none`.
        // To honour the LlmToolChoice.None contract (tools still declared so prior
        // toolUse/toolResult blocks in history validate) we keep the tools array, coerce
        // toolChoice back to "auto", and inject a system-message instruction forbidding new
        // tool calls this turn. See interfaces.kt doc for LlmToolChoice.None.
        val suppressToolsPrompt = prompt.tools.isNotEmpty() && prompt.toolChoice == LlmToolChoice.None
        val systemBlocks = buildJsonArray {
            prompt.messages
                .filter { it.source == LlmMessageSource.System }
                .forEach { msg ->
                    msg.content.forEach { block ->
                        when (block) {
                            is LlmContent.Text -> addJsonObject { put("text", block.text) }
                            // Bedrock accepts a few other system block types (guardrail,
                            // cachePoint) that the abstraction doesn't expose. Silently drop.
                            else -> {}
                        }
                    }
                }
            if (suppressToolsPrompt) {
                addJsonObject { put("text", TOOL_SUPPRESSION_INSTRUCTION) }
            }
        }
        if (systemBlocks.isNotEmpty()) put("system", systemBlocks)

        putJsonArray("messages") {
            prompt.messages
                .filter { it.source != LlmMessageSource.System }
                .forEach { msg ->
                    addJsonObject {
                        put("role", roleFor(msg.source))
                        putJsonArray("content") {
                            msg.content.forEach { block ->
                                // Reasoning is receive-only in v1 (see encodeContentBlock); skip
                                // rather than emit an empty content object Bedrock would reject.
                                if (block !is LlmContent.Reasoning) add(encodeContentBlock(block))
                            }
                        }
                    }
                }
        }

        if (prompt.maxTokens != null || prompt.temperature != null || prompt.stopSequences.isNotEmpty()) {
            putJsonObject("inferenceConfig") {
                prompt.maxTokens?.let { put("maxTokens", it) }
                prompt.temperature?.let { put("temperature", it) }
                if (prompt.stopSequences.isNotEmpty()) {
                    putJsonArray("stopSequences") { prompt.stopSequences.forEach { add(it) } }
                }
            }
        }

        if (prompt.tools.isNotEmpty()) {
            putJsonObject("toolConfig") {
                putJsonArray("tools") {
                    prompt.tools.forEach { tool ->
                        addJsonObject {
                            putJsonObject("toolSpec") {
                                put("name", tool.name)
                                put("description", tool.description)
                                putJsonObject("inputSchema") {
                                    put("json", tool.toJsonSchema(module))
                                }
                            }
                        }
                    }
                }
                put("toolChoice", toolChoiceObject(prompt.toolChoice))
            }
        }
    }

    /**
     * System instruction appended when [LlmToolChoice.None] is requested but tools must remain
     * declared (to keep prior toolUse/toolResult blocks valid). Bedrock has no toolChoice=none,
     * so we steer the model via prompt instead.
     */
    internal const val TOOL_SUPPRESSION_INSTRUCTION: String =
        "Do not call any tools on this turn. Respond with text only."

    private fun roleFor(source: LlmMessageSource): String = when (source) {
        LlmMessageSource.User -> "user"
        LlmMessageSource.Agent -> "assistant"
        // Tool-result messages are carried back to Bedrock as 'user' messages containing
        // toolResult blocks — same pattern as Anthropic.
        LlmMessageSource.Tool -> "user"
        LlmMessageSource.System -> error("System messages must be routed to the 'system' array")
    }

    private fun encodeContentBlock(block: LlmContent): JsonObject = when (block) {
        is LlmContent.Text -> buildJsonObject { put("text", block.text) }

        is LlmContent.Attachment -> buildJsonObject {
            val att = block.attachment
            val format = imageFormatFor(att.mediaType)
            putJsonObject("image") {
                put("format", format)
                putJsonObject("source") {
                    when (att) {
                        is LlmAttachment.Base64 -> put("bytes", att.base64)
                        is LlmAttachment.Url -> throw IllegalArgumentException(
                            "Bedrock Converse does not support URL image attachments; " +
                                    "fetch the bytes client-side and pass LlmAttachment.Base64 instead.",
                        )
                    }
                }
            }
        }

        is LlmContent.ToolCall -> buildJsonObject {
            putJsonObject("toolUse") {
                put("toolUseId", block.id)
                put("name", block.name)
                // Bedrock expects the tool arguments as an embedded JSON object (not a
                // string), so we parse the caller-provided JSON before inlining it.
                put("input", jsonCodec.parseToJsonElement(block.inputJson.ifEmpty { "{}" }))
            }
        }

        is LlmContent.ToolResult -> buildJsonObject {
            putJsonObject("toolResult") {
                put("toolUseId", block.toolCallId)
                putJsonArray("content") {
                    addJsonObject { put("text", block.content) }
                }
                if (block.isError) put("status", "error")
            }
        }

        // Reasoning is receive-only in v1: round-tripping extended-thinking blocks to Bedrock
        // requires preserving the provider-opaque signature payload, which the abstraction
        // does not yet carry. Filtered out in buildRequestBody before reaching this when, so
        // reaching this branch indicates a caller bypassed the filter.
        is LlmContent.Reasoning -> error("LlmContent.Reasoning must be filtered before encoding")
    }

    /** Map an abstract media type to the Bedrock-accepted image format enum value. */
    private fun imageFormatFor(mediaType: MediaType): String {
        val s = mediaType.toString().lowercase()
        return when {
            "png" in s -> "png"
            "jpeg" in s || "jpg" in s -> "jpeg"
            "gif" in s -> "gif"
            "webp" in s -> "webp"
            else -> throw IllegalArgumentException(
                "Unsupported image mediaType '$s' for Bedrock; expected png, jpeg, gif, or webp.",
            )
        }
    }

    private fun toolChoiceObject(choice: LlmToolChoice): JsonObject = when (choice) {
        is LlmToolChoice.Auto -> buildJsonObject { putJsonObject("auto") {} }
        is LlmToolChoice.Required -> buildJsonObject { putJsonObject("any") {} }
        is LlmToolChoice.Specific -> buildJsonObject {
            putJsonObject("tool") { put("name", choice.name) }
        }
        // Bedrock has no toolChoice=none; caller will also have prepended a
        // "do not call tools" system instruction to actually suppress calls.
        is LlmToolChoice.None -> buildJsonObject { putJsonObject("auto") {} }
    }

    /**
     * Parse the AWS JSON error envelope (`{"__type": "...", "message": "..."}`) that Bedrock
     * returns for pre-stream HTTP errors. Returns the `__type` / `message` pair or nulls when
     * the body is absent or not JSON.
     */
    internal fun parseAwsErrorBody(body: String?): Pair<String?, String?> {
        if (body.isNullOrBlank()) return null to null
        val obj = runCatching { jsonCodec.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null to null
        // AWS uses either `__type` or `code`; some services strip the URL prefix
        // (`com.amazonaws...#ValidationException`) — we normalise to the bare type name.
        val rawType = obj.str("__type") ?: obj.str("code")
        val type = rawType?.substringAfterLast('#')
        val message = obj.str("message") ?: obj.str("Message")
        return type to message
    }

    /** Map Bedrock's `stopReason` string to the abstract enum. */
    fun parseStopReason(s: String?): LlmStopReason = when (s) {
        "end_turn" -> LlmStopReason.EndTurn
        "tool_use" -> LlmStopReason.ToolUse
        "stop_sequence" -> LlmStopReason.StopSequence
        "max_tokens" -> LlmStopReason.MaxTokens
        "content_filtered", "guardrail_intervened" -> {
            // No dedicated LlmStopReason for moderation; fall back to EndTurn but log so
            // operators can notice (e.g. retry with a softer prompt or tune the guardrail).
            wireLogger.warn { "Bedrock returned stopReason=$s; response was moderated or blocked by a guardrail." }
            LlmStopReason.EndTurn
        }
        // Malformed or unknown reasons: still surface as EndTurn so callers finish cleanly.
        else -> LlmStopReason.EndTurn
    }
}

/**
 * Accumulator for an in-progress tool-call: Bedrock streams the JSON input as a sequence of
 * `input` fragments tied to a content-block index, which we concatenate and emit once the
 * block closes.
 */
internal data class ToolCallInProgress(
    val id: String,
    val name: String,
    val argsJson: StringBuilder = StringBuilder(),
)

/** Running state across the lifetime of one ConverseStream response. */
internal class BedrockStreamState {
    var inputTokens: Int = 0
    var outputTokens: Int = 0
    var cacheReadTokens: Int = 0
    var stopReason: LlmStopReason = LlmStopReason.EndTurn
    var finished: Boolean = false
    val toolsInFlight: HashMap<Int, ToolCallInProgress> = HashMap()
}

/**
 * Translate a Bedrock error into a typed [LlmException] subclass.
 *
 * Accepts the authoritative exception type (from either the pre-stream JSON envelope's
 * `__type` field or the mid-stream event-stream `:exception-type` header) and a human-readable
 * message. When called without a type — e.g. because the body was unparseable — [status] is
 * used as a fallback classifier.
 *
 * @param type Bedrock exception type, e.g. `"ValidationException"`. Null if unavailable.
 * @param message Human-readable failure message from the provider, if any.
 * @param status HTTP status, when known, used as a fallback classifier.
 * @param modelId Preserved on [LlmException.InvalidModel] so callers can report which model was rejected.
 * @param cause Underlying exception to preserve on the cause chain, if any.
 */
internal fun mapBedrockError(
    type: String?,
    message: String?,
    status: HttpStatusCode? = null,
    modelId: LlmModelId? = null,
    cause: Throwable? = null,
): LlmException {
    val msg = message?.takeIf { it.isNotBlank() }
        ?: type?.let { "Bedrock request failed: $it" }
        ?: status?.let { "Bedrock request failed with status $it" }
        ?: "Bedrock request failed"
    return when (type) {
        "AccessDeniedException", "UnauthorizedException" ->
            LlmException.Auth(msg, cause)
        "ValidationException" ->
            LlmException.InvalidRequest(msg, cause)
        "ResourceNotFoundException", "ModelNotReadyException" ->
            if (modelId != null) LlmException.InvalidModel(modelId, msg, cause)
            // Without a modelId we can't fill the required field, so fall back to InvalidRequest.
            else LlmException.InvalidRequest(msg, cause)
        "ThrottlingException", "TooManyRequestsException" ->
            // Bedrock does not send a Retry-After header; callers get to pick the backoff.
            LlmException.RateLimit(msg, retryAfter = null, cause = cause)
        "InternalServerException",
        "InternalFailureException",
        "ServiceUnavailableException",
        "ModelErrorException",
        "ModelStreamErrorException",
        "ModelTimeoutException" ->
            LlmException.ServerError(msg, cause)
        null, "" -> when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                LlmException.Auth(msg, cause)
            status == HttpStatusCode.NotFound ->
                if (modelId != null) LlmException.InvalidModel(modelId, msg, cause)
                else LlmException.InvalidRequest(msg, cause)
            status == HttpStatusCode.TooManyRequests ->
                LlmException.RateLimit(msg, retryAfter = null, cause = cause)
            status != null && status.value in 400..499 ->
                LlmException.InvalidRequest(msg, cause)
            status != null && status.value in 500..599 ->
                LlmException.ServerError(msg, cause)
            else -> LlmException.ServerError(msg, cause)
        }
        // Unknown provider-supplied type: fall back to HTTP status mapping.
        else -> when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                LlmException.Auth(msg, cause)
            status == HttpStatusCode.NotFound ->
                if (modelId != null) LlmException.InvalidModel(modelId, msg, cause)
                else LlmException.InvalidRequest(msg, cause)
            status == HttpStatusCode.TooManyRequests ->
                LlmException.RateLimit(msg, retryAfter = null, cause = cause)
            status != null && status.value in 400..499 ->
                LlmException.InvalidRequest(msg, cause)
            status != null && status.value in 500..599 ->
                LlmException.ServerError(msg, cause)
            else -> LlmException.ServerError(msg, cause)
        }
    }
}

/**
 * Helper for pulling nested optional JSON fields. Returns null if any intermediate is missing
 * or not a JsonObject, which is what we want — the caller dispatches on null.
 */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonElement.asObj(): JsonObject? = this as? JsonObject
