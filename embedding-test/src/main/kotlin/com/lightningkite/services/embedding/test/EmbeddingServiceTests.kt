package com.lightningkite.services.embedding.test

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService

/**
 * Common base for every shared [EmbeddingService] contract test class.
 *
 * Subclass in a provider module and override [service] + [defaultModel]. Override
 * [servicePresent] to false when credentials are unavailable; the suite will skip
 * rather than fail.
 *
 * Example consumer:
 * ```kotlin
 * class OpenAiBasicEmbeddingTest : BasicEmbeddingTests() {
 *     override val service: EmbeddingService get() = OpenAiEmbeddingTestConfig.service
 *     override val defaultModel: EmbeddingModelId get() = OpenAiEmbeddingTestConfig.defaultModel
 *     override val servicePresent: Boolean get() = OpenAiEmbeddingTestConfig.apiKeyPresent
 * }
 * ```
 */
public abstract class EmbeddingServiceTests {

    /** The service under test. */
    public abstract val service: EmbeddingService

    /** Model id used for tests. Should be a cheap model to keep CI cost low. */
    public abstract val defaultModel: EmbeddingModelId

    /** Override to false if credentials are unavailable. All tests skip instead of fail. */
    public open val servicePresent: Boolean = true

    protected fun skipIfServiceAbsent() {
        if (!servicePresent) {
            org.junit.Assume.assumeTrue(
                "EmbeddingService not available (missing credentials?); skipping.",
                false,
            )
        }
    }
}
