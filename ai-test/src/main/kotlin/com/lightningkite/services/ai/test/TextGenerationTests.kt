package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies basic single-turn text generation: plain prompts, system instructions, stop
 * sequences, and max-token truncation. Every provider that claims to implement
 * [com.lightningkite.services.ai.LlmAccess] should pass this class.
 *
 * See [LlmAccessTests] for the subclass contract.
 */
public abstract class TextGenerationTests : LlmAccessTests() {

    /**
     * Asks the model to respond with exactly "HELLO" and verifies the word appears.
     * Baseline sanity: network, auth, non-empty response, usage reporting all work.
     */
    @Test
    public fun simpleHello(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(userText("Respond with only the word HELLO and nothing else.")),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val text = result.message.plainText()
        assertTrue(
            text.uppercase().contains("HELLO"),
            "Expected response to contain HELLO; got: '$text'",
        )
        assertTrue(result.usage.inputTokens > 0, "Expected non-zero inputTokens; got ${result.usage}")
        assertTrue(result.usage.outputTokens > 0, "Expected non-zero outputTokens; got ${result.usage}")
    }

    /**
     * Sends a system instruction "Always respond in French" followed by a user prompt.
     * Asserts the response contains French-typical characters (e.g. accented vowels) or
     * common French words, using a lenient heuristic to tolerate model variance.
     */
    @Test
    public fun systemPromptObeyed(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                systemPrompt = systemPrompt("Always respond in French. Never use any other language."),
                messages = listOf(
                    userText("What is the capital of France? Answer in one short sentence."),
                ),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val text = result.message.plainText()
        // Paris is spelled the same in English and French, so we can't key on it.
        // Instead look for French markers: accented characters or common French words.
        val frenchMarkers = listOf(
            "é", "è", "ê", "à", "ç", "ô", "û",
            " est ", " la ", " le ", " les ", " de ", " capitale", " Paris est",
        )
        val lower = text.lowercase()
        assertTrue(
            frenchMarkers.any { it.lowercase() in lower },
            "Expected a French response; none of the markers matched. Got: '$text'",
        )
    }

    /**
     * Sets a stop sequence and asserts the provider reports [LlmStopReason.StopSequence]
     * when the model produces it. Skipped on providers that don't support stop sequences.
     *
     * We ask for repeated lines and place the stop sequence mid-stream so the model almost
     * certainly hits it before finishing naturally.
     */
    @Test
    public fun stopSequences(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        Assume.assumeTrue(
            "Provider does not support stop sequences",
            supportsStopSequences,
        )
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText(
                        "Output three lines, each containing a single number: '1', '2', 'HALT'. " +
                            "Output exactly those three lines and nothing else.",
                    ),
                ),
                stopSequences = listOf("HALT"),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val text = result.message.plainText()
        // Per contract, the returned text must not contain the stop token — providers strip it.
        assertTrue(
            "HALT" !in text,
            "Response should not contain the stop sequence 'HALT' (provider should truncate before). Got: '$text'",
        )
        assertEquals(
            LlmStopReason.StopSequence,
            result.stopReason,
            "Expected stopReason=StopSequence when the stop token fires; got ${result.stopReason}",
        )
    }

    /**
     * Caps output at 10 tokens and verifies the provider reports [LlmStopReason.MaxTokens]
     * and returns a truncated response (i.e. not a complete sentence).
     */
    @Test
    public fun maxTokensTruncation(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText("Write a long essay about the history of Rome, at least 500 words."),
                ),
                maxTokens = 10,
                temperature = 0.0,
            ),
        )
        assertEquals(
            LlmStopReason.MaxTokens,
            result.stopReason,
            "Expected stopReason=MaxTokens when maxTokens=10; got ${result.stopReason}",
        )
        // Output tokens should be at-or-near the cap (providers may count slightly differently).
        assertTrue(
            result.usage.outputTokens <= 25,
            "Output tokens should be near the 10 cap; got ${result.usage.outputTokens}",
        )
    }

    /**
     * Reasoning-capable providers (Claude extended thinking, OpenAI o-series, Gemma reasoning
     * variants, etc.) emit chain-of-thought as a separate [LlmPart.Reasoning] block that
     * precedes the final [LlmPart.Text] answer. This test asks the model to solve a small
     * algebra problem and verifies both blocks are present and non-empty.
     *
     * Skipped on providers that don't expose reasoning content.
     */
    @Test
    public fun reasoningContentReceivedWhenSupported(): Unit = runTest(timeout = 120.seconds) {
        skipIfServiceAbsent()
        Assume.assumeTrue(
            "Provider does not support reasoning content",
            supportsReasoningContent,
        )
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText("Solve: if 3x + 7 = 22, what is x? Show your reasoning step by step."),
                ),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val reasoningBlocks = result.message.parts.filterIsInstance<LlmPart.Reasoning>()
        val textBlocks = result.message.parts.filterIsInstance<LlmPart.Text>()
        assertTrue(
            reasoningBlocks.isNotEmpty(),
            "Expected at least one Reasoning block; got parts=${result.message.parts.map { it::class.simpleName }}",
        )
        assertTrue(
            textBlocks.isNotEmpty(),
            "Expected at least one Text block; got parts=${result.message.parts.map { it::class.simpleName }}",
        )
        assertTrue(
            reasoningBlocks.first().text.isNotBlank(),
            "Reasoning block text must be non-empty; got '${reasoningBlocks.first().text}'",
        )
    }

    /**
     * At temperature 0, two identical calls should produce the same text.
     *
     * Many providers honor this; a few (local Ollama quantizations, some cross-model routing
     * layers) do not. We tolerate minor whitespace differences but expect the content to be
     * the same sentence. Skip the test wholesale if the provider is known to not support
     * deterministic temperature.
     */
    @Test
    public fun temperatureDeterminism(): Unit = runTest(timeout = 120.seconds) {
        skipIfServiceAbsent()
        val prompt = LlmPrompt(
            messages = listOf(userText("In exactly one short sentence, define entropy.")),
            maxTokens = testMaxTokens,
            temperature = 0.0,
        )
        val a = service.inference(cheapModel, prompt).message.plainText().trim()
        val b = service.inference(cheapModel, prompt).message.plainText().trim()
        // Lenient: at temperature 0 expect the first ~20 characters to match, which catches
        // hard non-determinism (different sentence) without tripping on trailing whitespace.
        val prefixA = a.take(20)
        val prefixB = b.take(20)
        assertTrue(
            prefixA == prefixB || a == b,
            "Temperature 0 should produce similar responses. Got:\n  A: '$a'\n  B: '$b'",
        )
    }
}
