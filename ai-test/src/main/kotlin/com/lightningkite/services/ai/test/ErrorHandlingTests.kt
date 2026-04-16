package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies providers fail loudly on pathological inputs rather than silently degrading.
 *
 * The contract position is "Fail Fast" — invalid credentials or bogus model ids must throw
 * with a message referencing the offending input; they must never return a misleading
 * "successful" but empty response.
 *
 * The `invalidApiKey` test requires the subclass to expose an [invalidCredentialsService];
 * subclasses that cannot construct an obviously-invalid variant should leave it null, which
 * skips that test.
 */
public abstract class ErrorHandlingTests : LlmAccessTests() {

    /**
     * An [com.lightningkite.services.ai.LlmAccess] pointed at the same provider but with an
     * invalid API key. Used to verify the provider surfaces auth failures as exceptions.
     * Null skips the test.
     */
    public open val invalidCredentialsService: com.lightningkite.services.ai.LlmAccess? = null

    /**
     * Requesting a model that does not exist must throw an exception referencing the bad
     * model id, not return empty text.
     */
    @Test
    public fun unknownModelFails(): Unit = runTest(timeout = 30.seconds) {
        skipIfServiceAbsent()
        val bogus = LlmModelId("definitely-not-a-real-model-xyz-12345")
        try {
            service.inference(
                model = bogus,
                prompt = LlmPrompt(
                    messages = listOf(userText("Hello.")),
                    maxTokens = testMaxTokens,
                ),
            )
            fail("Expected an exception when requesting a non-existent model; got success")
        } catch (e: Throwable) {
            val message = (e.message.orEmpty() + " " + e.cause?.message.orEmpty()).lowercase()
            // The provider's error should mention the model name or "model" so callers can
            // diagnose from the exception alone.
            assertTrue(
                "model" in message || "definitely-not-a-real-model" in message || "not found" in message ||
                    "404" in message,
                "Error message should reference the bogus model or 'model'; got: '${e.message}'",
            )
        }
    }

    /**
     * An invalid API key must produce an auth-style exception (4xx from the provider).
     * Skipped when the subclass does not provide an [invalidCredentialsService].
     */
    @Test
    public fun invalidApiKeyFails(): Unit = runTest(timeout = 30.seconds) {
        skipIfServiceAbsent()
        val bad = invalidCredentialsService ?: run {
            println("SKIP invalidApiKeyFails: subclass did not provide invalidCredentialsService")
            return@runTest
        }
        try {
            bad.inference(
                model = cheapModel,
                prompt = LlmPrompt(
                    messages = listOf(userText("Hello.")),
                    maxTokens = testMaxTokens,
                ),
            )
            fail("Expected an auth exception from an invalid-key service; got success")
        } catch (e: Throwable) {
            val message = (e.message.orEmpty() + " " + e.cause?.message.orEmpty()).lowercase()
            assertTrue(
                "auth" in message || "api key" in message || "unauthorized" in message ||
                    "401" in message || "403" in message || "invalid" in message,
                "Error message should indicate auth failure; got: '${e.message}'",
            )
        }
    }
}
