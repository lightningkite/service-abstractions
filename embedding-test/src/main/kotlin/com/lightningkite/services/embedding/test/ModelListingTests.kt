package com.lightningkite.services.embedding.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [com.lightningkite.services.embedding.EmbeddingService.getModels].
 */
public abstract class ModelListingTests : EmbeddingServiceTests() {

    @Test
    public fun getModelsReturnsNonEmpty(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val models = service.getModels()
        assertTrue(models.isNotEmpty(), "getModels() should return at least one model")
    }

    @Test
    public fun modelsHaveDimensions(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val models = service.getModels()
        val withDimensions = models.filter { it.dimensions != null }
        assertTrue(
            withDimensions.isNotEmpty(),
            "At least one model should report its dimensions; got models: ${models.map { it.id.id }}",
        )
    }
}
