package com.lightningkite.services.embedding.test

import com.lightningkite.services.embedding.EmbeddingException
import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Error handling contract tests. Verifies that provider implementations surface
 * appropriate [EmbeddingException] subclasses for common failure modes.
 */
public abstract class ErrorHandlingTests : EmbeddingServiceTests() {

    /**
     * A service configured with deliberately invalid credentials.
     * Null disables the [invalidApiKeyFails] test.
     */
    public open val invalidCredentialsService: EmbeddingService? = null

    @Test
    public fun unknownModelFails(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        assertFailsWith<EmbeddingException> {
            service.embed(listOf("test"), EmbeddingModelId("nonexistent-model-xyz-999"))
        }
    }

    @Test
    public fun invalidApiKeyFails(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val badService = invalidCredentialsService
        Assume.assumeTrue("No invalidCredentialsService provided; skipping.", badService != null)
        assertFailsWith<EmbeddingException.Auth> {
            badService!!.embed(listOf("test"), defaultModel)
        }
    }
}
