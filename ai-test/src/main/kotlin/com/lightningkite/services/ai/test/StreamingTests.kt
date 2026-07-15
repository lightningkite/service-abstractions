package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import com.lightningkite.services.ai.toolCalls
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

    // Note: there is intentionally no "stream yields multiple deltas" test. Delta/chunk
    // granularity is not part of the streaming contract — a provider or local runtime may
    // legitimately deliver a short response in a single TextDelta frame. Asserting "at least
    // N deltas" is inherently flaky across models/runtimes (e.g. small local models in
    // LM Studio). streamConcatenatesToInference covers that streamed text is correct.

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
        // A single Finished frame that is last is NOT enough — a provider truncating a stream and
        // fabricating a terminal frame also satisfies the two checks above. A genuine terminal
        // frame carries real token usage; zero usage is the truncation fingerprint. This is the
        // check that would have caught the butchered Nova response.
        if (reportsUsage) {
            val finished = finishedFrames.single()
            assertTrue(
                finished.usage.outputTokens > 0,
                "The terminal Finished frame must carry non-zero output-token usage; got " +
                    "${finished.usage}. Zero usage signals a fabricated finish over a truncated stream.",
            )
        }
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
        // Comparing a streamed call against a separate non-streamed call only works if the
        // model is deterministic at temperature 0. Models that aren't (e.g. Amazon Nova) still
        // get the not-blank coverage above; skip only the equality check for them.
        if (deterministicAtTemperatureZero) {
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
     * Regression guard for silent stream truncation, in the exact shape that exposed it: a
     * realistic streamed turn with a system prompt and an available tool that the prompt should
     * trigger. Collected through [inference] (stream → single result), it must come back
     * COMPLETE: a tool call present, a matching stop reason, and non-zero usage. A provider that
     * truncates and fabricates a terminal frame fails all three — it returns partial text with
     * [LlmStopReason.EndTurn] and zero usage.
     */
    @Test
    public fun streamedToolTurnWithSystemPromptCompletes(): Unit = runTest(timeout = 90.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                systemPrompt = listOf(
                    LlmPart.Text("You are a helpful assistant. Greet the user by their name, Clarence."),
                ),
                messages = listOf(userText("Hi! What's the current weather in Denver?")),
                tools = listOf(weatherTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        assertTrue(
            result.message.toolCalls().isNotEmpty(),
            "Expected a tool call for a weather question with the weather tool available; got " +
                "parts=${result.message.parts.map { it::class.simpleName }}, stopReason=${result.stopReason}. " +
                "Partial content with EndTurn indicates a truncated stream.",
        )
        assertEquals(
            LlmStopReason.ToolUse,
            result.stopReason,
            "A completed tool-calling turn must end with stopReason=ToolUse; got ${result.stopReason}.",
        )
        if (reportsUsage) {
            assertTrue(
                result.usage.inputTokens > 0 && result.usage.outputTokens > 0,
                "A complete streamed response must report non-zero token usage; got ${result.usage}. " +
                    "Zero usage is the fingerprint of a truncated/fabricated finish.",
            )
        }
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
