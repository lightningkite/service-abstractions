package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.ai.toJsonSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.SerializersModule

/**
 * Request/response DTOs and wire mappers for Ollama's native `/api/chat` endpoint.
 *
 * Protocol reference: https://github.com/ollama/ollama/blob/main/docs/api.md
 *
 * Key differences from OpenAI's wire format:
 * - `tool_calls[].function.arguments` is a **parsed JSON object**, not a stringified JSON.
 * - Tool-result messages use `tool_call_id` (we omit Ollama's `tool_name`, which wants the
 *   function name rather than the opaque call id; Ollama accepts tool messages without it).
 * - Image attachments go at message level as `images: [base64...]`.
 * - Streaming uses NDJSON (newline-delimited JSON), one frame per line. Tool calls arrive
 *   as complete objects in a single NDJSON line (not streamed piecewise).
 */
internal object OllamaWire {

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * Build the JSON body for POST /api/chat.
     *
     * [toolChoice] is advisory: Ollama's native API has no field for tool choice, so we
     * emit a hint via the system prompt when the caller requires or forbids tool use.
     */
    internal fun buildChatRequest(
        model: String,
        prompt: LlmPrompt,
        module: SerializersModule,
        stream: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", stream)

        putJsonArray("messages") {
            prompt.collectSharedContext()?.let { ctx ->
                add(buildJsonObject {
                    put("role", "system")
                    put("content", ctx)
                })
            }
            val hintedMessages = applyToolChoiceHint(prompt.messages, prompt.toolChoice)
            hintedMessages.forEach { msg ->
                add(messageToJson(msg))
            }
        }

        if (prompt.tools.isNotEmpty() && prompt.toolChoice != LlmToolChoice.None) {
            putJsonArray("tools") {
                prompt.tools.forEach { tool ->
                    add(toolToJson(tool, module))
                }
            }
        }

        val hasOptions = prompt.temperature != null ||
            prompt.maxTokens != null ||
            prompt.stopSequences.isNotEmpty()
        if (hasOptions) {
            putJsonObject("options") {
                prompt.temperature?.let { put("temperature", it) }
                prompt.maxTokens?.let { put("num_predict", it) }
                if (prompt.stopSequences.isNotEmpty()) {
                    putJsonArray("stop") { prompt.stopSequences.forEach { add(it) } }
                }
            }
        }
    }

    /**
     * Encode a single [LlmMessage] in Ollama's wire format.
     *
     * Invariant (from upstream [splitToolResultMessages]): a Tool-sourced message contains
     * exactly one [LlmContent.ToolResult]. Multiple results in a single message are split
     * before reaching this function.
     *
     * We omit Ollama's `tool_name` field (which expects the function name, not the call id);
     * our [LlmContent.ToolResult] only carries [LlmContent.ToolResult.toolCallId], and Ollama
     * is lenient about missing tool_name. We still surface `tool_call_id` for wire-level
     * parity with OpenAI-style callers that mix this adapter in; Ollama ignores unknown fields.
     */
    private fun messageToJson(msg: LlmMessage): JsonObject = buildJsonObject {
        put("role", roleString(msg.source))

        val textParts = msg.content.filterIsInstance<LlmContent.Text>()
        val attachments = msg.content.filterIsInstance<LlmContent.Attachment>().map { it.attachment }
        val toolCalls = msg.content.filterIsInstance<LlmContent.ToolCall>()
        val toolResults = msg.content.filterIsInstance<LlmContent.ToolResult>()

        val content = when {
            msg.source == LlmMessageSource.Tool && toolResults.isNotEmpty() ->
                toolResults.single().content
            else -> textParts.joinToString("") { it.text }
        }
        put("content", content)

        if (msg.source == LlmMessageSource.Tool && toolResults.isNotEmpty()) {
            put("tool_call_id", toolResults.single().toolCallId)
        }

        if (attachments.isNotEmpty()) {
            val base64s = attachments.mapNotNull {
                when (it) {
                    is LlmAttachment.Base64 -> it.base64
                    is LlmAttachment.Url -> null
                }
            }
            val urls = attachments.filterIsInstance<LlmAttachment.Url>()
            if (urls.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Ollama native API does not accept image URLs; convert to LlmAttachment.Base64 before calling."
                )
            }
            if (base64s.isNotEmpty()) {
                putJsonArray("images") { base64s.forEach { add(it) } }
            }
        }

        if (toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                toolCalls.forEach { call ->
                    addJsonObject {
                        putJsonObject("function") {
                            put("name", call.name)
                            // Ollama expects `arguments` as a parsed JSON object, not a string.
                            val parsed: JsonElement = try {
                                json.parseToJsonElement(call.inputJson)
                            } catch (e: Exception) {
                                throw IllegalArgumentException(
                                    "LlmContent.ToolCall.inputJson must be valid JSON for Ollama; got: ${call.inputJson}",
                                    e,
                                )
                            }
                            put("arguments", parsed)
                        }
                    }
                }
            }
        }
    }

    /**
     * Expand Tool-sourced messages containing multiple [LlmContent.ToolResult] blocks
     * into one message per result, matching Ollama's "one tool message per result" shape.
     */
    private fun splitToolResultMessages(messages: List<LlmMessage>): List<LlmMessage> =
        messages.flatMap { msg ->
            if (msg.source != LlmMessageSource.Tool) listOf(msg)
            else {
                val results = msg.content.filterIsInstance<LlmContent.ToolResult>()
                if (results.size <= 1) listOf(msg)
                else results.map { LlmMessage(LlmMessageSource.Tool, listOf(it)) }
            }
        }

    /**
     * Apply [LlmToolChoice] as a soft system-level hint. Ollama has no first-class tool
     * choice field, so we prepend or append an instruction.
     */
    private fun applyToolChoiceHint(
        messages: List<LlmMessage>,
        choice: LlmToolChoice,
    ): List<LlmMessage> {
        val split = splitToolResultMessages(messages)
        val hint = when (choice) {
            LlmToolChoice.Auto -> null
            LlmToolChoice.None -> "Do not call any tools in this turn. Respond with natural-language text only."
            LlmToolChoice.Required -> "You MUST call at least one tool in this turn."
            is LlmToolChoice.Specific -> "You MUST call the tool named '${choice.name}' in this turn."
        } ?: return split

        // Merge with an existing system message if present, otherwise prepend one.
        val firstIsSystem = split.firstOrNull()?.source == LlmMessageSource.System
        return if (firstIsSystem) {
            val existing = split.first()
            val combined = LlmMessage(
                LlmMessageSource.System,
                existing.content + LlmContent.Text("\n\n$hint"),
            )
            listOf(combined) + split.drop(1)
        } else {
            listOf(LlmMessage(LlmMessageSource.System, listOf(LlmContent.Text(hint)))) + split
        }
    }

    private fun toolToJson(tool: LlmToolDescriptor<*>, module: SerializersModule): JsonObject =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.toJsonSchema(module))
            }
        }

    private fun roleString(source: LlmMessageSource): String = when (source) {
        LlmMessageSource.System -> "system"
        LlmMessageSource.User -> "user"
        LlmMessageSource.Agent -> "assistant"
        LlmMessageSource.Tool -> "tool"
    }
}

// ----------------------------------------------------------------------------------------
// Streaming response DTOs
// ----------------------------------------------------------------------------------------

/** One NDJSON frame from /api/chat streaming response. */
@Serializable
internal data class OllamaChatStreamFrame(
    val model: String? = null,
    val created_at: String? = null,
    val message: OllamaStreamMessage? = null,
    val done: Boolean = false,
    val done_reason: String? = null,
    val total_duration: Long? = null,
    val load_duration: Long? = null,
    val prompt_eval_count: Int? = null,
    val prompt_eval_duration: Long? = null,
    val eval_count: Int? = null,
    val eval_duration: Long? = null,
)

@Serializable
internal data class OllamaStreamMessage(
    val role: String? = null,
    val content: String? = null,
    val thinking: String? = null,
    val tool_calls: List<OllamaToolCall>? = null,
    val images: List<String>? = null,
)

@Serializable
internal data class OllamaToolCall(
    /** Optional: some Ollama builds include an id; if absent, callers fabricate one. */
    val id: String? = null,
    val function: OllamaToolCallFunction,
)

@Serializable
internal data class OllamaToolCallFunction(
    val name: String,
    /**
     * Parsed JSON object in Ollama's wire format — but we receive it as a raw JsonElement
     * and re-serialize to string for [com.lightningkite.services.ai.LlmContent.ToolCall.inputJson].
     */
    val arguments: JsonElement,
)

// ----------------------------------------------------------------------------------------
// /api/tags — list local models
// ----------------------------------------------------------------------------------------

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaTagEntry> = emptyList(),
)

@Serializable
internal data class OllamaTagEntry(
    val name: String,
    val model: String? = null,
    val modified_at: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: OllamaTagDetails? = null,
)

@Serializable
internal data class OllamaTagDetails(
    val parent_model: String? = null,
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    val parameter_size: String? = null,
    val quantization_level: String? = null,
)
