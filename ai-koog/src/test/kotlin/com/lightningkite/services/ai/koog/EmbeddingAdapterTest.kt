// Tests by Claude
package com.lightningkite.services.ai.koog

import ai.koog.embeddings.base.Vector as KoogVector
import com.lightningkite.services.database.Embedding as DbEmbedding
import kotlin.test.*

/**
 * Tests for EmbeddingAdapter which converts between Koog's Vector (List<Double>)
 * and the database Embedding (FloatArray).
 */
class EmbeddingAdapterTest {

    // --- EmbeddingAdapter.toDatabase tests ---

    @Test
    fun testToDatabaseWithTypicalVector() {
        val koogVector = KoogVector(listOf(0.1, 0.2, 0.3, 0.4, 0.5))
        val dbEmbedding = EmbeddingAdapter.toDatabase(koogVector)

        assertEquals(5, dbEmbedding.dimensions)
        assertEquals(0.1f, dbEmbedding.values[0], 0.0001f)
        assertEquals(0.2f, dbEmbedding.values[1], 0.0001f)
        assertEquals(0.3f, dbEmbedding.values[2], 0.0001f)
        assertEquals(0.4f, dbEmbedding.values[3], 0.0001f)
        assertEquals(0.5f, dbEmbedding.values[4], 0.0001f)
    }

    @Test
    fun testToDatabaseWithEmptyVector() {
        val koogVector = KoogVector(emptyList())
        val dbEmbedding = EmbeddingAdapter.toDatabase(koogVector)

        assertEquals(0, dbEmbedding.dimensions)
        assertTrue(dbEmbedding.values.isEmpty())
    }

    @Test
    fun testToDatabaseWithSingleElement() {
        val koogVector = KoogVector(listOf(1.5))
        val dbEmbedding = EmbeddingAdapter.toDatabase(koogVector)

        assertEquals(1, dbEmbedding.dimensions)
        assertEquals(1.5f, dbEmbedding.values[0], 0.0001f)
    }

    @Test
    fun testToDatabaseWithNegativeValues() {
        val koogVector = KoogVector(listOf(-0.5, -1.0, -100.0))
        val dbEmbedding = EmbeddingAdapter.toDatabase(koogVector)

        assertEquals(3, dbEmbedding.dimensions)
        assertEquals(-0.5f, dbEmbedding.values[0], 0.0001f)
        assertEquals(-1.0f, dbEmbedding.values[1], 0.0001f)
        assertEquals(-100.0f, dbEmbedding.values[2], 0.0001f)
    }

    @Test
    fun testToDatabaseWithZeroValues() {
        val koogVector = KoogVector(listOf(0.0, 0.0, 0.0))
        val dbEmbedding = EmbeddingAdapter.toDatabase(koogVector)

        assertEquals(3, dbEmbedding.dimensions)
        assertEquals(0.0f, dbEmbedding.values[0], 0.0001f)
        assertEquals(0.0f, dbEmbedding.values[1], 0.0001f)
        assertEquals(0.0f, dbEmbedding.values[2], 0.0001f)
    }

    @Test
    fun testToDatabaseWithLargeValues() {
        val koogVector = KoogVector(listOf(1e10, -1e10, 3.4e38))
        val dbEmbedding = EmbeddingAdapter.toDatabase(koogVector)

        assertEquals(3, dbEmbedding.dimensions)
        assertEquals(1e10f, dbEmbedding.values[0], 1e6f)
        assertEquals(-1e10f, dbEmbedding.values[1], 1e6f)
        // 3.4e38 double -> float: within Float range, approximate comparison needed
        assertEquals(3.4e38.toFloat(), dbEmbedding.values[2])
    }

    @Test
    fun testToDatabaseWithHighDimensional() {
        // Typical embedding dimensions like 768, 1536, or 3072
        val dimensions = 768
        val values = (0 until dimensions).map { it.toDouble() / dimensions }
        val koogVector = KoogVector(values)
        val dbEmbedding = EmbeddingAdapter.toDatabase(koogVector)

        assertEquals(dimensions, dbEmbedding.dimensions)
        for (i in 0 until dimensions) {
            assertEquals(values[i].toFloat(), dbEmbedding.values[i], 0.0001f)
        }
    }

    // --- EmbeddingAdapter.toKoog tests ---

    @Test
    fun testToKoogWithTypicalEmbedding() {
        val dbEmbedding = DbEmbedding(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f))
        val koogVector = EmbeddingAdapter.toKoog(dbEmbedding)

        assertEquals(5, koogVector.values.size)
        assertEquals(0.1, koogVector.values[0], 0.001)
        assertEquals(0.2, koogVector.values[1], 0.001)
        assertEquals(0.3, koogVector.values[2], 0.001)
        assertEquals(0.4, koogVector.values[3], 0.001)
        assertEquals(0.5, koogVector.values[4], 0.001)
    }

    @Test
    fun testToKoogWithEmptyEmbedding() {
        val dbEmbedding = DbEmbedding(floatArrayOf())
        val koogVector = EmbeddingAdapter.toKoog(dbEmbedding)

        assertEquals(0, koogVector.values.size)
    }

    @Test
    fun testToKoogWithSingleElement() {
        val dbEmbedding = DbEmbedding(floatArrayOf(2.5f))
        val koogVector = EmbeddingAdapter.toKoog(dbEmbedding)

        assertEquals(1, koogVector.values.size)
        assertEquals(2.5, koogVector.values[0], 0.001)
    }

    @Test
    fun testToKoogWithNegativeValues() {
        val dbEmbedding = DbEmbedding(floatArrayOf(-0.5f, -1.0f, -50.0f))
        val koogVector = EmbeddingAdapter.toKoog(dbEmbedding)

        assertEquals(3, koogVector.values.size)
        assertEquals(-0.5, koogVector.values[0], 0.001)
        assertEquals(-1.0, koogVector.values[1], 0.001)
        assertEquals(-50.0, koogVector.values[2], 0.001)
    }

    @Test
    fun testToKoogWithZeroValues() {
        val dbEmbedding = DbEmbedding(floatArrayOf(0.0f, 0.0f))
        val koogVector = EmbeddingAdapter.toKoog(dbEmbedding)

        assertEquals(2, koogVector.values.size)
        assertEquals(0.0, koogVector.values[0], 0.001)
        assertEquals(0.0, koogVector.values[1], 0.001)
    }

    // --- Extension function tests ---

    @Test
    fun testToDbEmbeddingExtensionFunction() {
        val koogVector = KoogVector(listOf(1.0, 2.0, 3.0))
        val dbEmbedding = koogVector.toDbEmbedding()

        assertEquals(3, dbEmbedding.dimensions)
        assertEquals(1.0f, dbEmbedding.values[0], 0.0001f)
        assertEquals(2.0f, dbEmbedding.values[1], 0.0001f)
        assertEquals(3.0f, dbEmbedding.values[2], 0.0001f)
    }

    @Test
    fun testToKoogVectorExtensionFunction() {
        val dbEmbedding = DbEmbedding(floatArrayOf(1.0f, 2.0f, 3.0f))
        val koogVector = dbEmbedding.toKoogVector()

        assertEquals(3, koogVector.values.size)
        assertEquals(1.0, koogVector.values[0], 0.001)
        assertEquals(2.0, koogVector.values[1], 0.001)
        assertEquals(3.0, koogVector.values[2], 0.001)
    }

    // --- Round-trip tests ---

    @Test
    fun testRoundTripKoogToDbToKoog() {
        val original = KoogVector(listOf(0.1, 0.2, 0.3, 0.4, 0.5))
        val roundTripped = original.toDbEmbedding().toKoogVector()

        assertEquals(original.values.size, roundTripped.values.size)
        for (i in original.values.indices) {
            // Note: Some precision loss expected due to Double -> Float -> Double
            assertEquals(original.values[i], roundTripped.values[i], 0.0001)
        }
    }

    @Test
    fun testRoundTripDbToKoogToDb() {
        val original = DbEmbedding(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f))
        val roundTripped = original.toKoogVector().toDbEmbedding()

        assertEquals(original.dimensions, roundTripped.dimensions)
        for (i in original.values.indices) {
            // Float -> Double -> Float should be exact
            assertEquals(original.values[i], roundTripped.values[i], 0.0001f)
        }
    }

    @Test
    fun testRoundTripPreservesLength() {
        val dimensions = 1536
        val koogVector = KoogVector((0 until dimensions).map { it.toDouble() / dimensions })
        val roundTripped = koogVector.toDbEmbedding().toKoogVector()

        assertEquals(dimensions, roundTripped.values.size)
    }

    // --- Precision loss tests ---

    @Test
    fun testDoubleToFloatPrecisionLoss() {
        // This test documents the expected precision loss when converting Double to Float
        // Double has ~15-17 decimal digits of precision, Float has ~6-9
        val highPrecisionValue = 0.123456789012345
        val koogVector = KoogVector(listOf(highPrecisionValue))
        val dbEmbedding = koogVector.toDbEmbedding()

        // Float will only preserve approximately 6-7 decimal digits
        val floatValue = dbEmbedding.values[0]
        val backToDouble = floatValue.toDouble()

        // The values won't be exactly equal due to precision loss
        assertNotEquals(highPrecisionValue, backToDouble)
        // But they should be close within Float precision
        assertEquals(highPrecisionValue, backToDouble, 1e-6)
    }

    @Test
    fun testFloatToDoubleNoPrecisionLoss() {
        // Float -> Double should not lose precision (Double can represent all Float values)
        val floatValue = 0.123456f
        val dbEmbedding = DbEmbedding(floatArrayOf(floatValue))
        val koogVector = dbEmbedding.toKoogVector()

        // The round-trip should preserve the value exactly
        assertEquals(floatValue.toDouble(), koogVector.values[0], 0.0)
    }

    // --- Edge cases ---

    @Test
    fun testSpecialFloatValuesPositiveInfinity() {
        val koogVector = KoogVector(listOf(Double.POSITIVE_INFINITY))
        val dbEmbedding = koogVector.toDbEmbedding()

        assertEquals(Float.POSITIVE_INFINITY, dbEmbedding.values[0])
    }

    @Test
    fun testSpecialFloatValuesNegativeInfinity() {
        val koogVector = KoogVector(listOf(Double.NEGATIVE_INFINITY))
        val dbEmbedding = koogVector.toDbEmbedding()

        assertEquals(Float.NEGATIVE_INFINITY, dbEmbedding.values[0])
    }

    @Test
    fun testSpecialFloatValuesNaN() {
        val koogVector = KoogVector(listOf(Double.NaN))
        val dbEmbedding = koogVector.toDbEmbedding()

        assertTrue(dbEmbedding.values[0].isNaN())
    }
}
