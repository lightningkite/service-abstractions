package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.anthropic.AnthropicLlmSettings
import java.io.File
import java.util.Properties

/**
 * Shared setup for the live Anthropic integration test suites in this package.
 *
 * All suites share a single [service] instance (reused across classes within the same JVM
 * so the ktor client is not rebuilt per test class) and skip silently when the API key is
 * absent so CI stays green on forks without credentials.
 *
 * Credentials are sourced from `ANTHROPIC_API_KEY` — checked first in the environment, then
 * in a `local.properties` file walking up from the working directory. The latter mirrors
 * [com.lightningkite.services.ai.anthropic.AnthropicLiveTest] and keeps the developer
 * experience uniform across live test classes in this module.
 */
internal object AnthropicTestConfig {

    /** Cheapest Haiku available as of 2026-04. Used for every live test to minimize spend. */
    val cheapModel: LlmModelId = LlmModelId("claude-haiku-4-5")

    /** Haiku 4.5 supports vision, so the multimodal suite reuses [cheapModel]. */
    val visionModel: LlmModelId = LlmModelId("claude-haiku-4-5")

    /** Lazily resolves the key so early-loaded test classes don't force env access eagerly. */
    val apiKey: String? by lazy {
        System.getenv("ANTHROPIC_API_KEY") ?: loadFromLocalProperties("ANTHROPIC_API_KEY")
    }

    /** True when the key is present; the abstract suites gate every @Test on this via `servicePresent`. */
    val servicePresent: Boolean get() = apiKey != null

    private val context: TestSettingContext by lazy { TestSettingContext() }

    /**
     * Live [LlmAccess] wired through the `anthropic://` URL scheme. Built lazily so a missing
     * key doesn't throw at class-load time — it just leaves every test suite to assume-skip.
     */
    val service: LlmAccess by lazy {
        AnthropicLlmSettings.ensureRegistered()
        val key = apiKey
            ?: error("AnthropicTestConfig.service accessed without a key; gate on servicePresent first")
        LlmAccess.Settings("anthropic://${cheapModel.id}?apiKey=$key")(
            "anthropic-integration",
            context,
        )
    }

    /**
     * Second [LlmAccess] with a deliberately invalid key. Used by [com.lightningkite.services.ai.test.ErrorHandlingTests]
     * to verify auth failures surface as typed exceptions. Lazy so the bogus key only gets
     * constructed when the test actually runs.
     */
    val invalidCredentialsService: LlmAccess by lazy {
        AnthropicLlmSettings.ensureRegistered()
        LlmAccess.Settings("anthropic://${cheapModel.id}?apiKey=sk-ant-invalid-deadbeef")(
            "anthropic-integration-badkey",
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
