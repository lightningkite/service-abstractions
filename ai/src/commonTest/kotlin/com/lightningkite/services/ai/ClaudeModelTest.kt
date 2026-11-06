package com.lightningkite.services.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeModelTest {
    private fun prompt(
        effort: LlmReasoningEffort? = null,
        maxTokens: Int? = null,
        toolChoice: LlmToolChoice = LlmToolChoice.Auto,
    ) = LlmPrompt(
        messages = listOf(userMessage("hi")),
        tools = listOf(LlmToolDescriptor("weather", "Look up weather", serializer<String>())),
        toolChoice = toolChoice,
        maxTokens = maxTokens,
        reasoningEffort = effort,
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun olderModelsThinkToABudget() {
        for (id in listOf(
            "claude-haiku-4-5",
            "claude-sonnet-4-5-20250929",
            "claude-sonnet-4-20250514",
            "claude-opus-4-1",
            "anthropic.claude-3-7-sonnet-20250219-v1:0",
            "global.anthropic.claude-haiku-4-5-20251001-v1:0",
        )) {
            assertEquals(
                json("""{"thinking":{"type":"enabled","budget_tokens":8192}}"""),
                ClaudeModel(id).thinkingFields(prompt(effort = LlmReasoningEffort.Medium)),
                id,
            )
        }
    }

    @Test
    fun claude46AndNewerThinkAdaptivelyWithEffort() {
        for (id in listOf(
            "claude-sonnet-4-6",
            "anthropic.claude-opus-4-6-v1",
            "claude-opus-4-8",
            "claude-opus-5-5",
            "global.anthropic.claude-sonnet-5-5",
            "claude-fable-5-1",
            "claude-haiku-5-5",
            "claude-someday-9",
        )) {
            assertEquals(
                json("""{"thinking":{"type":"adaptive","display":"summarized"},"output_config":{"effort":"high"}}"""),
                ClaudeModel(id).thinkingFields(prompt(effort = LlmReasoningEffort.High)),
                id,
            )
        }
    }

    @Test
    fun noReasoningRequestedSendsNoThinking() {
        for (id in listOf("claude-opus-5-5", "claude-opus-5", "claude-haiku-4-5")) {
            assertNull(ClaudeModel(id).thinkingFields(prompt()), id)
        }
    }

    @Test
    fun offDisablesThinkingWhereOmittingItWouldThink() {
        for (id in listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-5-5")) {
            assertEquals(
                json("""{"thinking":{"type":"disabled"}}"""),
                ClaudeModel(id).thinkingFields(prompt(effort = LlmReasoningEffort.Off)),
                id,
            )
        }
        // Older models don't think unless asked; the newest can't stop.
        for (id in listOf("claude-opus-4-8", "claude-haiku-4-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5", "claude-fable-5-1")) {
            assertNull(ClaudeModel(id).thinkingFields(prompt(effort = LlmReasoningEffort.Off)), id)
        }
    }

    @Test
    fun forcedToolChoiceBecomesAutoOnModelsThatRejectIt() {
        for (id in listOf("claude-opus-5-5", "global.anthropic.claude-sonnet-5-5", "claude-fable-5-1", "claude-mythos-5-1")) {
            for (choice in listOf(LlmToolChoice.Required, LlmToolChoice.Specific("weather"))) {
                assertEquals(LlmToolChoice.Auto, ClaudeModel(id).supported(prompt(toolChoice = choice)).toolChoice, "$id $choice")
            }
        }
        for (id in listOf("claude-haiku-5-5", "claude-opus-5", "claude-fable-5", "claude-sonnet-4-6", "claude-haiku-4-5")) {
            assertEquals(LlmToolChoice.Required, ClaudeModel(id).supported(prompt(toolChoice = LlmToolChoice.Required)).toolChoice, id)
        }
        // Adaptive thinking accepts a forced call (the model skips thinking); budget thinking doesn't, so it's turned off.
        val adaptive = ClaudeModel("claude-opus-4-8").supported(prompt(toolChoice = LlmToolChoice.Required, effort = LlmReasoningEffort.High))
        assertEquals(LlmReasoningEffort.High, adaptive.reasoningEffort)
        val budget = ClaudeModel("claude-haiku-4-5").supported(prompt(toolChoice = LlmToolChoice.Required, effort = LlmReasoningEffort.High))
        assertEquals(LlmToolChoice.Required, budget.toolChoice)
        assertNull(ClaudeModel("claude-haiku-4-5").thinkingFields(budget))
    }

    @Test
    fun aThinkingBudgetTakesAtMostHalfOfMaxTokens() {
        val haiku = ClaudeModel("claude-haiku-4-5")
        assertEquals(8192, haiku.thinkingBudget(prompt(effort = LlmReasoningEffort.Medium)))
        assertEquals(4500, haiku.thinkingBudget(prompt(effort = LlmReasoningEffort.Medium, maxTokens = 9000)))
        assertNull(haiku.thinkingBudget(prompt(effort = LlmReasoningEffort.Medium, maxTokens = 2000)))
        assertNull(ClaudeModel("claude-opus-5-5").thinkingBudget(prompt(effort = LlmReasoningEffort.High, maxTokens = 1000)))
    }
}
