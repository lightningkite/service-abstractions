package com.lightningkite.services.embedding.test

import com.lightningkite.services.database.EmbeddingSimilarity
import com.lightningkite.services.embedding.embed
import com.lightningkite.services.embedding.embedTexts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Core embedding contract tests: single embed, batch, determinism, semantic distance.
 * Every provider that implements [com.lightningkite.services.embedding.EmbeddingService]
 * should pass this class.
 */
public abstract class BasicEmbeddingTests : EmbeddingServiceTests() {

    @Test
    public fun singleTextReturnsVector(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val embedding = service.embed("Hello world", defaultModel)
        assertTrue(embedding.dimensions > 0, "Expected non-zero dimensions; got ${embedding.dimensions}")
    }

    @Test
    public fun batchEmbeddingReturnsSameCount(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val texts = SAMPLE_TEXTS
        val result = service.embed(texts, defaultModel)
        assertEquals(
            texts.size,
            result.embeddings.size,
            "Expected ${texts.size} embeddings; got ${result.embeddings.size}",
        )
        for (emb in result.embeddings) {
            assertTrue(emb.dimensions > 0, "Each embedding should have non-zero dimensions")
        }
    }

    @Test
    public fun sameTextProducesSameVector(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val text = "The quick brown fox jumps over the lazy dog."
        val a = service.embed(text, defaultModel)
        val b = service.embed(text, defaultModel)
        val similarity = EmbeddingSimilarity.cosineSimilarity(a, b)
        assertTrue(
            similarity > 0.99f,
            "Same text should produce nearly identical vectors; cosine similarity was $similarity",
        )
    }

    @Test
    public fun differentTextsProduceDifferentVectors(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val a = service.embed("fluffy orange cat sleeping on a couch", defaultModel)
        val b = service.embed("quantum chromodynamics and the strong nuclear force", defaultModel)
        val similarity = EmbeddingSimilarity.cosineSimilarity(a, b)
        assertTrue(
            similarity < 0.95f,
            "Semantically different texts should have lower similarity; got $similarity",
        )
    }

    @Test
    public fun emptyListReturnsEmptyResult(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.embed(emptyList(), defaultModel)
        assertTrue(
            result.embeddings.isEmpty(),
            "Empty input should produce empty embeddings list; got ${result.embeddings.size}",
        )
    }

    @Test
    public fun usageIsNonZero(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val result = service.embed(listOf("Test input for token counting"), defaultModel)
        assertTrue(
            result.usage.inputTokens > 0,
            "Expected non-zero inputTokens; got ${result.usage.inputTokens}",
        )
    }
}
