@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import kotlin.test.*

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
}
