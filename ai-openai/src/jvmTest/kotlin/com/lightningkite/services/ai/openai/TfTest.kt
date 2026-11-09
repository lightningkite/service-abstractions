package com.lightningkite.services.ai.openai

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

class TfTest {
    init {
        OpenAiLlmSettings.ensureRegistered()
    }

    @Test
    fun testOpenaiWithVariable() {
        assertPlannableAws<LlmAccess.Settings>("testOpenaiVar") {
            it.openai(
                modelId = "gpt-4o",
                apiKey = "\${var.openai_api_key}"
            )
        }
    }

    @Test
    fun testOpenaiWithCustomBaseUrl() {
        assertPlannableAws<LlmAccess.Settings>("testOpenaiCustom") {
            it.openai(
                modelId = "qwen2.5-coder",
                apiKey = "not-needed",
                baseUrl = "http://localhost:1234/v1"
            )
        }
    }
}
