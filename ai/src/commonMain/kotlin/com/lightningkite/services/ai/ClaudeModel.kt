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

    // Opus/Sonnet 5.5+ and Fable/Mythos 5.1+ reject `tool_choice` `any`/`tool` on every request (Haiku 5.5 accepts it);
    // every model rejects it with budget thinking on.
    private val rejectsForcedToolChoice: Boolean = when (family) {
        "haiku" -> false
        "fable", "mythos" -> atLeast(5, 1)
        else -> atLeast(5, 5)
    }

    // From 5 on, a model thinks when `thinking` is omitted. Opus 5, Sonnet 5 and Haiku 5.5 can be stopped with
    // `disabled`; Fable/Mythos and Opus/Sonnet 5.5+ reject it and always think.
    private val turnsThinkingOffExplicitly: Boolean = atLeast(5, 0) && when (family) {
        "haiku" -> true
        "fable", "mythos" -> false
        else -> !atLeast(5, 5)
    }

    /**
     * [prompt] without what this model would reject, so one prompt runs on every Claude model: a forced tool call
     * ([LlmToolChoice.Required] / [LlmToolChoice.Specific]) becomes [LlmToolChoice.Auto] on models that reject it.
     * On models that think to a budget, thinking is turned off instead, keeping the call forced.
     */
    public fun supported(prompt: LlmPrompt): LlmPrompt {
        val forced = prompt.toolChoice == LlmToolChoice.Required || prompt.toolChoice is LlmToolChoice.Specific
        return when {
            !forced -> prompt
            rejectsForcedToolChoice -> prompt.copy(toolChoice = LlmToolChoice.Auto)
            thinkingBudget(prompt) != null -> prompt.copy(reasoningEffort = LlmReasoningEffort.Off)
            else -> prompt
        }
    }

    /**
     * The `budget_tokens` for [prompt] on a model that thinks to a budget; null when it doesn't.
     * A budget takes at most half of [LlmPrompt.maxTokens], leaving the rest for the answer;
     * when that is below the 1024-token minimum, the model doesn't think.
     */
    public fun thinkingBudget(prompt: LlmPrompt): Int? {
        if (!usesBudgetThinking) return null
        val requested = when (prompt.reasoningEffort) {
            null, LlmReasoningEffort.Off -> return null
            LlmReasoningEffort.Minimal -> 1024
            LlmReasoningEffort.Low -> 2048
            LlmReasoningEffort.Medium -> 8192
            LlmReasoningEffort.High -> 16384
        }
        val maxTokens = prompt.maxTokens ?: return requested
        return minOf(requested, maxTokens / 2).takeIf { it >= 1024 }
    }

    /**
     * The `thinking` and `output_config` request fields for a [supported] prompt, or null to send neither.
     * Anthropic puts them at the top level of the body, Bedrock Converse in `additionalModelRequestFields`.
     */
    public fun thinkingFields(prompt: LlmPrompt): JsonObject? {
        if (prompt.reasoningEffort == LlmReasoningEffort.Off) {
            if (!turnsThinkingOffExplicitly) return null
            return buildJsonObject { putJsonObject("thinking") { put("type", "disabled") } }
        }
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
            null, LlmReasoningEffort.Off -> return null
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
}
