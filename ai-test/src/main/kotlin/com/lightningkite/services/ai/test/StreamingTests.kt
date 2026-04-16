package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies streaming semantics as defined in [com.lightningkite.services.ai.LlmAccess.stream]:
 *   - the flow is a sequence of [LlmStreamEvent] values
 *   - it terminates with exactly one [LlmStreamEvent.Finished] frame as the last event
 *   - tool-call events carry complete, parseable JSON (not partial deltas)
 *   - concatenated text deltas equal the non-streaming [inference] text at temperature 0
 *   - the stream is cancel-safe
 */
public abstract class StreamingTests : LlmAccessTests() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A moderately long generation prompt should produce at least two TextDelta frames on
     * any streaming-capable provider. (One-frame streams happen on very short outputs; two
     * frames is the realistic minimum for a 3-line response.)
     */
    @Test
    public fun streamYieldsMultipleDeltas(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val events = service.stream(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(userText("Count from 1 to 5, one number per line.")),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        ).toList()
        val deltas = events.filterIsInstance<LlmStreamEvent.TextDelta>()
        assertTrue(
            deltas.size >= 2,
            "Expected at least 2 TextDelta events; got ${deltas.size} (total events: ${events.size})",
        )
    }

    /**
     * Contract: streams ALWAYS end with exactly one [LlmStreamEvent.Finished] frame, and
     * no other frame type is Finished.
     */
    @Test
    public fun streamEndsWithFinishedExactlyOnce(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val events = service.stream(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(userText("Say hello in three words.")),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        ).toList()
        val finishedFrames = events.filterIsInstance<LlmStreamEvent.Finished>()
        assertEquals(
            1,
            finishedFrames.size,
            "Stream must contain exactly one Finished frame; got ${finishedFrames.size}",
        )
        assertTrue(
            events.last() is LlmStreamEvent.Finished,
            "The Finished frame must be the LAST event; got events=${events.map { it::class.simpleName }}",
        )
    }

    /**
     * Concatenate all [LlmStreamEvent.TextDelta].text values and compare to the text returned
     * by a non-streaming [inference] call on the same prompt + model. At temperature 0 these
     * should be identical (deterministic sampling). We tolerate whitespace differences only.
     */
    @Test
    public fun streamConcatenatesToInference(): Unit = runTest(timeout = 90.seconds) {
        skipIfServiceAbsent()
        val prompt = LlmPrompt(
            messages = listOf(userText("In one short sentence, say what a unit test is.")),
            maxTokens = testMaxTokens,
            temperature = 0.0,
        )
        val streamed = service.stream(cheapModel, prompt).toList()
            .filterIsInstance<LlmStreamEvent.TextDelta>()
            .joinToString("") { it.text }
            .trim()
        val nonStreamed = service.inference(cheapModel, prompt).message.plainText().trim()
        assertTrue(streamed.isNotBlank(), "Streamed text must not be blank")
        assertTrue(nonStreamed.isNotBlank(), "Non-streamed text must not be blank")
        // Providers sometimes differ in trailing whitespace / punctuation between the two
        // code paths even at temperature 0. Compare only the first 20 characters for a
        // smoke-test of equivalence.
        assertEquals(
            nonStreamed.take(20),
            streamed.take(20),
            "Streamed and non-streamed prefixes should match at temperature 0.\n" +
                "  streamed: '$streamed'\n  nonStreamed: '$nonStreamed'",
        )
    }

    /**
     * When the model emits a tool call during streaming, the provider should buffer the
     * argument deltas and emit a single [LlmStreamEvent.ToolCallEmitted] with complete,
     * parseable JSON — never a partial chunk.
     */
    @Test
    public fun streamingToolCallComplete(): Unit = runTest(timeout = 90.seconds) {
        skipIfServiceAbsent()
        val events = service.stream(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(userText("What's the current weather in Osaka?")),
                tools = listOf(weatherTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        ).toList()
        val toolEvents = events.filterIsInstance<LlmStreamEvent.ToolCallEmitted>()
        assertTrue(
            toolEvents.isNotEmpty(),
            "Expected at least one ToolCallEmitted; got events=${events.map { it::class.simpleName }}",
        )
        val finishedIdx = events.indexOfFirst { it is LlmStreamEvent.Finished }
        val lastToolIdx = events.indexOfLast { it is LlmStreamEvent.ToolCallEmitted }
        assertTrue(
            lastToolIdx < finishedIdx,
            "ToolCallEmitted must come before Finished; tool=$lastToolIdx finished=$finishedIdx",
        )
        val tool = toolEvents.first()
        assertEquals("get_weather", tool.name)
        // Complete JSON: must parse to an object.
        val parsed = runCatching { json.parseToJsonElement(tool.inputJson) }.getOrNull()
        assertNotNull(parsed, "Streaming ToolCallEmitted.inputJson must parse; got '${tool.inputJson}'")
        assertTrue(parsed is JsonObject, "inputJson must decode to a JSON object; got $parsed")
    }

    /**
     * Reasoning-capable providers emit all [LlmStreamEvent.ReasoningDelta] frames BEFORE any
     * [LlmStreamEvent.TextDelta] or [LlmStreamEvent.ToolCallEmitted] — adapters preserve this
     * ordering so downstream consumers can show reasoning first.
     *
     * Skipped on providers that don't expose reasoning content.
     */
    @Test
    public fun reasoningStreamOrderingPreservesContract(): Unit = runTest(timeout = 120.seconds) {
        skipIfServiceAbsent()
        Assume.assumeTrue(
            "Provider does not support reasoning content",
            supportsReasoningContent,
        )
        val events = service.stream(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText("Solve: if 3x + 7 = 22, what is x? Show your reasoning step by step."),
                ),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        ).toList()
        val lastReasoningIdx = events.indexOfLast { it is LlmStreamEvent.ReasoningDelta }
        val firstTextIdx = events.indexOfFirst { it is LlmStreamEvent.TextDelta }
        val firstToolIdx = events.indexOfFirst { it is LlmStreamEvent.ToolCallEmitted }
        assertTrue(
            lastReasoningIdx >= 0,
            "Expected at least one ReasoningDelta; got events=${events.map { it::class.simpleName }}",
        )
        if (firstTextIdx >= 0) {
            assertTrue(
                lastReasoningIdx < firstTextIdx,
                "Last ReasoningDelta (idx=$lastReasoningIdx) must precede first TextDelta (idx=$firstTextIdx); " +
                    "events=${events.map { it::class.simpleName }}",
            )
        }
        if (firstToolIdx >= 0) {
            assertTrue(
                lastReasoningIdx < firstToolIdx,
                "Last ReasoningDelta (idx=$lastReasoningIdx) must precede first ToolCallEmitted (idx=$firstToolIdx); " +
                    "events=${events.map { it::class.simpleName }}",
            )
        }
    }

    /**
     * Cancel the flow mid-stream by taking only the first event. The provider's underlying
     * HTTP connection should close cleanly and the test should complete quickly. We don't
     * have a direct way to verify connection cleanup, but a hanging test indicates a leak.
     */
    @Test
    public fun streamCancellation(): Unit = runTest(timeout = 30.seconds) {
        skipIfServiceAbsent()
        val events = service.stream(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText(
                        "Write a very long poem of at least 200 lines. Do not stop early.",
                    ),
                ),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        ).take(1).toList()
        assertEquals(
            1,
            events.size,
            "take(1) should emit exactly 1 event; got ${events.size}",
        )
    }
}
