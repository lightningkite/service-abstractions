package com.lightningkite.services.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeModelTest {
    private fun prompt(
        effort: LlmReasoningEffort? = null,
        budget: Int? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        toolChoice: LlmToolChoice = LlmToolChoice.Auto,
    ) = LlmPrompt(
        messages = listOf(userMessage("hi")),
        tools = listOf(LlmToolDescriptor("weather", "Look up weather", serializer<String>())),
        toolChoice = toolChoice,
        maxTokens = maxTokens,
        temperature = temperature,
        reasoningEffort = effort,
        reasoningBudgetTokens = budget,
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
        assertNull(ClaudeModel("claude-opus-5-5").thinkingFields(prompt()))
        assertNull(ClaudeModel("claude-opus-5-5").thinkingFields(prompt(effort = LlmReasoningEffort.Off)))
        assertNull(ClaudeModel("claude-haiku-4-5").thinkingFields(prompt(effort = LlmReasoningEffort.Off)))
    }

    @Test
    fun aBudgetAloneIsRejectedOnAdaptiveModels() {
        val e = assertFailsWith<LlmException.InvalidRequest> {
            ClaudeModel("claude-sonnet-5-5").requireSupports(prompt(budget = 4000))
        }
        assertTrue("reasoningEffort" in e.message!!, e.message)
    }

    @Test
    fun temperatureIsRejectedOnCurrentModels() {
        for (id in listOf("claude-opus-4-7", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1")) {
            val e = assertFailsWith<LlmException.InvalidRequest>(id) {
                ClaudeModel(id).requireSupports(prompt(temperature = 0.0))
            }
            assertTrue(id in e.message!! && "temperature" in e.message!!, e.message)
        }
    }

    @Test
    fun temperatureIsRejectedWithThinkingOnOlderModels() {
        ClaudeModel("claude-haiku-4-5").requireSupports(prompt(temperature = 0.0))
        ClaudeModel("claude-sonnet-4-6").requireSupports(prompt(temperature = 0.0))
        assertFailsWith<LlmException.InvalidRequest> {
            ClaudeModel("claude-haiku-4-5").requireSupports(prompt(temperature = 0.0, effort = LlmReasoningEffort.Low))
        }
        assertFailsWith<LlmException.InvalidRequest> {
            ClaudeModel("claude-sonnet-4-6").requireSupports(prompt(temperature = 0.0, effort = LlmReasoningEffort.Low))
        }
    }

    @Test
    fun forcedToolChoiceIsRejectedOnModelsThatRejectIt() {
        for (id in listOf("claude-opus-5-5", "global.anthropic.claude-sonnet-5-5", "claude-fable-5-1", "claude-mythos-5-1")) {
            for (choice in listOf(LlmToolChoice.Required, LlmToolChoice.Specific("weather"))) {
                val e = assertFailsWith<LlmException.InvalidRequest>("$id $choice") {
                    ClaudeModel(id).requireSupports(prompt(toolChoice = choice))
                }
                assertTrue(id in e.message!! && "LlmToolChoice.Auto" in e.message!!, e.message)
            }
        }
        for (id in listOf("claude-haiku-5-5", "claude-opus-5", "claude-fable-5", "claude-sonnet-4-6", "claude-haiku-4-5")) {
            ClaudeModel(id).requireSupports(prompt(toolChoice = LlmToolChoice.Required))
        }
        // Adaptive thinking accepts a forced call (the model skips thinking); budget thinking doesn't.
        ClaudeModel("claude-opus-4-8").requireSupports(prompt(toolChoice = LlmToolChoice.Required, effort = LlmReasoningEffort.High))
        assertFailsWith<LlmException.InvalidRequest> {
            ClaudeModel("claude-haiku-4-5").requireSupports(prompt(toolChoice = LlmToolChoice.Required, effort = LlmReasoningEffort.High))
        }
    }

    @Test
    fun maxTokensMustExceedTheThinkingBudget() {
        val e = assertFailsWith<LlmException.InvalidRequest> {
            ClaudeModel("claude-haiku-4-5").requireSupports(prompt(effort = LlmReasoningEffort.Medium, maxTokens = 4096))
        }
        assertTrue("8192" in e.message!! && "4096" in e.message!!, e.message)
        ClaudeModel("claude-haiku-4-5").requireSupports(prompt(effort = LlmReasoningEffort.Medium, maxTokens = 9000))
        ClaudeModel("claude-opus-5-5").requireSupports(prompt(effort = LlmReasoningEffort.High, maxTokens = 1000))
    }
}
