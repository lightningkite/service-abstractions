package com.lightningkite.services.database.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import com.lightningkite.services.database.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Abstract test suite for vector search functionality.
 * Database implementations should extend this class to verify their vector search implementation.
 */
abstract class VectorSearchTests {

    abstract val database: Database

    /**
     * Override and return false if the database doesn't support vector search.
     * Tests will be skipped.
     */
    open val supportsVectorSearch: Boolean = true

    /**
     * Override and return false if the database doesn't support sparse vector search.
     * Sparse vector tests will be skipped.
     */
    open val supportsSparseVectorSearch: Boolean = false

    /**
     * Override and return false if the database doesn't support changing similarity
     * metrics at query time. Some databases (like MongoDB Atlas) require the metric
     * to be specified at index creation time.
     *
     * If false, tests for Euclidean and DotProduct metrics will be skipped.
     */
    open val supportsQueryTimeMetrics: Boolean = true

    /**
     * Delay to wait after inserting documents before querying vector search.
     * Some databases (like MongoDB with mongot) need time to sync documents
     * to the search index via Change Streams.
     *
     * Override this if your database implementation has eventual consistency
     * for vector search indexes.
     */
    open val vectorSearchIndexSyncDelay: Duration = 0.milliseconds

    /**
     * Suspend function called after inserting documents to wait for
     * vector search index to sync. By default uses [vectorSearchIndexSyncDelay].
     *
     * Uses Dispatchers.Default to escape runTest's virtual time and perform
     * a real delay when needed.
     */
    protected open suspend fun waitForVectorSearchSync() {
        if (vectorSearchIndexSyncDelay > Duration.ZERO) {
            // Use Dispatchers.Default to escape runTest's virtual time
            // so the delay actually happens in real time
            withContext(Dispatchers.Default) {
                delay(vectorSearchIndexSyncDelay)
            }
        }
    }

    @Test
    open fun testFindSimilarBasic() = runTest {
        if (!supportsVectorSearch) {
            println("Vector search not supported, skipping test")
            return@runTest
        }

        val collection = database.table<VectorTestModel>("testFindSimilarBasic")

        // Clean up any existing documents from previous test runs
        collection.deleteMany(Condition.Always)
        waitForVectorSearchSync()

        // Create documents with simple embeddings
        val doc1 = VectorTestModel(
            title = "Machine Learning",
            category = "tech",
            embedding = Embedding.of(1f, 0f, 0f) // Points in x direction
        )
        val doc2 = VectorTestModel(
            title = "Deep Learning",
            category = "tech",
            embedding = Embedding.of(0.9f, 0.1f, 0f) // Similar to doc1
        )
        val doc3 = VectorTestModel(
            title = "Cooking Recipes",
            category = "food",
            embedding = Embedding.of(0f, 1f, 0f) // Orthogonal to doc1
        )

        collection.insertMany(listOf(doc1, doc2, doc3))
        waitForVectorSearchSync()

        // Search for vectors similar to [1, 0, 0]
        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = collection.findSimilar(
            vectorField = VectorTestModel.path.embedding,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 10
            )
        ).toList()

        assertEquals(3, results.size)
        // doc1 should be first (exact match)
        assertEquals("Machine Learning", results[0].model.title)
        assertEquals(1f, results[0].score, 0.01f)
        // doc2 should be second (similar)
        assertEquals("Deep Learning", results[1].model.title)
        // doc3 should be last (orthogonal)
        assertEquals("Cooking Recipes", results[2].model.title)
        assertEquals(0.5f, results[2].score, 0.01f) // Orthogonal = 0.5 normalized cosine
    }

    @Test
    open fun testFindSimilarWithMinScore() = runTest {
        if (!supportsVectorSearch) {
            println("Vector search not supported, skipping test")
            return@runTest
        }

        val collection = database.table<VectorTestModel>("testFindSimilarWithMinScore")

        // Clean up any existing documents from previous test runs
        collection.deleteMany(Condition.Always)
        waitForVectorSearchSync()

        val doc1 = VectorTestModel(
            title = "Very Similar",
            category = "tech",
            embedding = Embedding.of(1f, 0f, 0f)
        )
        val doc2 = VectorTestModel(
            title = "Different",
            category = "tech",
            embedding = Embedding.of(0f, 1f, 0f)
        )

        collection.insertMany(listOf(doc1, doc2))
        waitForVectorSearchSync()

        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = collection.findSimilar(
            vectorField = VectorTestModel.path.embedding,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 10,
                minScore = 0.9f // Only highly similar
            )
        ).toList()

        assertEquals(1, results.size)
        assertEquals("Very Similar", results[0].model.title)
    }

    @Test
    open fun testFindSimilarWithCondition() = runTest {
        if (!supportsVectorSearch) {
            println("Vector search not supported, skipping test")
            return@runTest
        }

        val collection = database.table<VectorTestModel>("testFindSimilarWithCondition")

        // Clean up any existing documents from previous test runs
        collection.deleteMany(Condition.Always)
        waitForVectorSearchSync()

        val doc1 = VectorTestModel(
            title = "Tech Doc 1",
            category = "tech",
            embedding = Embedding.of(1f, 0f, 0f)
        )
        val doc2 = VectorTestModel(
            title = "Food Doc",
            category = "food",
            embedding = Embedding.of(1f, 0f, 0f) // Same embedding but different category
        )
        val doc3 = VectorTestModel(
            title = "Tech Doc 2",
            category = "tech",
            embedding = Embedding.of(0.5f, 0.5f, 0f)
        )

        collection.insertMany(listOf(doc1, doc2, doc3))
        waitForVectorSearchSync()

        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = collection.findSimilar(
            vectorField = VectorTestModel.path.embedding,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 10
            ),
            condition = VectorTestModel.path.category eq "tech"
        ).toList()

        assertEquals(2, results.size)
        assertTrue(results.all { it.model.category == "tech" })
        assertEquals("Tech Doc 1", results[0].model.title) // Most similar
    }

    @Test
    open fun testFindSimilarWithLimit() = runTest {
        if (!supportsVectorSearch) {
            println("Vector search not supported, skipping test")
            return@runTest
        }

        val collection = database.table<VectorTestModel>("testFindSimilarWithLimit")

        // Clean up any existing documents from previous test runs
        collection.deleteMany(Condition.Always)
        waitForVectorSearchSync()

        // Insert 10 documents
        val docs = (1..10).map { i ->
            VectorTestModel(
                title = "Doc $i",
                category = "tech",
                embedding = Embedding.of(1f - i * 0.05f, i * 0.05f, 0f)
            )
        }
        collection.insertMany(docs)
        waitForVectorSearchSync()

        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = collection.findSimilar(
            vectorField = VectorTestModel.path.embedding,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 3
            )
        ).toList()

        assertEquals(3, results.size)
    }

    @Test
    open fun testFindSimilarEuclidean() = runTest {
        if (!supportsVectorSearch) {
            println("Vector search not supported, skipping test")
            return@runTest
        }
        if (!supportsQueryTimeMetrics) {
            println("Query-time metrics not supported, skipping Euclidean test")
            return@runTest
        }

        val collection = database.table<VectorTestModel>("testFindSimilarEuclidean")

        // Clean up any existing documents from previous test runs
        collection.deleteMany(Condition.Always)
        waitForVectorSearchSync()

        val doc1 = VectorTestModel(
            title = "Close",
            category = "tech",
            embedding = Embedding.of(1f, 1f, 0f)
        )
        val doc2 = VectorTestModel(
            title = "Far",
            category = "tech",
            embedding = Embedding.of(10f, 10f, 0f)
        )

        collection.insertMany(listOf(doc1, doc2))
        waitForVectorSearchSync()

        val queryVector = Embedding.of(0f, 0f, 0f)
        val results = collection.findSimilar(
            vectorField = VectorTestModel.path.embedding,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Euclidean,
                limit = 10
            )
        ).toList()

        assertEquals(2, results.size)
        // doc1 is closer (distance ~1.41), so higher similarity score
        assertEquals("Close", results[0].model.title)
        assertTrue(results[0].score > results[1].score)
    }

    @Test
    open fun testFindSimilarDotProduct() = runTest {
        if (!supportsVectorSearch) {
            println("Vector search not supported, skipping test")
            return@runTest
        }
        if (!supportsQueryTimeMetrics) {
            println("Query-time metrics not supported, skipping DotProduct test")
            return@runTest
        }

        val collection = database.table<VectorTestModel>("testFindSimilarDotProduct")

        // Clean up any existing documents from previous test runs
        collection.deleteMany(Condition.Always)
        waitForVectorSearchSync()

        val doc1 = VectorTestModel(
            title = "High Magnitude",
            category = "tech",
            embedding = Embedding.of(2f, 2f, 0f)
        )
        val doc2 = VectorTestModel(
            title = "Low Magnitude",
            category = "tech",
            embedding = Embedding.of(0.5f, 0.5f, 0f)
        )

        collection.insertMany(listOf(doc1, doc2))
        waitForVectorSearchSync()

        val queryVector = Embedding.of(1f, 1f, 0f)
        val results = collection.findSimilar(
            vectorField = VectorTestModel.path.embedding,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.DotProduct,
                limit = 10
            )
        ).toList()

        assertEquals(2, results.size)
        // doc1 has higher dot product (2*1 + 2*1 = 4 vs 0.5*1 + 0.5*1 = 1)
        assertEquals("High Magnitude", results[0].model.title)
        assertTrue(results[0].score > results[1].score)
    }

    // Sparse vector tests

    @Test
    open fun testFindSimilarSparseBasic() = runTest {
        if (!supportsSparseVectorSearch) {
            println("Sparse vector search not supported, skipping test")
            return@runTest
        }

        val collection = database.table<SparseVectorTestModel>("testFindSimilarSparseBasic")

        // Clean up any existing documents from previous test runs
        collection.deleteMany(Condition.Always)
        waitForVectorSearchSync()

        val doc1 = SparseVectorTestModel(
            title = "Doc A",
            sparseEmbedding = SparseEmbedding(intArrayOf(0, 2), floatArrayOf(1f, 1f), 10)
        )
        val doc2 = SparseVectorTestModel(
            title = "Doc B",
            sparseEmbedding = SparseEmbedding(intArrayOf(0, 2), floatArrayOf(0.9f, 0.9f), 10)
        )
        val doc3 = SparseVectorTestModel(
            title = "Doc C",
            sparseEmbedding = SparseEmbedding(intArrayOf(3, 5), floatArrayOf(1f, 1f), 10) // Disjoint indices
        )

        collection.insertMany(listOf(doc1, doc2, doc3))
        waitForVectorSearchSync()

        val queryVector = SparseEmbedding(intArrayOf(0, 2), floatArrayOf(1f, 1f), 10)
        val results = collection.findSimilarSparse(
            vectorField = SparseVectorTestModel.path.sparseEmbedding,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 10
            )
        ).toList()

        assertEquals(3, results.size)
        // doc1 should be first (exact match)
        assertEquals("Doc A", results[0].model.title)
        assertEquals(1f, results[0].score, 0.01f)
    }
}
