package com.lightningkite.services.ai.openai.integration

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.openai.OpenAiLlmSettings
import java.io.File
import java.util.Properties

/**
 * Shared configuration for the `:ai-openai` live integration tests.
 *
 * These tests hit the real OpenAI API and cost real tokens — they are gated on an
 * `OPENAI_API_KEY` credential. When absent, every test in the integration suite skips
 * (via [LlmAccessTests.servicePresent]=false → `Assume.assumeTrue(false)`), so CI
 * without credentials still passes.
 *
 * The credential is resolved in this order:
 *  1. `OPENAI_API_KEY` environment variable
 *  2. `OPENAI_API_KEY` entry in a `local.properties` file at any ancestor directory
 *
 * The lazy [service] shares a single [LlmAccess] instance across all seven integration
 * test classes so they reuse the same underlying ktor client.
 */
internal object OpenAiTestConfig {

    init {
        // Ensure the openai:// URL scheme is registered before any Settings lookup.
        OpenAiLlmSettings.ensureRegistered()
    }

    /**
     * Cheapest GA OpenAI model that still supports the full feature surface the shared
     * suite exercises (tools, vision, streaming). `gpt-4o-mini` is currently the cheapest
     * model that passes every capability test; `gpt-4.1-nano` is slightly cheaper but
     * has weaker tool-calling reliability.
     */
    val cheapModel: LlmModelId = LlmModelId("gpt-4o-mini")

    /** Same family as [cheapModel]; gpt-4o-mini supports vision natively. */
    val visionModel: LlmModelId = LlmModelId("gpt-4o-mini")

    /** True when an API key is available and the live suite should execute. */
    val apiKeyPresent: Boolean get() = apiKey != null

    private val apiKey: String? by lazy {
        System.getenv("OPENAI_API_KEY") ?: loadFromLocalProperties("OPENAI_API_KEY")
    }

    private val context = TestSettingContext()

    /**
     * Lazily-constructed live OpenAI service. Accessed only when [apiKeyPresent] is true;
     * calling the getter without a key throws an [IllegalStateException] to surface the
     * misuse immediately rather than making a broken HTTP request.
     */
    val service: LlmAccess by lazy {
        val key = apiKey ?: error("OPENAI_API_KEY not set; tests should check apiKeyPresent first")
        LlmAccess.Settings("openai://${cheapModel.asString}?apiKey=$key")(
            "openai-integration-test",
            context,
        )
    }

    /**
     * Service built with a deliberately-wrong API key — used by the error-handling suite
     * to verify auth failures surface as exceptions rather than silent empty responses.
     */
    val invalidCredentialsService: LlmAccess by lazy {
        LlmAccess.Settings("openai://${cheapModel.asString}?apiKey=sk-deliberately-invalid-key-for-tests")(
            "openai-integration-test-bad-key",
            context,
        )
    }

    private fun loadFromLocalProperties(key: String): String? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val file = File(dir, "local.properties")
            if (file.exists()) {
                val props = Properties()
                file.inputStream().use { props.load(it) }
                props.getProperty(key)?.let { return it }
            }
            dir = dir.parentFile
        }
        return null
    }
}
