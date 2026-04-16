package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.toJsonSchema
import kotlinx.serialization.json.JsonArray
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
 * Wire-format helpers translating between [LlmPrompt] and Anthropic's Messages API JSON.
 *
 * All methods work in pure [JsonElement] so the wire format can be unit-tested without
 * an HTTP stack. The actual request is assembled from these pieces in [AnthropicLlmAccess].
 */
internal object AnthropicWire {

    /**
     * Build a full POST /v1/messages body from the high-level [prompt].
     *
     * @param modelId the Anthropic model id (e.g. `claude-haiku-4-5`)
     * @param prompt the prompt to translate
     * @param module serializers module used to resolve @Contextual tool-arg serializers
     * @param stream when true, requests SSE streaming
     * @param defaultMaxTokens used when [LlmPrompt.maxTokens] is null (Anthropic requires the field)
     */
    fun buildRequestBody(
        modelId: String,
        prompt: LlmPrompt,
        module: SerializersModule,
        stream: Boolean,
        defaultMaxTokens: Int,
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("max_tokens", prompt.maxTokens ?: defaultMaxTokens)
        put("stream", stream)

        prompt.temperature?.let { put("temperature", it) }
        if (prompt.stopSequences.isNotEmpty()) {
            putJsonArray("stop_sequences") { prompt.stopSequences.forEach { add(it) } }
        }

        // Anthropic carries system as a top-level field, not a message role.
        val systemText = prompt.messages
            .filter { it.source == LlmMessageSource.System }
            .flatMap { it.content }
            .filterIsInstance<LlmContent.Text>()
            .joinToString("\n\n") { it.text }
        if (systemText.isNotEmpty()) put("system", systemText)

        put("messages", buildMessages(prompt.messages))

        // Tools must stay visible whenever the prompt declares any, even when the caller
        // sets [LlmToolChoice.None]. If prior assistant/tool messages in the history contain
        // tool_use/tool_result blocks, Anthropic validates them against the tools list and
        // rejects the request if the tools field is absent. The `tool_choice: {"type":"none"}`
        // value forbids new calls without hiding the declarations.
        if (prompt.tools.isNotEmpty()) {
            putJsonArray("tools") {
                prompt.tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.toJsonSchema(module))
                    }
                }
            }
            put("tool_choice", toolChoice(prompt.toolChoice))
        }
    }

    /** Map non-system messages to Anthropic's `{role, content:[...]}` array form. */
    fun buildMessages(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        messages
            .filter { it.source != LlmMessageSource.System }
            .forEach { msg ->
                addJsonObject {
                    put("role", roleFor(msg.source))
                    putJsonArray("content") {
                        // Reasoning blocks are receive-only in v1; round-tripping them back to
                        // Anthropic would require the provider-opaque signature data that the
                        // SSE parser intentionally drops. Filter here instead of failing.
                        msg.content
                            .filter { it !is LlmContent.Reasoning }
                            .forEach { add(contentBlock(it)) }
                    }
                }
            }
    }

    /**
     * Anthropic only has "user" and "assistant" message roles. Tool results are carried
     * in a user-role message containing `tool_result` blocks, per the API contract.
     */
    private fun roleFor(source: LlmMessageSource): String = when (source) {
        LlmMessageSource.User -> "user"
        LlmMessageSource.Agent -> "assistant"
        LlmMessageSource.Tool -> "user"
        LlmMessageSource.System ->
            error("System messages must be lifted to the top-level `system` field, not a role")
    }

    /** Serialize a single [LlmContent] block to Anthropic's content-block JSON shape. */
    fun contentBlock(content: LlmContent): JsonObject = when (content) {
        is LlmContent.Text -> buildJsonObject {
            put("type", "text")
            put("text", content.text)
        }

        is LlmContent.Attachment -> attachmentBlock(content.attachment)

        is LlmContent.ToolCall -> buildJsonObject {
            put("type", "tool_use")
            put("id", content.id)
            put("name", content.name)
            // Anthropic expects a parsed JSON object here, not a string.
            put("input", parseInputJson(content.inputJson))
        }

        is LlmContent.ToolResult -> buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", content.toolCallId)
            put("content", content.content)
            if (content.isError) put("is_error", true)
        }

        // Reasoning blocks must be filtered out by [buildMessages] before reaching here
        // because v1 can't round-trip them (missing signature data). Reaching this branch
        // is a bug in the caller path.
        is LlmContent.Reasoning ->
            error("LlmContent.Reasoning must be filtered by buildMessages; it is receive-only in v1")
    }

    private fun attachmentBlock(attachment: LlmAttachment): JsonObject = buildJsonObject {
        put("type", "image")
        when (attachment) {
            is LlmAttachment.Base64 -> putJsonObject("source") {
                put("type", "base64")
                put("media_type", attachment.mediaType.toString())
                put("data", attachment.base64)
            }

            is LlmAttachment.Url -> putJsonObject("source") {
                put("type", "url")
                put("url", attachment.url)
            }
        }
    }

    /** Decode a tool-call argument JSON string to an object for the wire; fail fast if invalid. */
    private fun parseInputJson(inputJson: String): JsonObject {
        val parsed = runCatching { jsonCodec.parseToJsonElement(inputJson) }
            .getOrElse { throw IllegalArgumentException("ToolCall.inputJson is not valid JSON: $inputJson", it) }
        return parsed as? JsonObject
            ?: throw IllegalArgumentException("ToolCall.inputJson must encode a JSON object, got ${parsed::class.simpleName}")
    }

    fun toolChoice(choice: LlmToolChoice): JsonObject = when (choice) {
        LlmToolChoice.Auto -> buildJsonObject { put("type", "auto") }
        LlmToolChoice.None -> buildJsonObject { put("type", "none") }
        LlmToolChoice.Required -> buildJsonObject { put("type", "any") }
        is LlmToolChoice.Specific -> buildJsonObject {
            put("type", "tool")
            put("name", choice.name)
        }
    }

    /** Map Anthropic's `stop_reason` string to the abstract [LlmStopReason]. */
    fun parseStopReason(raw: String?): LlmStopReason = when (raw) {
        "end_turn" -> LlmStopReason.EndTurn
        "tool_use" -> LlmStopReason.ToolUse
        "stop_sequence" -> LlmStopReason.StopSequence
        "max_tokens" -> LlmStopReason.MaxTokens
        // "refusal" or any unknown value — treat as a natural completion so the caller
        // sees the text that was produced; the ToolUse contract is specifically for the
        // caller-runs-tool loop, and refusals don't fit that.
        else -> LlmStopReason.EndTurn
    }

    /** Local Json instance; Anthropic never sends comments/trailing commas, so defaults suffice. */
    val jsonCodec: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }
}
