package com.lightningkite.services.ai.embedded

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Integration test that loads a tiny random model via Transformers.js and runs inference.
 *
 * Uses `Xenova/tiny-random-MistralForCausalLM` — a ~5MB randomly-initialized model
 * designed for testing. Output will be gibberish, but proves the full pipeline works:
 * load model -> format prompt -> stream tokens -> collect result.
 *
 * First run downloads the model from HuggingFace Hub (cached in IndexedDB after).
 */
class TransformersJsIntegrationTest {

    private val tinyModelId = "onnx-community/tiny-random-LlamaForCausalLM-ONNX"

    @Test
    fun loadAndGenerate() = runTest(timeout = 3.minutes) {
        val engine = EmbeddedLlmEngine(
            EmbeddedEngineConfig(
                modelName = "tiny-random-mistral",
                modelPath = tinyModelId,
                threads = 1,
                contextSize = 128,
            )
        )

        // Load model (downloads ~5MB on first run)
        engine.loadModel()
        assertTrue(engine.isModelLoaded(), "Engine should report model loaded")

        // Generate a few tokens — output will be random gibberish
        val tokens = engine.generate(
            text = "Hello",
            maxTokens = 5,
            temperature = 0.0,  // greedy for determinism
            stopSequences = emptyList(),
        ).toList()

        // We should have received at least one token
        assertTrue(tokens.isNotEmpty(), "Expected at least one generated token, got none")
        val output = tokens.joinToString("")
        assertTrue(output.isNotEmpty(), "Generated text should not be empty")

        println("Generated ${tokens.size} tokens: $output")

        // Cleanup
        engine.unloadModel()
        assertTrue(!engine.isModelLoaded(), "Engine should report model unloaded")
    }
}
