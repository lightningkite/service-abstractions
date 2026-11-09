package com.lightningkite.services.ai.embedded

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

internal expect fun liteRtModelPath(): String?

/**
 * Runs a real `.litertlm` model given by the `LITERT_MODEL` environment variable, such as Qwen3-0.6B; skipped without
 * it.  On Apple platforms the build also needs `-PliteRtApple`; see the README.
 */
class LiteRtIntegrationTest {
    private suspend fun withModel(block: suspend (EmbeddedLlmAccess, LlmModelId) -> Unit) {
        val liteRtModelPath = liteRtModelPath() ?: run {
            println("SKIP LiteRtIntegrationTest: LITERT_MODEL not set")
            return
        }
        EmbeddedLlmSettings.ensureRegistered()
        val access = LlmAccess.Settings("embedded://test?path=${liteRtModelPath.encodeURLParameter()}")
            .invoke("embedded", TestSettingContext()) as EmbeddedLlmAccess
        access.connect()
        try {
            block(access, access.getModels().single().id)
        } finally {
            access.disconnect()
        }
    }

    @Test
    fun followsSystemPromptAndHistory() = runTest(timeout = 5.minutes) {
        withModel { access, model ->
            val events = access.stream(
                model,
                LlmPrompt(
                    systemPrompt = systemPrompt("You are a terse assistant. Answer with a single word, no punctuation."),
                    messages = listOf(
                        userMessage("My favorite color is teal. Remember it."),
                        LlmMessage.Agent(listOf(LlmPart.Text("Okay"))),
                        userMessage("What is my favorite color?"),
                    ),
                    maxTokens = 200,
                    reasoningEffort = LlmReasoningEffort.Off,
                ),
            ).toList()
            val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            println("Reply: $text")
            println("Finished: ${events.last()}")
            assertTrue("teal" in text.lowercase(), "Expected the model to recall 'teal' from history, got: $text")
            val finished = events.last() as LlmStreamEvent.Finished
            assertTrue(finished.usage.outputTokens > 0, "Output tokens should be counted")
            assertTrue(finished.usage.inputTokens > 0, "Input tokens should be counted")
        }
    }

    /** Assumes a model that thinks, like Qwen3. */
    @Test
    fun reasoningArrivesSeparatelyFromTheAnswer() = runTest(timeout = 5.minutes) {
        withModel { access, model ->
            assertTrue(access.getModels().single().supportsReasoning)
            val events = access.stream(
                model,
                LlmPrompt(messages = listOf(userMessage("What is 2 + 3?")), maxTokens = 60, reasoningEffort = LlmReasoningEffort.Low),
            ).toList()
            val reasoning = events.filterIsInstance<LlmStreamEvent.ReasoningDelta>().joinToString("") { it.text }
            println("Reasoning: $reasoning")
            assertTrue(reasoning.isNotBlank(), "Expected reasoning, got events: $events")
        }
    }

    @Test
    fun maxTokensIsReported() = runTest(timeout = 5.minutes) {
        withModel { access, model ->
            val events = access.stream(
                model,
                LlmPrompt(messages = listOf(userMessage("Write a long story about a lighthouse.")), maxTokens = 10, reasoningEffort = LlmReasoningEffort.Off),
            ).toList()
            val finished = events.last() as LlmStreamEvent.Finished
            println("Finished: $finished")
            assertEquals(LlmStopReason.MaxTokens, finished.stopReason)
        }
    }

    @Test
    fun cancellingStopsGenerationAndLeavesTheEngineUsable() = runTest(timeout = 5.minutes) {
        withModel { access, model ->
            val longPrompt = LlmPrompt(messages = listOf(userMessage("Count from 1 to 500, one number per line.")), maxTokens = 2000, reasoningEffort = LlmReasoningEffort.Off)
            access.stream(model, longPrompt).take(3).collect()
            val events = access.stream(model, LlmPrompt(messages = listOf(userMessage("Say hi.")), maxTokens = 50, reasoningEffort = LlmReasoningEffort.Off)).toList()
            assertTrue(events.last() is LlmStreamEvent.Finished)
        }
    }
}
