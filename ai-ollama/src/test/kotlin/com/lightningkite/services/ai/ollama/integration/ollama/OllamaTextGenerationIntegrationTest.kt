package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.TextGenerationTests

/**
 * Live text-generation suite against a local Ollama server.
 * Silently skips every test when the server at [OllamaTestConfig.baseUrl] is unreachable.
 */
class OllamaTextGenerationIntegrationTest : TextGenerationTests() {
    override val service: LlmAccess get() = OllamaTestConfig.service
    override val cheapModel: LlmModelId get() = OllamaTestConfig.cheapModel
    override val servicePresent: Boolean get() = OllamaTestConfig.servicePresent

    /**
     * Ollama forwards [com.lightningkite.services.ai.LlmPrompt.stopSequences] to llama.cpp,
     * but its `/api/chat` response's `done_reason` is always `"stop"` for any natural
     * termination — it does not distinguish "hit a user-supplied stop sequence" from
     * "reached natural end-of-turn." Our mapping therefore always reports EndTurn, not
     * StopSequence, so the stricter [stopSequences] assertion fails by construction.
     *
     * Flagging as unsupported skips the single assertion; stop sequences ARE honored in
     * generation, just not reported distinctly.
     */
    override val supportsStopSequences: Boolean = false
}
