package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmReasoningEffort
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.ai.toJsonSchema
import com.lightningkite.services.data.MediaType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private val logger = KotlinLogging.logger("OllamaWire")

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * Build the JSON body for POST /api/chat.
     *
     * Ollama's native API has no field for tool choice: [LlmToolChoice.None] becomes a system-prompt
     * hint, and a forced choice ([LlmToolChoice.Required] / [LlmToolChoice.Specific]) throws
     * [LlmException.InvalidRequest].
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
                // System messages take text only.
                prompt.systemPrompt.forEach { part ->
                    when (part) {
                        is LlmPart.Text -> add(part.text)
                        is LlmPart.Attachment -> add(notSent(part.attachment).text)
                    }
                }
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
            val supportedMediaTypes = supportedMediaTypes(model)
            prompt.messages.forEach { msg ->
                add(messageToJson(msg, supportedMediaTypes))
            }
        }

        if (prompt.tools.isNotEmpty() && prompt.toolChoice != LlmToolChoice.None) {
            putJsonArray("tools") {
                prompt.tools.forEach { tool ->
                    add(toolToJson(tool, module))
                }
            }
        }

        val hasOptions = prompt.maxTokens != null ||
            prompt.stopSequences.isNotEmpty()
        if (hasOptions) {
            putJsonObject("options") {
                prompt.maxTokens?.let { put("num_predict", it) }
                if (prompt.stopSequences.isNotEmpty()) {
                    putJsonArray("stop") { prompt.stopSequences.forEach { add(it) } }
                }
            }
        }
        ollamaThink(prompt)?.let { put("think", it) }
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
    private fun messageToJson(msg: LlmMessage, supportedMediaTypes: List<MediaType>): JsonObject = buildJsonObject {
        when (msg) {
            is LlmMessage.User -> {
                put("role", "user")
                putContentAndImages(msg.parts, supportedMediaTypes)
            }
            is LlmMessage.Agent -> {
                put("role", "assistant")
                putContentAndImages(msg.parts, supportedMediaTypes)
                val toolCalls = msg.parts.filterIsInstance<LlmPart.ToolCall>()
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
                // Tool messages take text only.
                putContentAndImages(msg.parts, supportedMediaTypes = emptyList())
                put("tool_call_id", msg.toolCallId)
            }
        }
    }

    /**
     * Ollama's native API takes attachments only as base64 image bytes in `images`; there is no
     * URL form. Every other attachment goes into `content` as [LlmAttachment.notSentNote] text.
     */
    private fun JsonObjectBuilder.putContentAndImages(parts: List<LlmPart>, supportedMediaTypes: List<MediaType>) {
        val content = StringBuilder()
        val images = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                is LlmPart.Text -> content.append(part.text)
                is LlmPart.Attachment -> {
                    val attachment = part.attachment
                    if (attachment is LlmAttachment.Base64 && supportedMediaTypes.any { it.accepts(attachment.mediaType) }) {
                        images.add(attachment.base64)
                    } else {
                        content.append(notSent(attachment).text)
                    }
                }
                is LlmPart.Reasoning, is LlmPart.ToolCall -> {}
            }
        }
        put("content", content.toString())
        if (images.isNotEmpty()) {
            putJsonArray("images") { images.forEach { add(it) } }
        }
    }

    private fun notSent(attachment: LlmAttachment): LlmPart.Text {
        logger.warn { "Ollama can't take this ${attachment.mediaType} attachment; sending a note instead." }
        return attachment.notSentNote()
    }

    private val visionModelPatterns = listOf("llava", "bakllava", "moondream", "cogvlm", "vision", "minicpm-v")

    /** Ollama reads images only, and only on vision models, which we recognize by name. */
    internal fun supportedMediaTypes(model: String): List<MediaType> =
        if (visionModelPatterns.any { it in model.lowercase() }) listOf(MediaType.Image.Any) else emptyList()

    /**
     * Return a tool choice hint string for Ollama's system prompt. Ollama has no first-class
     * tool choice field, so [LlmToolChoice.None] becomes a system-level instruction (tools are also
     * left out of the request). A forced call can't be guaranteed that way, so it fails instead.
     */
    private fun toolChoiceHint(choice: LlmToolChoice): String? = when (choice) {
        LlmToolChoice.Auto -> null
        LlmToolChoice.None -> "Do not call any tools in this turn. Respond with natural-language text only."
        LlmToolChoice.Required, is LlmToolChoice.Specific -> throw LlmException.InvalidRequest(
            "Ollama can't force a tool call (LlmToolChoice.$choice); use LlmToolChoice.Auto, " +
                "say in the prompt which tool to call, and check that it was called.",
        )
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

    /**
     * Resolve Ollama's `think` field. Ollama accepts a boolean toggle (older versions)
     * or a level string `"low" | "medium" | "high"` (newer thinking-capable models).
     * `Off` -> `false`, anything else -> level string. Returns null when reasoning is unset.
     */
    private fun ollamaThink(prompt: LlmPrompt): JsonElement? {
        return when (prompt.reasoningEffort) {
            null -> null
            LlmReasoningEffort.Off -> JsonPrimitive(false)
            LlmReasoningEffort.Minimal, LlmReasoningEffort.Low -> JsonPrimitive("low")
            LlmReasoningEffort.Medium -> JsonPrimitive("medium")
            LlmReasoningEffort.High -> JsonPrimitive("high")
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
