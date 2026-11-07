package com.lightningkite.services.ai.embedded

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Loads a tiny randomly-initialized model through Transformers.js and chats with it.  The output is gibberish, but
 * it proves the whole path: download, chat template, streaming, token counts.
 *
 * The first run downloads the model from Hugging Face; the browser caches it after that.
 */
class TransformersJsIntegrationTest {
    @Test
    fun loadAndChat() = runTest(timeout = 3.minutes) {
        EmbeddedLlmSettings.ensureRegistered()
        val access = LlmAccess.Settings("embedded://tiny?path=onnx-community/tiny-random-LlamaForCausalLM-ONNX&dtype=fp32&runtimeUrl=/onnxruntime-web/&device=wasm")
            .invoke("embedded", TestSettingContext()) as EmbeddedLlmAccess
        access.connect()
        assertEquals(1f, access.loadProgress.value)

        val events = access.stream(
            access.getModels().single().id,
            LlmPrompt(
                systemPrompt = systemPrompt("Be brief."),
                messages = listOf(userMessage("Hello"), LlmMessage.Agent(listOf(LlmPart.Text("Hi"))), userMessage("Again")),
                maxTokens = 5,
            ),
        ).toList()

        val finished = events.last() as LlmStreamEvent.Finished
        assertTrue(events.filterIsInstance<LlmStreamEvent.TextDelta>().isNotEmpty(), "Expected generated text")
        assertEquals(LlmStopReason.MaxTokens, finished.stopReason)
        assertEquals(5, finished.usage.outputTokens)
        assertTrue(finished.usage.inputTokens > 3, "Input tokens should count the whole templated conversation")
        access.disconnect()
    }
}
