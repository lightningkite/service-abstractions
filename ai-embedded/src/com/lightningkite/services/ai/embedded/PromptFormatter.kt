package com.lightningkite.services.ai.embedded

import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt

/**
 * Chat template format for on-device models.
 *
 * Different models expect different special token wrappers around messages.
 * The [PromptFormatter] applies the correct template based on model name.
 */
public enum class ChatTemplate {
    /** `<|im_start|>role\ncontent<|im_end|>` — used by many fine-tuned models */
    ChatML,
    /** `<|start_header_id|>role<|end_header_id|>\ncontent<|eot_id|>` — Llama 3+ */
    Llama,
    /** `<start_of_turn>role\ncontent<end_of_turn>` — Gemma */
    Gemma,
    /** `<|role|>\ncontent<|end|>` — Phi */
    Phi,
}

/**
 * Formats an [LlmPrompt] into a raw text string for on-device model consumption.
 *
 * On-device models don't accept structured message arrays like cloud APIs.
 * This formatter applies the model's chat template to produce a single prompt string.
 */
public object PromptFormatter {
    private val modelTemplateMap = mapOf(
        "llama" to ChatTemplate.Llama,
        "gemma" to ChatTemplate.Gemma,
        "phi" to ChatTemplate.Phi,
        "chatml" to ChatTemplate.ChatML,
        "qwen" to ChatTemplate.ChatML,
        "mistral" to ChatTemplate.ChatML,
    )

    /** Selects a chat template based on model name prefix. Falls back to ChatML. */
    public fun templateFor(modelName: String): ChatTemplate {
        val lower = modelName.lowercase()
        for ((prefix, template) in modelTemplateMap) {
            if (lower.startsWith(prefix)) return template
        }
        return ChatTemplate.ChatML
    }

    /** Formats an [LlmPrompt] to raw text using the given [template]. */
    public fun format(prompt: LlmPrompt, template: ChatTemplate): String = buildString {
        val systemText = prompt.systemPrompt.joinToString("") { part ->
            when (part) {
                is LlmPart.Text -> part.text
                is LlmPart.Attachment -> "[attachment]"
            }
        }
        if (systemText.isNotEmpty()) {
            appendMessage("system", systemText, template)
        }

        for (message in prompt.messages) {
            when (message) {
                is LlmMessage.User -> {
                    val text = message.parts.joinToString("") { part ->
                        when (part) {
                            is LlmPart.Text -> part.text
                            is LlmPart.Attachment -> "[attachment]"
                        }
                    }
                    appendMessage("user", text, template)
                }
                is LlmMessage.Agent -> {
                    val text = message.parts.joinToString("") { part ->
                        when (part) {
                            is LlmPart.Text -> part.text
                            is LlmPart.Reasoning -> ""
                            is LlmPart.ToolCall -> ""
                            is LlmPart.Attachment -> "[attachment]"
                        }
                    }
                    if (text.isNotEmpty()) {
                        appendMessage("assistant", text, template)
                    }
                }
                is LlmMessage.ToolResult -> {
                    // On-device models generally don't support tool results;
                    // render as a user message with the tool output
                    val text = message.parts.joinToString("") { part ->
                        when (part) {
                            is LlmPart.Text -> part.text
                            is LlmPart.Attachment -> "[attachment]"
                        }
                    }
                    appendMessage("user", text, template)
                }
            }
        }

        // Append the assistant turn opener so the model continues from here
        appendAssistantPrefix(template)
    }

    private fun StringBuilder.appendMessage(role: String, content: String, template: ChatTemplate) {
        when (template) {
            ChatTemplate.ChatML -> {
                append("<|im_start|>")
                append(role)
                append('\n')
                append(content)
                append("<|im_end|>\n")
            }
            ChatTemplate.Llama -> {
                append("<|start_header_id|>")
                append(role)
                append("<|end_header_id|>\n\n")
                append(content)
                append("<|eot_id|>")
            }
            ChatTemplate.Gemma -> {
                append("<start_of_turn>")
                append(if (role == "assistant") "model" else role)
                append('\n')
                append(content)
                append("<end_of_turn>\n")
            }
            ChatTemplate.Phi -> {
                append("<|")
                append(role)
                append("|>\n")
                append(content)
                append("<|end|>\n")
            }
        }
    }

    private fun StringBuilder.appendAssistantPrefix(template: ChatTemplate) {
        when (template) {
            ChatTemplate.ChatML -> append("<|im_start|>assistant\n")
            ChatTemplate.Llama -> append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            ChatTemplate.Gemma -> append("<start_of_turn>model\n")
            ChatTemplate.Phi -> append("<|assistant|>\n")
        }
    }
}
