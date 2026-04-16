package com.lightningkite.services.ai.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies [com.lightningkite.services.ai.LlmAccess.getModels] returns a sensible list.
 *
 * Providers vary on whether `getModels` returns every model they host or only the ones the
 * caller has access to, so [cheapModelAppearsInList] is best-effort and tolerated to skip.
 */
public abstract class GetModelsTests : LlmAccessTests() {

    /**
     * Set to false when the provider's getModels list is known to be filtered by the
     * caller's entitlements (i.e. `cheapModel` may legitimately not appear). Default true
     * so the stricter check runs by default and we learn about surprises.
     */
    public open val cheapModelExpectedInList: Boolean = true

    /**
     * Basic sanity: the provider reports at least one model.
     */
    @Test
    public fun listModelsReturnsAtLeastOne(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val models = service.getModels()
        assertTrue(
            models.isNotEmpty(),
            "Expected getModels() to return at least one model; got an empty list",
        )
    }

    /**
     * The model used by the rest of the test suite should appear in `getModels()`. Providers
     * with entitlement-scoped model lists can set [cheapModelExpectedInList]=false to skip.
     */
    @Test
    public fun cheapModelAppearsInList(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        if (!cheapModelExpectedInList) {
            println("SKIP cheapModelAppearsInList: provider scopes getModels() to caller entitlements")
            return@runTest
        }
        val models = service.getModels()
        val cheapId = cheapModel.id
        val present = models.any { it.id.id == cheapId || it.id.id.contains(cheapId, ignoreCase = true) }
        assertTrue(
            present,
            "Expected '$cheapId' in getModels()=${models.map { it.id.id }}",
        )
    }
}
