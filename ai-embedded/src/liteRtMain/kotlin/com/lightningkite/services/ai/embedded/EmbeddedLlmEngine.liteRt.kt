package com.lightningkite.services.ai.embedded

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LlmCapability
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ThinkingConfig
import com.lightningkite.services.ai.LlmStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android and desktop implementation using Google LiteRT-LM.
 * [EmbeddedEngineConfig.modelPath] is a `.litertlm` file on disk.
 */
internal class LiteRtEngine(
    private val config: EmbeddedEngineConfig,
) : ChatEngine {
    private var engine: Engine? = null

    override val loadProgress: StateFlow<Float?> = MutableStateFlow(null)

    @OptIn(ExperimentalApi::class)
    override suspend fun loadModel(): Unit = withContext(Dispatchers.IO) {
        val e = Engine(
            EngineConfig(
                modelPath = config.modelPath,
                backend = Backend.CPU(numOfThreads = config.threads),
                maxNumTokens = config.contextSize,
            )
        )
        // Token counts only exist in benchmark mode.  The flag is process-wide and only read by initialize(), so it's
        // set just around that, leaving engines the app creates itself unaffected.
        synchronized(ExperimentalFlags) {
            val before = ExperimentalFlags.enableBenchmark
            ExperimentalFlags.enableBenchmark = true
            try {
                e.initialize()
            } finally {
                ExperimentalFlags.enableBenchmark = before
            }
        }
        engine = e
    }

    override suspend fun supportsReasoning(): Boolean = withContext(Dispatchers.IO) {
        ModelInfo.from(config.modelPath).use { (it as? LlmCapability)?.supportsThinking() == true }
    }

    override suspend fun unloadModel() {
        engine?.close()
        engine = null
    }

    override fun isModelLoaded(): Boolean = engine?.isInitialized() == true

    @OptIn(ExperimentalApi::class)  // getBenchmarkInfo is the only source of real token counts
    override suspend fun generate(
        systemPrompt: String?,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        enableThinking: Boolean?,
        onOutput: (LlmStreamEvent) -> Boolean,
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded. Call connect() first.")
        val conversation = currentEngine.createConversation(
            ConversationConfig(
                systemInstruction = systemPrompt?.let { Contents.of(it) },
                initialMessages = turns.dropLast(1).map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
                maxOutputToken = maxTokens,
                thinkingConfig = enableThinking?.let { ThinkingConfig(it) },
            )
        )
        conversation.use {
            var stoppedEarly = false
            val done = CompletableDeferred<Unit>()
            conversation.sendMessageAsync(turns.last().text, object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (stoppedEarly) return
                    val outputs = listOfNotNull(
                        message.channels["thought"]?.let { LlmStreamEvent.ReasoningDelta(it) },
                        message.contents.toString().takeIf { it.isNotEmpty() }?.let { LlmStreamEvent.TextDelta(it) },
                    )
                    if (outputs.any { !onOutput(it) }) {
                        stoppedEarly = true
                        conversation.cancelProcess()
                    }
                }

                override fun onDone() {
                    done.complete(Unit)
                }

                override fun onError(throwable: Throwable) {
                    // Cancelling reports an error; that's the stop we asked for.
                    if (stoppedEarly) done.complete(Unit) else done.completeExceptionally(throwable)
                }
            })
            try {
                done.await()
            } catch (e: CancellationException) {
                // Closing the conversation while native generation is still running is unsafe, so wait it out.
                stoppedEarly = true
                conversation.cancelProcess()
                withContext(NonCancellable) { done.join() }
                throw e
            }
            val info = conversation.getBenchmarkInfo()
            GenerationResult(
                inputTokens = info.lastPrefillTokenCount,
                outputTokens = info.lastDecodeTokenCount,
                hitMaxTokens = !stoppedEarly && maxTokens != null && info.lastDecodeTokenCount >= maxTokens,
            )
        }
    }
}

internal actual fun EmbeddedLlmEngine(config: EmbeddedEngineConfig): ChatEngine = LiteRtEngine(config)
