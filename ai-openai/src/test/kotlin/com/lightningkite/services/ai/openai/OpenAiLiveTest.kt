package com.lightningkite.services.ai.openai

import com.lightningkite.services.SettingContext
import com.lightningkite.services.SharedResources
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live end-to-end test against the real OpenAI API. Only runs when `OPENAI_API_KEY` is set;
 * otherwise silently no-ops so CI without credentials still passes.
 */
class OpenAiLiveTest {

    private val apiKey: String? = System.getenv("OPENAI_API_KEY")

    private val context: SettingContext = object : SettingContext {
        override val projectName: String = "ai-openai-test"
        override val publicUrl: String = "http://localhost"
        override val internalSerializersModule = EmptySerializersModule()
        override val openTelemetry = null
        override val sharedResources: SharedResources = SharedResources()
    }

    @Test
    fun simpleInference() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val key = apiKey ?: run {
            println("SKIP OpenAiLiveTest.simpleInference: OPENAI_API_KEY not set")
            return@runTest
        }
        val access: LlmAccess = LlmAccess.Settings("openai://gpt-4o-mini?apiKey=$key")
            .invoke("live-test", context)
        val result = access.inference(
            model = LlmModelId("gpt-4o-mini"),
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.User(
                        listOf(LlmPart.Text("Reply with just the word 'pong'.")),
                    ),
                ),
                maxTokens = 20,
                temperature = 0.0,
            ),
        )
        val text = result.message.plainText()
        println("Live response: $text (usage=${result.usage})")
        assertTrue(text.isNotBlank(), "Expected non-empty response, got: '$text'")
        assertTrue(result.usage.inputTokens > 0, "Expected non-zero input token count")
        assertTrue(result.usage.outputTokens > 0, "Expected non-zero output token count")
    }

    @Test
    fun streamingProducesDeltasAndTerminates() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val key = apiKey ?: run {
            println("SKIP OpenAiLiveTest.streamingProducesDeltasAndTerminates: OPENAI_API_KEY not set")
            return@runTest
        }
        val access: LlmAccess = LlmAccess.Settings("openai://gpt-4o-mini?apiKey=$key")
            .invoke("live-test", context)
        val events = access.stream(
            model = LlmModelId("gpt-4o-mini"),
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.User(
                        listOf(LlmPart.Text("Count from 1 to 3, one number per line.")),
                    ),
                ),
                maxTokens = 30,
                temperature = 0.0,
            ),
        ).toList()
        assertTrue(events.any { it is LlmStreamEvent.TextDelta }, "Expected at least one TextDelta")
        assertTrue(events.last() is LlmStreamEvent.Finished, "Stream must end with Finished")
    }
}
