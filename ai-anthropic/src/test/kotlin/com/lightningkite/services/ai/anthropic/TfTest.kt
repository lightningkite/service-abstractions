package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

class TfTest {
    init {
        AnthropicLlmSettings.ensureRegistered()
    }

    @Test
    fun testAnthropicWithVariable() {
        assertPlannableAws<LlmAccess.Settings>("testAnthropicVar") {
            it.anthropic(
                modelId = "claude-sonnet-4-5",
                apiKey = "\${var.anthropic_api_key}"
            )
        }
    }
}
