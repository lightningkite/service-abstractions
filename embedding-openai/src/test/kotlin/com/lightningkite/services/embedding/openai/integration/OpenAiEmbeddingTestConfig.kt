package com.lightningkite.services.embedding.openai.integration

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.openai.OpenAiEmbeddingSettings
import java.io.File
import java.util.Properties

/**
 * Shared configuration for the `:embedding-openai` live integration tests.
 *
 * Tests hit the real OpenAI API and cost real tokens — gated on `OPENAI_API_KEY`.
 * When absent, every test skips via [EmbeddingServiceTests.servicePresent]=false.
 */
internal object OpenAiEmbeddingTestConfig {

    init {
        OpenAiEmbeddingSettings.ensureRegistered()
    }

    val defaultModel: EmbeddingModelId = EmbeddingModelId("text-embedding-3-small")

    val apiKeyPresent: Boolean get() = apiKey != null

    private val apiKey: String? by lazy {
        System.getenv("OPENAI_API_KEY") ?: loadFromLocalProperties("OPENAI_API_KEY")
    }

    private val context = TestSettingContext()

    val service: EmbeddingService by lazy {
        val key = apiKey ?: error("OPENAI_API_KEY not set; tests should check apiKeyPresent first")
        EmbeddingService.Settings("openai://${defaultModel.id}?apiKey=$key")(
            "openai-embedding-test",
            context,
        )
    }

    val invalidCredentialsService: EmbeddingService by lazy {
        EmbeddingService.Settings("openai://${defaultModel.id}?apiKey=sk-deliberately-invalid-key-for-tests")(
            "openai-embedding-test-bad-key",
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
