package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.toolCalls
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies [LlmToolChoice] semantics.
 *
 * Providers may support tool choice to different degrees; the subclass's
 * [supportsToolChoiceNone] and [supportsToolChoiceForced] flags cause the matching tests
 * to skip (via [Assume.assumeTrue]) when a provider can't honor a given choice mode.
 */
public abstract class ToolChoiceTests : LlmAccessTests() {

    /**
     * With [LlmToolChoice.Auto] and a plain text prompt unrelated to the available tools,
     * the model should NOT invoke the tool — stopReason should be [LlmStopReason.EndTurn].
     */
    @Test
    public fun autoWithUnrelatedPromptDoesNotCall(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText("In one sentence, what is the capital of Japan?"),
                ),
                tools = listOf(weatherTool),
                toolChoice = LlmToolChoice.Auto,
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        assertEquals(
            LlmStopReason.EndTurn,
            result.stopReason,
            "Auto with an unrelated prompt should not trigger a tool call; got ${result.stopReason}",
        )
        assertTrue(
            result.message.toolCalls().isEmpty(),
            "No tool calls expected; got ${result.message.toolCalls().map { it.name }}",
        )
    }

    /**
     * [LlmToolChoice.Required] forces the model to invoke at least one tool, even for a
     * prompt that would otherwise be answered in plain text. Providers that don't reliably
     * honor this mode should set [supportsToolChoiceForced]=false to skip.
     */
    @Test
    public fun requiredForcesCall(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        Assume.assumeTrue(
            "Provider does not support tool_choice=required",
            supportsToolChoiceForced,
        )
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    // Ambiguous prompt — the model could answer in text, but Required must
                    // force a call.
                    userText("Tell me about the weather today."),
                ),
                tools = listOf(weatherTool),
                toolChoice = LlmToolChoice.Required,
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        assertEquals(
            LlmStopReason.ToolUse,
            result.stopReason,
            "Required tool choice must force a tool call; got ${result.stopReason}",
        )
        assertTrue(
            result.message.toolCalls().isNotEmpty(),
            "Expected at least one tool call when Required; got ${result.message.parts}",
        )
    }

    /**
     * [LlmToolChoice.Specific] forces the model to invoke exactly the named tool, even when
     * other tools are available and the prompt would suggest them. Providers that don't
     * fully support this mode should set [supportsToolChoiceForced]=false to skip.
     */
    @Test
    public fun specificForcesNamedTool(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        Assume.assumeTrue(
            "Provider does not support tool_choice=<specific>",
            supportsToolChoiceForced,
        )
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    // The user is asking about time, but we're forcing the weather tool.
                    userText("What's the time right now?"),
                ),
                tools = listOf(weatherTool, currentTimeTool),
                toolChoice = LlmToolChoice.Specific("get_weather"),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        assertEquals(
            LlmStopReason.ToolUse,
            result.stopReason,
            "Specific tool choice should force a tool call; got ${result.stopReason}",
        )
        val names = result.message.toolCalls().map { it.name }
        assertTrue(
            names.all { it == "get_weather" },
            "Specific('get_weather') must call only that tool; got $names",
        )
        assertTrue(names.isNotEmpty(), "Expected at least one tool call")
    }

    /**
     * [LlmToolChoice.None] forbids new tool calls on this turn. The model must finish with
     * [LlmStopReason.EndTurn] and produce no tool call parts.
     *
     * Providers that cannot reliably enforce None (notably Ollama and some local runtimes)
     * should set [supportsToolChoiceNone]=false to skip.
     */
    @Test
    public fun noneForbidsToolCall(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        Assume.assumeTrue(
            "Provider does not support tool_choice=none",
            supportsToolChoiceNone,
        )
        val result = service.inference(
            model = cheapModel,
            prompt = LlmPrompt(
                messages = listOf(
                    userText("What's the current weather in Seattle?"),
                ),
                tools = listOf(weatherTool),
                toolChoice = LlmToolChoice.None,
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        assertEquals(
            LlmStopReason.EndTurn,
            result.stopReason,
            "None tool choice must prevent tool calls; got ${result.stopReason}",
        )
        assertTrue(
            result.message.toolCalls().isEmpty(),
            "No tool calls expected under None; got ${result.message.toolCalls().map { it.name }}",
        )
    }
}
