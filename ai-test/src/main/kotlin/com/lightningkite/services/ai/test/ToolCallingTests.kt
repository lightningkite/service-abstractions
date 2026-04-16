package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.firstToolCall
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import com.lightningkite.services.ai.toolCalls
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies tool-calling semantics: tool definitions reach the model, tool_use blocks come
 * back correctly, tool_use_id survives a round trip, and the model can continue a
 * conversation after receiving a tool_result.
 *
 * Uses [weatherTool], [currentTimeTool], and [nestedWeatherTool] from [TestFixtures] so the
 * surface under test is consistent across providers.
 */
public abstract class ToolCallingTests : LlmAccessTests() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Ask "What's the weather in Tokyo?" with the weather tool available. The model should:
     *   - stop with [LlmStopReason.ToolUse]
     *   - emit a [LlmPart.ToolCall] with name="get_weather" and inputJson.city ≈ "Tokyo".
     */
    @Test
    public fun toolCallEmitted(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(userText("What's the current weather in Tokyo?")),
                tools = listOf(weatherTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        assertEquals(
            LlmStopReason.ToolUse,
            result.stopReason,
            "Expected stopReason=ToolUse when the model calls a tool; got ${result.stopReason}",
        )
        val call = result.message.firstToolCall()
        assertNotNull(call, "Expected at least one ToolCall in message; got: ${result.message.parts}")
        assertEquals("get_weather", call.name, "Tool call should use the registered name")
        val input = json.parseToJsonElement(call.inputJson) as? JsonObject
        assertNotNull(input, "inputJson must parse to a JSON object; got '${call.inputJson}'")
        val city = (input["city"])?.jsonPrimitive?.content
        assertNotNull(city, "tool args should contain 'city'")
        assertTrue(
            city.contains("Tokyo", ignoreCase = true),
            "Expected city ~ 'Tokyo'; got '$city'",
        )
    }

    /**
     * Verifies the tool_use id surfaces non-blank. Callers echo this back as
     * [LlmMessage.ToolResult.toolCallId] on the next turn; a blank id would break that
     * linkage.
     */
    @Test
    public fun toolCallIdPreserved(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(userText("What's the weather in Paris right now?")),
                tools = listOf(weatherTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val call = result.message.firstToolCall()
        assertNotNull(call, "Expected a ToolCall; got: ${result.message.parts}")
        assertTrue(call.id.isNotBlank(), "Tool call id must not be blank")
        // Every call in the turn must carry its own id for parallel-call providers.
        val ids = result.message.toolCalls().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Tool call ids must be unique; got $ids")
    }

    /**
     * Builds a two-turn conversation: User ask → Agent [LlmPart.ToolCall] →
     * Tool [LlmMessage.ToolResult] → Agent [LlmPart.Text]. Verifies the provider accepts
     * a tool_result referencing the id from the previous turn and produces a final
     * natural-language response that mentions the tool output.
     */
    @Test
    public fun toolResultRoundTrip(): Unit = runTest(timeout = 90.seconds) {
        skipIfServiceAbsent()
        // Turn 1: provoke a tool call.
        val firstResult = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(userText("What's the current weather in Paris?")),
                tools = listOf(weatherTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val call = firstResult.message.firstToolCall()
        assertNotNull(call, "Expected a tool call on turn 1; got: ${firstResult.message.parts}")
        // Turn 2: feed the result back using the same id.
        val toolResultText = "The current weather in Paris is 17C and cloudy."
        val secondResult = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText("What's the current weather in Paris?"),
                    firstResult.message, // Agent message containing ToolCall
                    LlmMessage.ToolResult(
                        toolCallId = call.id,
                        parts = listOf(LlmPart.Text(toolResultText)),
                    ),
                ),
                tools = listOf(weatherTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val finalText = secondResult.message.plainText()
        assertTrue(finalText.isNotBlank(), "Expected non-empty follow-up; got: '$finalText'")
        assertEquals(
            LlmStopReason.EndTurn,
            secondResult.stopReason,
            "After consuming a tool result, the model should finish with EndTurn; got ${secondResult.stopReason}",
        )
        // A well-behaved model surfaces the tool result payload (temperature or condition).
        val lower = finalText.lowercase()
        assertTrue(
            "17" in lower || "cloudy" in lower || "paris" in lower,
            "Expected the model to reference tool-result data; got: '$finalText'",
        )
    }

    /**
     * Explicit "give me two pieces of info, requiring both tools" prompt. Providers that
     * support parallel tool calls emit two in a single turn; providers that don't emit
     * one-at-a-time. We accept either.
     */
    @Test
    public fun parallelToolCalls(): Unit = runTest(timeout = 90.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText(
                        "Please get me BOTH the current weather in Tokyo AND the current time in " +
                            "UTC. Call both tools.",
                    ),
                ),
                tools = listOf(weatherTool, currentTimeTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val calls = result.message.toolCalls()
        assertTrue(calls.isNotEmpty(), "Expected at least one tool call; got: ${result.message.parts}")
        assertEquals(
            LlmStopReason.ToolUse,
            result.stopReason,
            "Expected stopReason=ToolUse; got ${result.stopReason}",
        )
        if (supportsParallelToolCalls && calls.size >= 2) {
            // When the provider actually emits parallel calls, enforce distinct ids and names.
            val ids = calls.map { it.id }.toSet()
            assertEquals(
                calls.size,
                ids.size,
                "Parallel tool calls must have distinct ids; got $ids for ${calls.map { it.name }}",
            )
            val names = calls.map { it.name }.toSet()
            assertTrue(
                "get_weather" in names && "get_current_time" in names,
                "Expected both tool names; got $names",
            )
        }
    }

    /**
     * Uses a nested-object tool arg to verify the schema generator produces something the
     * provider accepts, and the model can emit valid JSON for it.
     */
    @Test
    public fun nestedObjectToolArgs(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText(
                        "Call get_weather_nested to check the weather in Berlin, and include " +
                            "the note 'priority=high' in the notes list.",
                    ),
                ),
                tools = listOf(nestedWeatherTool),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        assertEquals(
            LlmStopReason.ToolUse,
            result.stopReason,
            "Expected stopReason=ToolUse; got ${result.stopReason}",
        )
        val call = result.message.firstToolCall()
        assertNotNull(call, "Expected a ToolCall; got ${result.message.parts}")
        assertEquals("get_weather_nested", call.name)
        // The inputJson should parse cleanly; we don't assert exact content because models
        // legitimately reinterpret "Berlin" / "priority=high" in different ways.
        val parsed = runCatching { json.parseToJsonElement(call.inputJson) }.getOrNull()
        assertNotNull(parsed, "inputJson must parse as JSON; got '${call.inputJson}'")
        assertTrue(parsed is JsonObject, "inputJson must parse to a JSON object; got $parsed")
    }
}
