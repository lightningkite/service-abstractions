package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.database.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingSimilarityTest {

    @Test
    fun testCosineSimilarityIdentical() {
        val a = Embedding.of(1f, 0f, 0f)
        val b = Embedding.of(1f, 0f, 0f)
        val similarity = EmbeddingSimilarity.cosineSimilarity(a, b)
        // Identical vectors should have similarity of 1.0 (normalized from cosine=1 to (1+1)/2 = 1)
        assertEquals(1f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOrthogonal() {
        val a = Embedding.of(1f, 0f, 0f)
        val b = Embedding.of(0f, 1f, 0f)
        val similarity = EmbeddingSimilarity.cosineSimilarity(a, b)
        // Orthogonal vectors have cosine=0, normalized to (0+1)/2 = 0.5
        assertEquals(0.5f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOpposite() {
        val a = Embedding.of(1f, 0f, 0f)
        val b = Embedding.of(-1f, 0f, 0f)
        val similarity = EmbeddingSimilarity.cosineSimilarity(a, b)
        // Opposite vectors have cosine=-1, normalized to (-1+1)/2 = 0
        assertEquals(0f, similarity, 0.0001f)
    }

    @Test
    fun testDotProduct() {
        val a = Embedding.of(1f, 2f, 3f)
        val b = Embedding.of(4f, 5f, 6f)
        val dot = EmbeddingSimilarity.dotProduct(a, b)
        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32f, dot, 0.0001f)
    }

    @Test
    fun testEuclideanDistance() {
        val a = Embedding.of(0f, 0f, 0f)
        val b = Embedding.of(3f, 4f, 0f)
        val distance = EmbeddingSimilarity.euclideanDistance(a, b)
        // sqrt(9 + 16) = 5
        assertEquals(5f, distance, 0.0001f)
    }

    @Test
    fun testEuclideanSimilarity() {
        val a = Embedding.of(0f, 0f, 0f)
        val b = Embedding.of(3f, 4f, 0f)
        val similarity = EmbeddingSimilarity.euclideanSimilarity(a, b)
        // 1 / (1 + 5) = 1/6
        assertEquals(1f / 6f, similarity, 0.0001f)
    }

    @Test
    fun testManhattanDistance() {
        val a = Embedding.of(0f, 0f, 0f)
        val b = Embedding.of(1f, 2f, 3f)
        val distance = EmbeddingSimilarity.manhattanDistance(a, b)
        // |1| + |2| + |3| = 6
        assertEquals(6f, distance, 0.0001f)
    }

    // ===== Sparse Embedding Tests =====

    @Test
    fun testSparseCosineSimilarityIdentical() {
        val a = SparseEmbedding(intArrayOf(0, 2), floatArrayOf(1f, 1f), 10)
        val b = SparseEmbedding(intArrayOf(0, 2), floatArrayOf(1f, 1f), 10)
        val similarity = EmbeddingSimilarity.cosineSimilarity(a, b)
        assertEquals(1f, similarity, 0.0001f)
    }

    @Test
    fun testSparseCosineSimilarityDisjoint() {
        val a = SparseEmbedding(intArrayOf(0, 1), floatArrayOf(1f, 1f), 10)
        val b = SparseEmbedding(intArrayOf(2, 3), floatArrayOf(1f, 1f), 10)
        val similarity = EmbeddingSimilarity.cosineSimilarity(a, b)
        // No overlap, cosine = 0, normalized to 0.5
        assertEquals(0.5f, similarity, 0.0001f)
    }

    @Test
    fun testSparseDotProduct() {
        val a = SparseEmbedding(intArrayOf(0, 2, 5), floatArrayOf(1f, 2f, 3f), 10)
        val b = SparseEmbedding(intArrayOf(0, 2, 7), floatArrayOf(4f, 5f, 6f), 10)
        val dot = EmbeddingSimilarity.dotProduct(a, b)
        // 1*4 + 2*5 + 0 (no overlap at 5 vs 7) = 4 + 10 = 14
        assertEquals(14f, dot, 0.0001f)
    }

    @Test
    fun testSparseFromDense() {
        val dense = Embedding.of(0f, 1f, 0f, 2f, 0f)
        val sparse = SparseEmbedding.fromDense(dense)
        assertEquals(2, sparse.nonZeroCount)
        assertEquals(5, sparse.dimensions)
        assertTrue(sparse.indices.contentEquals(intArrayOf(1, 3)))
        assertTrue(sparse.values.contentEquals(floatArrayOf(1f, 2f)))
    }

    @Test
    fun testSparseFromMap() {
        val map = mapOf(5 to 1.5f, 2 to 2.5f, 8 to 3.5f)
        val sparse = SparseEmbedding.fromMap(map, 10)
        // Should be sorted by index
        assertTrue(sparse.indices.contentEquals(intArrayOf(2, 5, 8)))
        assertTrue(sparse.values.contentEquals(floatArrayOf(2.5f, 1.5f, 3.5f)))
    }

    @Test
    fun testSparseIndexAccess() {
        val sparse = SparseEmbedding(intArrayOf(1, 3, 5), floatArrayOf(1f, 2f, 3f), 10)
        assertEquals(1f, sparse[1])
        assertEquals(2f, sparse[3])
        assertEquals(3f, sparse[5])
        assertEquals(0f, sparse[0]) // Not present
        assertEquals(0f, sparse[2]) // Not present
        assertEquals(0f, sparse[9]) // Not present
    }
}
