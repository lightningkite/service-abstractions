package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.ai.toJsonSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
     * [LlmPrompt.toolChoice] is advisory: Ollama's native API has no field for tool choice,
     * so we emit a hint via the system prompt when the caller requires or forbids tool use.
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
            // Collect all system-level content: systemPrompt, shared context, tool choice hint.
            val systemParts = buildList {
                // System prompt parts (text only)
                prompt.systemPrompt.filterIsInstance<LlmPart.Text>().forEach { add(it.text) }
                // Shared context from tools
                prompt.collectSharedContext()?.let { add(it) }
                // Tool choice hint
                toolChoiceHint(prompt.toolChoice)?.let { add(it) }
            }
            if (systemParts.isNotEmpty()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemParts.joinToString("\n\n"))
                })
            }
            prompt.messages.forEach { msg ->
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
     * Each message variant maps to its Ollama role:
     * - [LlmMessage.User] -> "user"
     * - [LlmMessage.Agent] -> "assistant"
     * - [LlmMessage.ToolResult] -> "tool"
     *
     * We omit Ollama's `tool_name` field (which expects the function name, not the call id);
     * Ollama is lenient about missing tool_name. We still surface `tool_call_id` for wire-level
     * parity with OpenAI-style callers that mix this adapter in; Ollama ignores unknown fields.
     */
    private fun messageToJson(msg: LlmMessage): JsonObject = buildJsonObject {
        when (msg) {
            is LlmMessage.User -> {
                put("role", "user")
                val textParts = msg.parts.filterIsInstance<LlmPart.Text>()
                val attachments = msg.parts.filterIsInstance<LlmPart.Attachment>().map { it.attachment }
                put("content", textParts.joinToString("") { it.text })
                emitAttachments(attachments)
            }
            is LlmMessage.Agent -> {
                put("role", "assistant")
                val textParts = msg.parts.filterIsInstance<LlmPart.Text>()
                val attachments = msg.parts.filterIsInstance<LlmPart.Attachment>().map { it.attachment }
                val toolCalls = msg.parts.filterIsInstance<LlmPart.ToolCall>()
                put("content", textParts.joinToString("") { it.text })
                emitAttachments(attachments)
                if (toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        toolCalls.forEach { tc ->
                            addJsonObject {
                                putJsonObject("function") {
                                    put("name", tc.call.name)
                                    // Ollama expects `arguments` as a parsed JSON object, not a string.
                                    val parsed: JsonElement = try {
                                        json.parseToJsonElement(tc.call.inputJson)
                                    } catch (e: Exception) {
                                        throw IllegalArgumentException(
                                            "LlmPart.ToolCall.inputJson must be valid JSON for Ollama; got: ${tc.call.inputJson}",
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
            is LlmMessage.ToolResult -> {
                put("role", "tool")
                val textParts = msg.parts.filterIsInstance<LlmPart.Text>()
                put("content", textParts.joinToString("") { it.text })
                put("tool_call_id", msg.toolCallId)
            }
        }
    }

    /**
     * Emit base64 image attachments into the `images` array. Throws if any URL attachments
     * are present, since Ollama's native API does not support image URLs.
     */
    private fun JsonObjectBuilder.emitAttachments(attachments: List<LlmAttachment>) {
        if (attachments.isEmpty()) return
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

    /**
     * Return a tool choice hint string for Ollama's system prompt. Ollama has no first-class
     * tool choice field, so we surface the intent as a system-level instruction.
     */
    private fun toolChoiceHint(choice: LlmToolChoice): String? = when (choice) {
        LlmToolChoice.Auto -> null
        LlmToolChoice.None -> "Do not call any tools in this turn. Respond with natural-language text only."
        LlmToolChoice.Required -> "You MUST call at least one tool in this turn."
        is LlmToolChoice.Specific -> "You MUST call the tool named '${choice.name}' in this turn."
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
     * and re-serialize to string for [com.lightningkite.services.ai.LlmToolCall.inputJson].
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
