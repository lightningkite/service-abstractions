package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmToolCall
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
        // When systemPromptCacheBoundary=true we must use the array-of-blocks
        // form so we can attach cache_control; otherwise the plain string form suffices.
        val sharedContext = prompt.collectSharedContext()
        val systemText = listOfNotNull(
            sharedContext,
            prompt.systemPrompt
                .filterIsInstance<LlmPart.Text>()
                .joinToString("\n\n") { it.text }
                .ifEmpty { null }
        ).joinToString("\n\n")
        if (systemText.isNotEmpty()) {
            if (prompt.systemPromptCacheBoundary) {
                put("system", buildJsonArray {
                    addJsonObject {
                        put("type", "text")
                        put("text", systemText)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                })
            } else {
                put("system", systemText)
            }
        }

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
                        if (tool.cacheBoundary) {
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        }
                    }
                }
            }
            put("tool_choice", toolChoice(prompt.toolChoice))
        }
    }

    /** Map messages to Anthropic's `{role, content:[...]}` array form. */
    fun buildMessages(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        messages.forEach { msg ->
            when (msg) {
                is LlmMessage.User -> addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        val blocks = msg.parts
                        blocks.forEachIndexed { index, part ->
                            val json = contentOnlyBlock(part)
                            if (msg.cacheBoundary && index == blocks.lastIndex) {
                                add(withCacheControl(json))
                            } else {
                                add(json)
                            }
                        }
                    }
                }

                is LlmMessage.Agent -> addJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        // Reasoning blocks are receive-only in v1; round-tripping them back to
                        // Anthropic would require the provider-opaque signature data that the
                        // SSE parser intentionally drops. Filter here instead of failing.
                        val blocks = msg.parts.filter { it !is LlmPart.Reasoning }
                        blocks.forEachIndexed { index, part ->
                            val json = agentPartBlock(part)
                            // Attach cache_control to the LAST content block when the message
                            // is marked as a cache boundary.
                            if (msg.cacheBoundary && index == blocks.lastIndex) {
                                add(withCacheControl(json))
                            } else {
                                add(json)
                            }
                        }
                    }
                }

                is LlmMessage.ToolResult -> addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        val resultBlock = buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", msg.toolCallId)
                            val textContent = msg.parts
                                .filterIsInstance<LlmPart.Text>()
                                .joinToString("") { it.text }
                            put("content", textContent)
                            if (msg.isError) put("is_error", true)
                        }
                        if (msg.cacheBoundary && msg.parts.none { it is LlmPart.Attachment }) {
                            add(withCacheControl(resultBlock))
                        } else {
                            add(resultBlock)
                        }
                        // Include attachment parts if present
                        val attachments = msg.parts.filterIsInstance<LlmPart.Attachment>()
                        attachments.forEachIndexed { index, part ->
                            val json = attachmentBlock(part.attachment)
                            if (msg.cacheBoundary && index == attachments.lastIndex) {
                                add(withCacheControl(json))
                            } else {
                                add(json)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Serialize a single [LlmPart] from an Agent message to Anthropic's content-block JSON shape. */
    fun agentPartBlock(part: LlmPart): JsonObject = when (part) {
        is LlmPart.Text -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }

        is LlmPart.Attachment -> attachmentBlock(part.attachment)

        is LlmPart.ToolCall -> buildJsonObject {
            put("type", "tool_use")
            put("id", part.call.id)
            put("name", part.call.name)
            // Anthropic expects a parsed JSON object here, not a string.
            put("input", parseInputJson(part.call.inputJson))
        }

        // Reasoning blocks must be filtered out by [buildMessages] before reaching here
        // because v1 can't round-trip them (missing signature data). Reaching this branch
        // is a bug in the caller path.
        is LlmPart.Reasoning ->
            error("LlmPart.Reasoning must be filtered by buildMessages; it is receive-only in v1")
    }

    /** Serialize a [LlmPart.ContentOnly] (text or attachment) to Anthropic's content-block JSON shape. */
    fun contentOnlyBlock(part: LlmPart.ContentOnly): JsonObject = when (part) {
        is LlmPart.Text -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }

        is LlmPart.Attachment -> attachmentBlock(part.attachment)
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

    /** Clone a content-block JSON object with `cache_control: {type: "ephemeral"}` appended. */
    private fun withCacheControl(block: JsonObject): JsonObject = buildJsonObject {
        block.forEach { (k, v) -> put(k, v) }
        putJsonObject("cache_control") { put("type", "ephemeral") }
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
