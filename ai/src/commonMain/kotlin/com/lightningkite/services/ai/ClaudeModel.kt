package com.lightningkite.services.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The request rules that differ between Claude models, shared by the [LlmAccess] implementations that serve
 * Claude (Anthropic, Bedrock). Read from the model id, bare or Bedrock-style: `claude-opus-5-5`,
 * `global.anthropic.claude-sonnet-4-5-20250929-v1:0`, `anthropic.claude-3-7-sonnet-20250219-v1:0`.
 *
 * The rules single out the older models, so a model this library has never seen gets the current rules;
 * an id with no readable version counts as the newest.
 * Source: platform.claude.com/docs/en/build-with-claude/thinking ("Limits and feature compatibility").
 */
public class ClaudeModel(public val id: String) {
    private val match = Regex("""claude-(opus|sonnet|haiku|fable|mythos)-(\d+)(?:-(\d{1,2}))?(?!\d)""").find(id)
    private val family: String? = match?.groupValues?.get(1)
    private val major: Int = match?.groupValues?.get(2)?.toInt() ?: if ("claude-3" in id) 3 else Int.MAX_VALUE
    private val minor: Int = match?.groupValues?.get(3)?.toIntOrNull() ?: 0

    private fun atLeast(major: Int, minor: Int): Boolean =
        this.major > major || (this.major == major && this.minor >= minor)

    // Thinking takes `budget_tokens` on 4.5 and older, which reject adaptive thinking; 4.7+ reject a budget.
    private val usesBudgetThinking: Boolean = !atLeast(4, 6)

    // `temperature` is rejected on every request from 4.7 on; older models reject it only with thinking on.
    private val rejectsSampling: Boolean = atLeast(4, 7)

    // Opus/Sonnet 5.5+ and Fable/Mythos 5.1+ reject `tool_choice` `any`/`tool` on every request (Haiku 5.5 accepts it);
    // every model rejects it with budget thinking on.
    private val rejectsForcedToolChoice: Boolean = when (family) {
        "haiku" -> false
        "fable", "mythos" -> atLeast(5, 1)
        else -> atLeast(5, 5)
    }

    /** The `budget_tokens` for [prompt] on a model that thinks to a budget; null when it doesn't. */
    public fun thinkingBudget(prompt: LlmPrompt): Int? {
        if (!usesBudgetThinking) return null
        prompt.reasoningBudgetTokens?.let { return it.coerceAtLeast(1024) }
        return when (prompt.reasoningEffort) {
            null, LlmReasoningEffort.Off -> null
            LlmReasoningEffort.Minimal -> 1024
            LlmReasoningEffort.Low -> 2048
            LlmReasoningEffort.Medium -> 8192
            LlmReasoningEffort.High -> 16384
        }
    }

    /**
     * The `thinking` and `output_config` request fields for [prompt], or null when it asks for no reasoning.
     * Anthropic puts them at the top level of the body, Bedrock Converse in `additionalModelRequestFields`.
     */
    public fun thinkingFields(prompt: LlmPrompt): JsonObject? {
        if (usesBudgetThinking) {
            val budget = thinkingBudget(prompt) ?: return null
            return buildJsonObject {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }
        }
        val effort = when (prompt.reasoningEffort) {
            null, LlmReasoningEffort.Off -> {
                if (prompt.reasoningBudgetTokens != null) throw LlmException.InvalidRequest(
                    "$id takes a reasoning effort, not a token budget; set LlmPrompt.reasoningEffort instead of reasoningBudgetTokens.",
                )
                return null
            }
            LlmReasoningEffort.Minimal, LlmReasoningEffort.Low -> "low"
            LlmReasoningEffort.Medium -> "medium"
            LlmReasoningEffort.High -> "high"
        }
        return buildJsonObject {
            putJsonObject("thinking") {
                put("type", "adaptive")
                // These models default to "omitted", which streams no reasoning text at all.
                put("display", "summarized")
            }
            putJsonObject("output_config") { put("effort", effort) }
        }
    }

    /** Throws [LlmException.InvalidRequest] for a [prompt] this model would reject, before it is sent. */
    public fun requireSupports(prompt: LlmPrompt) {
        val thinking = thinkingFields(prompt) != null
        if (prompt.temperature != null && (rejectsSampling || thinking)) throw LlmException.InvalidRequest(
            "$id rejects temperature" + (if (rejectsSampling) "" else " while reasoning is on") +
                "; leave LlmPrompt.temperature unset and steer with the prompt instead.",
        )
        val forced = prompt.toolChoice == LlmToolChoice.Required || prompt.toolChoice is LlmToolChoice.Specific
        if (forced && prompt.tools.isNotEmpty() && (rejectsForcedToolChoice || (thinking && usesBudgetThinking))) {
            throw LlmException.InvalidRequest(
                "$id rejects forcing a tool call (LlmToolChoice.${prompt.toolChoice})" +
                    (if (rejectsForcedToolChoice) "" else " while reasoning is on") +
                    "; use LlmToolChoice.Auto, say in the prompt which tool to call, and check that it was called.",
            )
        }
        val budget = thinkingBudget(prompt)
        val maxTokens = prompt.maxTokens
        if (budget != null && maxTokens != null && maxTokens <= budget) throw LlmException.InvalidRequest(
            "$id needs maxTokens above its thinking budget ($budget tokens, which count toward maxTokens); " +
                "got maxTokens=$maxTokens. Raise maxTokens, lower the reasoning effort or budget, or leave maxTokens unset.",
        )
    }
}
