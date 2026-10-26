package com.lightningkite.services.ai

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/** Replays [events] for every call and records the last prompt it was given. */
internal class ScriptedLlmAccess(private val events: List<LlmStreamEvent>) : LlmAccess {
    override val name: String = "scripted"
    override val context: SettingContext get() = error("unused")
    override suspend fun getModels(): List<LlmModelInfo> = emptyList()
    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
    var lastPrompt: LlmPrompt? = null
    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> {
        lastPrompt = prompt
        return flowOf(*events.toTypedArray())
    }
}

/** Fails with each of [failures] in turn, then streams [events]; counts its calls. */
internal class FlakyLlmAccess(private val failures: List<LlmException>, private val events: List<LlmStreamEvent>) : LlmAccess {
    override val name: String = "flaky"
    override val context: SettingContext get() = error("unused")
    override suspend fun getModels(): List<LlmModelInfo> = emptyList()
    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
    var calls = 0
    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> {
        failures.getOrNull(calls++)?.let { throw it }
        return flowOf(*events.toTypedArray())
    }
}

class InferenceTest {
    private val finished = LlmStreamEvent.Finished(LlmStopReason.ToolUse, LlmUsage(1, 1))

    @Test
    fun keepsOnePartPerSignedReasoningBlockInStreamOrder() = runTest {
        val llm = ScriptedLlmAccess(listOf(
            LlmStreamEvent.ReasoningDelta("Check the "),
            LlmStreamEvent.ReasoningDelta("weather."),
            LlmStreamEvent.ReasoningDelta("", signature = "sig-1"),
            LlmStreamEvent.ReasoningDelta("", redactedData = "encrypted"),
            LlmStreamEvent.TextDelta("Looking it up."),
            LlmStreamEvent.ToolCallEmitted("call-1", "weather", "{}"),
            LlmStreamEvent.ReasoningDelta("", signature = "sig-2"),
            LlmStreamEvent.ToolCallEmitted("call-2", "weather", "{}"),
            finished,
        ))
        val parts = llm.inference(LlmModelId("m"), LlmPrompt(messages = emptyList())).message.parts
        assertEquals(
            listOf(
                LlmPart.Reasoning("Check the weather.", signature = "sig-1"),
                LlmPart.Reasoning("", redactedData = "encrypted"),
                LlmPart.Text("Looking it up."),
                LlmPart.ToolCall(LlmToolCall("call-1", "weather", "{}")),
                LlmPart.Reasoning("", signature = "sig-2"),
                LlmPart.ToolCall(LlmToolCall("call-2", "weather", "{}")),
            ),
            parts,
        )
    }

    @Test
    fun unsignedReasoningEndsWhenTextStarts() = runTest {
        val llm = ScriptedLlmAccess(listOf(
            LlmStreamEvent.ReasoningDelta("Hmm"),
            LlmStreamEvent.ReasoningDelta("."),
            LlmStreamEvent.TextDelta("Hi"),
            LlmStreamEvent.TextDelta("!"),
            finished,
        ))
        val parts = llm.inference(LlmModelId("m"), LlmPrompt(messages = emptyList())).message.parts
        assertEquals(listOf(LlmPart.Reasoning("Hmm."), LlmPart.Text("Hi!")), parts)
    }

    @Test
    fun redactedReasoningCannotCarryText() {
        assertFailsWith<IllegalArgumentException> { LlmPart.Reasoning("text", redactedData = "encrypted") }
    }

    @Test
    fun sanitizerKeepsSignaturesAndEndsTheBlockWithIt() = runTest {
        val llm = ScriptedLlmAccess(listOf(
            LlmStreamEvent.ReasoningDelta("The SSN <<REDACTED_"),
            LlmStreamEvent.ReasoningDelta("00>> is on file."),
            LlmStreamEvent.ReasoningDelta("", signature = "sig-1"),
            finished,
        ))
        val safe = llm.sanitized(CommonPatterns.SSN)
        val prompt = LlmPrompt(messages = listOf(
            userMessage("My SSN is 123-45-6789"),
            LlmMessage.Agent(listOf(LlmPart.Reasoning("Earlier 123-45-6789", signature = "old-sig"))),
            userMessage("Again?"),
        ))
        val events = safe.stream(LlmModelId("m"), prompt).toList()
        assertEquals(
            LlmPart.Reasoning("Earlier <<REDACTED_00>>", signature = "old-sig"),
            (llm.lastPrompt!!.messages[1] as LlmMessage.Agent).parts.single(),
        )
        val reasoning = events.filterIsInstance<LlmStreamEvent.ReasoningDelta>()
        assertEquals("The SSN 123-45-6789 is on file.", reasoning.joinToString("") { it.text })
        assertEquals("sig-1", reasoning.last().signature)
    }

    @Test
    fun transientFailuresAreRetriedWithBackoff() = runTest {
        val reply = listOf(LlmStreamEvent.TextDelta("ok"), finished)
        val llm = FlakyLlmAccess(listOf(LlmException.ServerError("busy"), LlmException.Transport("reset"), LlmException.RateLimit("slow")), reply)
        val result = llm.inference(LlmModelId("m"), LlmPrompt(messages = listOf()))
        assertEquals("ok", result.message.plainText())
        assertEquals(4, llm.calls)
        // 1s + 2s + 4s of backoff, on the test's virtual clock.
        assertEquals(7000, testScheduler.currentTime)
    }

    @Test
    fun aRateLimitWaitsAsLongAsTheProviderSays() = runTest {
        val llm = FlakyLlmAccess(listOf(LlmException.RateLimit("slow", retryAfter = 30.seconds)), listOf(finished))
        llm.inference(LlmModelId("m"), LlmPrompt(messages = listOf()))
        assertEquals(30_000, testScheduler.currentTime)
    }

    @Test
    fun theLastTransientFailureAndAnyOtherFailureAreThrown() = runTest {
        val busy = FlakyLlmAccess(List(INFERENCE_ATTEMPTS) { LlmException.ServerError("busy $it") }, listOf(finished))
        val error = assertFailsWith<LlmException.ServerError> { busy.inference(LlmModelId("m"), LlmPrompt(messages = listOf())) }
        assertEquals("busy ${INFERENCE_ATTEMPTS - 1}", error.message)

        val invalid = FlakyLlmAccess(listOf(LlmException.InvalidRequest("bad")), listOf(finished))
        assertFailsWith<LlmException.InvalidRequest> { invalid.inference(LlmModelId("m"), LlmPrompt(messages = listOf())) }
        assertEquals(1, invalid.calls)
    }
}
