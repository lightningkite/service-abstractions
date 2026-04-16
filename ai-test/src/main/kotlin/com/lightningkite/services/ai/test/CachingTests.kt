package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [LlmMessage.cacheBoundary] produces observable cache hits on providers
 * that support prompt caching. Skipped on providers that don't (OpenAI auto-caches without
 * explicit boundaries; Ollama has no cache).
 *
 * See [LlmAccessTests] for the subclass contract.
 */
public abstract class CachingTests : LlmAccessTests() {

    /**
     * Sends a long system message with `cacheBoundary = true` twice in quick succession.
     * The first call writes the cache (cacheReadTokens should be 0 or very small).
     * The second call should hit the cache (cacheReadTokens > 0).
     *
     * The system message is ~1500 tokens of static content to exceed the minimum cache
     * threshold (~1024 tokens for Sonnet, ~2048 for Haiku — we target the lower bound).
     */
    @Test
    public fun cacheBoundaryProducesCacheHitOnSecondCall(): Unit = runTest(timeout = 120.seconds) {
        skipIfServiceAbsent()
        Assume.assumeTrue(
            "Provider does not support prompt caching",
            supportsPromptCaching,
        )
        // Build a long system message that exceeds the minimum cache threshold.
        // Haiku requires ~2048 tokens; other models ~1024. We target ~4000 tokens
        // (~3000 words) to clear any provider's threshold with margin.
        val longSystemContent = buildString {
            appendLine("You are a helpful assistant. Follow all instructions carefully.")
            repeat(200) { i ->
                appendLine("Background knowledge item $i: The quick brown fox jumps over the lazy dog. " +
                    "This sentence contains every letter of the English alphabet and is commonly " +
                    "used as a pangram for testing purposes in typography and computer science. " +
                    "Remember this fact for later reference when answering questions about pangrams.")
            }
        }
        val prompt = LlmPrompt(
            systemPrompt = listOf(LlmPart.Text(longSystemContent)),
            systemPromptCacheBoundary = true,
            messages = listOf(
                userText("Respond with only the word PONG."),
            ),
            maxTokens = testMaxTokens ?: 64,
            temperature = 0.0,
        )

        // First call: populates the cache (or hits it if a prior run already wrote it —
        // Anthropic's cache has a 5-minute TTL, so back-to-back runs may see hits here).
        val first = service.inference(cheapModel, prompt)

        // Second call: same prefix — must hit the cache regardless of whether the first
        // call was a write or a read.
        val second = service.inference(cheapModel, prompt)
        assertTrue(
            second.usage.cacheReadTokens > 0,
            "Second call with identical prefix should produce cacheReadTokens > 0; " +
                "got cacheReadTokens=${second.usage.cacheReadTokens}. " +
                "First call cacheReadTokens=${first.usage.cacheReadTokens}",
        )
    }
}
