package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

class TfTest {
    init {
        OllamaSchemeRegistrar.ensureRegistered()
    }

    @Test
    fun testOllamaDefault() {
        assertPlannableAws<LlmAccess.Settings>("testOllamaDefault") {
            it.ollama(modelId = "llama3")
        }
    }

    @Test
    fun testOllamaCustomBaseUrl() {
        assertPlannableAws<LlmAccess.Settings>("testOllamaCustom") {
            it.ollama(
                modelId = "mistral",
                baseUrl = "http://ollama.internal:11434"
            )
        }
    }
}
