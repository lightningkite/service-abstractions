package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAccess
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemeRegistrationTest {
    @Test
    fun schemeRegistrationSmoke() {
        // Touching OllamaSchemeRegistrar triggers scheme registration via object init.
        OllamaSchemeRegistrar.ensureRegistered()
        assertTrue(LlmAccess.Settings.supports("ollama"), "ollama:// should be registered")
    }
}
