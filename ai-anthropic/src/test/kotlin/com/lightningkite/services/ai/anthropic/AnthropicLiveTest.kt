package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live-API smoke test. Skipped (printed, not failed) when no key is available — keeps
 * CI green on forks that don't have the secret while still catching regressions when a
 * developer runs tests locally with a key configured.
 *
 * Credentials are read from the `ANTHROPIC_API_KEY` env var, falling back to a
 * `local.properties` file at any ancestor directory (same pattern as the other live tests
 * in this repo).
 */
class AnthropicLiveTest {

    @Test
    fun sayHelloWithHaiku() = runTest {
        val key = apiKey
        if (key == null) {
            println("Skipping live test - ANTHROPIC_API_KEY not set")
            return@runTest
        }

        // Force the anthropic:// URL scheme to register before constructing Settings.
        AnthropicLlmAccess
        val context = TestSettingContext()
        val settings = LlmAccess.Settings("anthropic://claude-haiku-4-5?apiKey=$key")
        val llm = settings("test", context)

        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.User(listOf(LlmPart.Text("Say the word hello and nothing else."))),
            ),
            maxTokens = 50,
        )

        val result = llm.inference(com.lightningkite.services.ai.LlmModelId("claude-haiku-4-5"), prompt)
        val text = result.message.plainText()
        assertTrue(text.isNotBlank(), "Expected non-empty response, got: ${result.message.parts}")
        assertTrue(result.usage.inputTokens > 0, "Expected non-zero input tokens")
        assertTrue(result.usage.outputTokens > 0, "Expected non-zero output tokens")
        println("Live response: $text (usage=${result.usage}, stopReason=${result.stopReason})")
    }

    private val apiKey: String? by lazy {
        System.getenv("ANTHROPIC_API_KEY") ?: loadFromLocalProperties("ANTHROPIC_API_KEY")
    }

    private fun loadFromLocalProperties(key: String): String? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val file = File(dir, "local.properties")
            if (file.exists()) {
                val props = Properties()
                file.inputStream().use { props.load(it) }
                props.getProperty(key)?.let { return it }
            }
            dir = dir.parentFile
        }
        return null
    }
}
