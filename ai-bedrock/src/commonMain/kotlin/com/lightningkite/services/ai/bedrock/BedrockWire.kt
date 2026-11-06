package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.data.MediaType
import com.lightningkite.services.ai.ClaudeModel
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmReasoningEffort
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmToolCall
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmUsage
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
     * For Claude models, what the model would reject is left out of [requested] (see [ClaudeModel.supported]).
     */
    fun buildRequestBody(
        modelId: String,
        requested: LlmPrompt,
        module: SerializersModule,
    ): JsonObject = buildJsonObject {
        val claude = if ("claude" in modelId.lowercase()) ClaudeModel(modelId) else null
        val prompt = claude?.supported(requested) ?: requested
        // Bedrock's Converse ToolChoice union is {auto, any, tool} — there is no `none`.
        // To honour the LlmToolChoice.None contract (tools still declared so prior
        // toolUse/toolResult blocks in history validate) we keep the tools array, coerce
        // toolChoice back to "auto", and inject a system-message instruction forbidding new
        // tool calls this turn. See interfaces.kt doc for LlmToolChoice.None.
        val suppressToolsPrompt = prompt.tools.isNotEmpty() && prompt.toolChoice == LlmToolChoice.None
        val sendable = mediaTypes(modelId)
        var documentCount = 0
        fun note(attachment: LlmAttachment): JsonObject {
            wireLogger.warn { "Bedrock can't send a ${attachment.mediaType} attachment here to $modelId; sending a note instead." }
            return buildJsonObject { put("text", attachment.notSentNote().text) }
        }
        fun attachmentBlock(attachment: LlmAttachment): JsonObject {
            if (attachment !is LlmAttachment.Base64 || sendable.none { it.accepts(attachment.mediaType) }) return note(attachment)
            val type = attachment.mediaType.withoutParameters
            val source = buildJsonObject { put("bytes", attachment.base64) }
            imageFormats[type]?.let { format ->
                return buildJsonObject { putJsonObject("image") { put("format", format); put("source", source) } }
            }
            videoFormats[type]?.let { format ->
                return buildJsonObject { putJsonObject("video") { put("format", format); put("source", source) } }
            }
            documentCount++
            return buildJsonObject {
                putJsonObject("document") {
                    put("format", documentFormats.getValue(type))
                    // The name reaches the model, so it's ours rather than anything from the caller.
                    put("name", "Document $documentCount")
                    put("source", source)
                }
            }
        }

        val sharedContext = prompt.collectSharedContext()
        val systemBlocks = buildJsonArray {
            if (sharedContext != null) {
                addJsonObject { put("text", sharedContext) }
            }
            prompt.systemPrompt.forEach { part ->
                when (part) {
                    is LlmPart.Text -> addJsonObject { put("text", part.text) }
                    is LlmPart.Attachment -> add(note(part.attachment))
                }
            }
            if (suppressToolsPrompt) {
                addJsonObject { put("text", TOOL_SUPPRESSION_INSTRUCTION) }
            }
        }
        if (systemBlocks.isNotEmpty()) put("system", systemBlocks)

        putJsonArray("messages") {
            // Converse requires roles to alternate, and every result of an assistant turn in the user message
            // right after it, ahead of anything else. So each run of same-role messages becomes one message,
            // and a user run puts its tool results first.
            val turns = mutableListOf<List<LlmMessage>>()
            for (msg in prompt.messages) {
                val previous = turns.lastOrNull()
                if (previous != null && (previous.last() is LlmMessage.Agent) == (msg is LlmMessage.Agent)) turns[turns.lastIndex] = previous + msg
                else turns += listOf(msg)
            }
            turns.forEach { turn ->
                val documentsBefore = documentCount
                val content = mutableListOf<JsonObject>()
                if (turn.first() is LlmMessage.Agent) {
                    turn.filterIsInstance<LlmMessage.Agent>().forEach { msg ->
                        msg.parts.forEach { part ->
                            when (part) {
                                is LlmPart.Text -> content += buildJsonObject { put("text", part.text) }
                                is LlmPart.Attachment -> content += attachmentBlock(part.attachment)
                                is LlmPart.ToolCall -> content += encodeToolCall(part.call)
                                // Unsigned reasoning (from another provider) can't be replayed.
                                is LlmPart.Reasoning -> if (part.signature != null || part.redactedData != null) content += encodeReasoning(part)
                            }
                        }
                        if (msg.cacheBoundary) content += CACHE_POINT_BLOCK
                    }
                } else {
                    turn.filterIsInstance<LlmMessage.ToolResult>().forEach { result ->
                        content += buildJsonObject {
                            putJsonObject("toolResult") {
                                put("toolUseId", result.toolCallId)
                                putJsonArray("content") {
                                    result.parts.forEach { part ->
                                        when (part) {
                                            is LlmPart.Text -> addJsonObject { put("text", part.text) }
                                            is LlmPart.Attachment -> add(attachmentBlock(part.attachment))
                                        }
                                    }
                                }
                                if (result.isError) put("status", "error")
                            }
                        }
                        if (result.cacheBoundary) content += CACHE_POINT_BLOCK
                    }
                    turn.filterIsInstance<LlmMessage.User>().forEach { msg ->
                        msg.parts.forEach { part ->
                            when (part) {
                                is LlmPart.Text -> content += buildJsonObject { put("text", part.text) }
                                is LlmPart.Attachment -> content += attachmentBlock(part.attachment)
                            }
                        }
                        if (msg.cacheBoundary) content += CACHE_POINT_BLOCK
                    }
                }
                // Converse rejects a message with a document but no text block.
                if (documentCount > documentsBefore && content.none { "text" in it }) {
                    content += buildJsonObject { put("text", "(see attached document)") }
                }
                addJsonObject {
                    put("role", if (turn.first() is LlmMessage.Agent) "assistant" else "user")
                    put("content", JsonArray(content))
                }
            }
        }

        // A thinking budget counts toward maxTokens and must stay below it.
        val maxTokens = prompt.maxTokens ?: claude?.thinkingBudget(prompt)?.let { it + CLAUDE_ANSWER_TOKENS }
        if (maxTokens != null || prompt.stopSequences.isNotEmpty()) {
            putJsonObject("inferenceConfig") {
                maxTokens?.let { put("maxTokens", it) }
                if (prompt.stopSequences.isNotEmpty()) {
                    putJsonArray("stopSequences") { prompt.stopSequences.forEach { add(it) } }
                }
            }
        }

        val reasoningFields = if (claude != null) claude.thinkingFields(prompt) else bedrockReasoningFields(modelId, prompt)
        reasoningFields?.let { fields ->
            put("additionalModelRequestFields", fields)
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
                        // Append a cachePoint entry after this tool when marked.
                        if (tool.cacheBoundary) add(CACHE_POINT_BLOCK)
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
    /** Reusable cachePoint block inserted into content/tool arrays for prompt caching. */
    private val CACHE_POINT_BLOCK: JsonObject = buildJsonObject {
        putJsonObject("cachePoint") { put("type", "default") }
    }

    // Room for the answer beside a Claude thinking budget when the caller sets no maxTokens; Anthropic's default.
    private const val CLAUDE_ANSWER_TOKENS: Int = 4096

    internal const val TOOL_SUPPRESSION_INSTRUCTION: String =
        "Do not call any tools on this turn. Respond with text only."

    private fun encodeToolCall(call: LlmToolCall): JsonObject = buildJsonObject {
        putJsonObject("toolUse") {
            put("toolUseId", call.id)
            put("name", call.name)
            // Bedrock expects the tool arguments as an embedded JSON object (not a
            // string), so we parse the caller-provided JSON before inlining it.
            put("input", jsonCodec.parseToJsonElement(call.inputJson.ifEmpty { "{}" }))
        }
    }

    private fun encodeReasoning(part: LlmPart.Reasoning): JsonObject = buildJsonObject {
        putJsonObject("reasoningContent") {
            if (part.redactedData != null) {
                put("redactedContent", part.redactedData)
            } else {
                putJsonObject("reasoningText") {
                    put("text", part.text)
                    put("signature", part.signature)
                }
            }
        }
    }

    private val imageFormats: Map<MediaType, String> = mapOf(
        MediaType.Image.PNG to "png",
        MediaType.Image.JPEG to "jpeg",
        MediaType.Image.GIF to "gif",
        MediaType.Image.WebP to "webp",
    )

    private val documentFormats: Map<MediaType, String> = mapOf(
        MediaType.Application.Pdf to "pdf",
        MediaType("text/csv") to "csv",
        MediaType("application/msword") to "doc",
        MediaType.Application.Docx to "docx",
        MediaType("application/vnd.ms-excel") to "xls",
        MediaType.Application.Xlsx to "xlsx",
        MediaType("text/html") to "html",
        MediaType("text/plain") to "txt",
        MediaType("text/markdown") to "md",
    )

    private val videoFormats: Map<MediaType, String> = mapOf(
        MediaType("video/x-matroska") to "mkv",
        MediaType.Video.MKV to "mkv",
        MediaType.Video.QuickTime to "mov",
        MediaType.Video.MP4 to "mp4",
        MediaType.Video.WEBM to "webm",
        MediaType("video/x-flv") to "flv",
        MediaType.Video.MPEG to "mpeg",
        MediaType("video/x-ms-wmv") to "wmv",
        MediaType("video/3gpp") to "three_gp",
    )

    /** Attachment types Bedrock sends to [modelId]; anything else becomes [LlmAttachment.notSentNote]. */
    fun mediaTypes(modelId: String): List<MediaType> {
        val id = modelId.lowercase()
        val images = imageFormats.keys.toList()
        val documents = documentFormats.keys.toList()
        val video = videoFormats.keys.toList()
        return when {
            "anthropic.claude" in id -> images + documents
            "amazon.nova-micro" in id -> emptyList()
            "amazon.nova" in id -> images + documents + video
            "meta.llama4" in id -> images
            "meta.llama3" in id -> emptyList()
            else -> images + documents
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

    /**
     * Build the model-family-specific reasoning fields injected into Bedrock's
     * `additionalModelRequestFields` for models other than Claude (see [ClaudeModel.thinkingFields]).
     * Returns null when no reasoning is requested.
     *
     * - DeepSeek (`*deepseek*`): `thinking: { type: "enabled", budget_tokens }`. Budget is bucketed
     *   from [LlmPrompt.reasoningEffort].
     * - Nova (`amazon.nova-*`): `reasoning_config: { type: "enabled" }` —
     *   Nova doesn't take effort/budget granularity, just on/off.
     * - Other models: ignored.
     */
    fun bedrockReasoningFields(modelId: String, prompt: LlmPrompt): JsonObject? {
        val effort = prompt.reasoningEffort
        if (effort == null || effort == LlmReasoningEffort.Off) return null

        val lower = modelId.lowercase()
        return when {
            lower.contains("deepseek") -> {
                val resolved = when (effort) {
                    LlmReasoningEffort.Off -> return null
                    LlmReasoningEffort.Minimal -> 1024
                    LlmReasoningEffort.Low -> 2048
                    LlmReasoningEffort.Medium -> 8192
                    LlmReasoningEffort.High -> 16384
                }
                buildJsonObject {
                    putJsonObject("thinking") {
                        put("type", "enabled")
                        put("budget_tokens", resolved)
                    }
                }
            }
            lower.contains("nova") -> buildJsonObject {
                putJsonObject("reasoning_config") {
                    put("type", "enabled")
                }
            }
            else -> null
        }
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
    var cacheWriteTokens: Int = 0
    var stopReason: LlmStopReason = LlmStopReason.EndTurn
    var finished: Boolean = false
    val toolsInFlight: HashMap<Int, ToolCallInProgress> = HashMap()

    // Converse's inputTokens counts only what wasn't read from or written to the cache; LlmUsage.inputTokens is all of it.
    fun usage(): LlmUsage = LlmUsage(inputTokens + cacheReadTokens + cacheWriteTokens, outputTokens, cacheReadTokens, cacheWriteTokens)

    // Diagnostics for pinpointing a truncated stream (populated as bytes are consumed):
    var framesSeen: Int = 0
    var lastEventType: String? = null
    var bytesRead: Long = 0
    var trailingBytesBuffered: Int = 0
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
    val msg = listOfNotNull(type?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() }).joinToString(": ")
        .ifEmpty { status?.let { "Bedrock request failed with status $it" } ?: "Bedrock request failed" }
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
