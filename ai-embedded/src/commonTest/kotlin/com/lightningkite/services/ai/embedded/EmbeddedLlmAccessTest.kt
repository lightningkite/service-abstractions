package com.lightningkite.services.ai.embedded

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddedLlmAccessTest {
    /** Emits [reasoning] chunks, then [chunks] of text. */
    private class ScriptedEngine(
        val chunks: List<String>,
        val reasoning: List<String> = emptyList(),
        val thinks: Boolean = false,
    ) : ChatEngine {
        var receivedSystemPrompt: String? = null
        var receivedTurns: List<ChatTurn> = emptyList()
        var receivedEnableThinking: Boolean? = null
        var chunksSent = 0
        override val loadProgress: StateFlow<Float?> = MutableStateFlow(null)
        override suspend fun loadModel() {}
        override suspend fun unloadModel() {}
        override fun isModelLoaded(): Boolean = true
        override suspend fun supportsReasoning(): Boolean = thinks
        override suspend fun generate(
            systemPrompt: String?,
            turns: List<ChatTurn>,
            maxTokens: Int?,
            enableThinking: Boolean?,
            onOutput: (LlmStreamEvent) -> Boolean,
        ): GenerationResult {
            receivedSystemPrompt = systemPrompt
            receivedTurns = turns
            receivedEnableThinking = enableThinking
            val outputs = reasoning.map { LlmStreamEvent.ReasoningDelta(it) } + chunks.map { LlmStreamEvent.TextDelta(it) }
            for (output in outputs) {
                chunksSent++
                if (!onOutput(output)) break
            }
            return GenerationResult(inputTokens = 7, outputTokens = chunksSent, hitMaxTokens = maxTokens == chunksSent)
        }
    }

    private fun access(engine: ChatEngine) = EmbeddedLlmAccess(
        name = "test",
        context = TestSettingContext(),
        config = EmbeddedEngineConfig(modelName = "test", modelPath = "unused"),
        engine = engine,
    )

    private val model = LlmModelId(id = "test", access = "test")

    private suspend fun EmbeddedLlmAccess.run(prompt: LlmPrompt): Pair<String, LlmStreamEvent.Finished> {
        val events = stream(model, prompt).toList()
        val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
        return text to events.last() as LlmStreamEvent.Finished
    }

    @Test
    fun passesSystemPromptAndHistoryAsTurns() = runTest {
        val engine = ScriptedEngine(listOf("Hi"))
        access(engine).run(
            LlmPrompt(
                systemPrompt = systemPrompt("Be brief."),
                messages = listOf(
                    userMessage("Hello"),
                    LlmMessage.Agent(listOf(LlmPart.Reasoning("thinking"), LlmPart.Text("Hey"))),
                    userMessage("How are you?"),
                ),
            )
        )
        assertEquals("Be brief.", engine.receivedSystemPrompt)
        assertEquals(
            listOf(ChatTurn(true, "Hello"), ChatTurn(false, "Hey"), ChatTurn(true, "How are you?")),
            engine.receivedTurns,
        )
    }

    @Test
    fun streamsTextAndReportsEngineUsage() = runTest {
        val (text, finished) = access(ScriptedEngine(listOf("Hel", "lo", "!"))).run(LlmPrompt(messages = listOf(userMessage("Hi"))))
        assertEquals("Hello!", text)
        assertEquals(LlmStopReason.EndTurn, finished.stopReason)
        assertEquals(LlmUsage(inputTokens = 7, outputTokens = 3), finished.usage)
    }

    @Test
    fun reportsMaxTokens() = runTest {
        val (_, finished) = access(ScriptedEngine(listOf("a", "b"))).run(LlmPrompt(messages = listOf(userMessage("Hi")), maxTokens = 2))
        assertEquals(LlmStopReason.MaxTokens, finished.stopReason)
    }

    @Test
    fun stopSequenceSplitAcrossChunksStopsGenerationAndIsNotEmitted() = runTest {
        val engine = ScriptedEngine(listOf("One. EN", "D more", " never sent"))
        val (text, finished) = access(engine).run(
            LlmPrompt(messages = listOf(userMessage("Hi")), stopSequences = listOf("END"))
        )
        assertEquals("One. ", text)
        assertEquals(LlmStopReason.StopSequence, finished.stopReason)
        assertEquals(2, engine.chunksSent)
    }

    @Test
    fun textResemblingAStopSequenceIsReleasedAtTheEnd() = runTest {
        val (text, finished) = access(ScriptedEngine(listOf("Almost E", "N"))).run(
            LlmPrompt(messages = listOf(userMessage("Hi")), stopSequences = listOf("END"))
        )
        assertEquals("Almost EN", text)
        assertEquals(LlmStopReason.EndTurn, finished.stopReason)
    }

    @Test
    fun reasoningPassesThroughWithoutTriggeringStopSequences() = runTest {
        val engine = ScriptedEngine(chunks = listOf("Answer"), reasoning = listOf("Thinking END of thought"))
        val events = access(engine).stream(
            model,
            LlmPrompt(messages = listOf(userMessage("Hi")), stopSequences = listOf("END")),
        ).toList()
        assertEquals(listOf(LlmStreamEvent.ReasoningDelta("Thinking END of thought")), events.filterIsInstance<LlmStreamEvent.ReasoningDelta>())
        assertEquals("Answer", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(LlmStopReason.EndTurn, (events.last() as LlmStreamEvent.Finished).stopReason)
    }

    @Test
    fun reasoningEffortTurnsThinkingOnOrOff() = runTest {
        suspend fun thinkingFor(effort: LlmReasoningEffort?): Boolean? {
            val engine = ScriptedEngine(listOf("Hi"))
            access(engine).stream(model, LlmPrompt(messages = listOf(userMessage("Hi")), reasoningEffort = effort)).toList()
            return engine.receivedEnableThinking
        }
        assertEquals(null, thinkingFor(null))
        assertEquals(false, thinkingFor(LlmReasoningEffort.Off))
        assertEquals(true, thinkingFor(LlmReasoningEffort.Low))
    }

    @Test
    fun rejectsToolsAndPromptsNotEndingWithTheUser() = runTest {
        val engine = ScriptedEngine(emptyList())
        assertFailsWith<LlmException.InvalidRequest> {
            access(engine).stream(model, LlmPrompt(messages = listOf(userMessage("Hi"), LlmMessage.Agent(listOf(LlmPart.Text("Hey"))))))
        }
        assertFailsWith<LlmException.InvalidRequest> {
            access(engine).stream(
                model,
                LlmPrompt(
                    messages = listOf(userMessage("Hi")),
                    tools = listOf(LlmToolDescriptor(name = "t", description = "", type = kotlinx.serialization.serializer<String>())),
                )
            )
        }
    }

    @Test
    fun modelInfoReportsWhetherTheModelReasons() = runTest {
        assertEquals(true, access(ScriptedEngine(emptyList(), thinks = true)).getModels().single().supportsReasoning)
        assertEquals(false, access(ScriptedEngine(emptyList(), thinks = false)).getModels().single().supportsReasoning)
    }

    @Test
    fun urlKeepsTheModelNameCase() {
        EmbeddedLlmSettings.ensureRegistered()
        val access = LlmAccess.Settings("embedded://Qwen3-0.6B?path=/models/q.litertlm&threads=2")
            .invoke("embedded", TestSettingContext()) as EmbeddedLlmAccess
        assertEquals("Qwen3-0.6B", access.config.modelName)
        assertEquals(2, access.config.threads)
    }

    @Test
    fun urlRejectsMissingPathAndBadNumbers() {
        EmbeddedLlmSettings.ensureRegistered()
        fun load(url: String) = LlmAccess.Settings(url).invoke("embedded", TestSettingContext())
        assertFailsWith<IllegalArgumentException> { load("embedded://m") }
        assertFailsWith<NumberFormatException> { load("embedded://m?path=p&threads=four") }
    }
}
