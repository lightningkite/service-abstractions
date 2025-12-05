package com.lightningkite.services.database

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class DocumentWithEmbedding(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val category: String,
    val embedding: Embedding,
) : HasId<Uuid>

@OptIn(ExperimentalUuidApi::class)
class VectorSearchTest {

    private val serializer = DocumentWithEmbedding.serializer()

    // Create manual DataClassPath for the embedding field
    private val embeddingPath: DataClassPath<DocumentWithEmbedding, Embedding> by lazy {
        val props = serializer.serializableProperties!!
        val embeddingProp = props.first { it.name == "embedding" }
        @Suppress("UNCHECKED_CAST")
        DataClassPathAccess(
            DataClassPathSelf(serializer),
            embeddingProp as SerializableProperty<DocumentWithEmbedding, Embedding>
        )
    }

    private val categoryPath: DataClassPath<DocumentWithEmbedding, String> by lazy {
        val props = serializer.serializableProperties!!
        val categoryProp = props.first { it.name == "category" }
        @Suppress("UNCHECKED_CAST")
        DataClassPathAccess(
            DataClassPathSelf(serializer),
            categoryProp as SerializableProperty<DocumentWithEmbedding, String>
        )
    }

    @Test
    fun testFindSimilarBasic() = runTest {
        val table = InMemoryTable(serializer = serializer)

        // Create documents with simple embeddings
        val doc1 = DocumentWithEmbedding(
            title = "Machine Learning",
            category = "tech",
            embedding = Embedding.of(1f, 0f, 0f) // Points in x direction
        )
        val doc2 = DocumentWithEmbedding(
            title = "Deep Learning",
            category = "tech",
            embedding = Embedding.of(0.9f, 0.1f, 0f) // Similar to doc1
        )
        val doc3 = DocumentWithEmbedding(
            title = "Cooking Recipes",
            category = "food",
            embedding = Embedding.of(0f, 1f, 0f) // Orthogonal to doc1
        )

        table.insert(listOf(doc1, doc2, doc3))

        // Search for vectors similar to [1, 0, 0]
        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = table.findSimilar(
            vectorField = embeddingPath,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 10
            )
        ).toList()

        assertEquals(3, results.size)
        // doc1 should be first (exact match)
        assertEquals("Machine Learning", results[0].model.title)
        assertEquals(1f, results[0].score, 0.0001f)
        // doc2 should be second (similar)
        assertEquals("Deep Learning", results[1].model.title)
        // doc3 should be last (orthogonal)
        assertEquals("Cooking Recipes", results[2].model.title)
        assertEquals(0.5f, results[2].score, 0.01f) // Orthogonal = 0.5 normalized cosine
    }

    @Test
    fun testFindSimilarWithMinScore() = runTest {
        val table = InMemoryTable(serializer = serializer)

        val doc1 = DocumentWithEmbedding(
            title = "Very Similar",
            category = "tech",
            embedding = Embedding.of(1f, 0f, 0f)
        )
        val doc2 = DocumentWithEmbedding(
            title = "Different",
            category = "tech",
            embedding = Embedding.of(0f, 1f, 0f)
        )

        table.insert(listOf(doc1, doc2))

        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = table.findSimilar(
            vectorField = embeddingPath,
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
    fun testFindSimilarWithCondition() = runTest {
        val table = InMemoryTable(serializer = serializer)

        val doc1 = DocumentWithEmbedding(
            title = "Tech Doc 1",
            category = "tech",
            embedding = Embedding.of(1f, 0f, 0f)
        )
        val doc2 = DocumentWithEmbedding(
            title = "Food Doc",
            category = "food",
            embedding = Embedding.of(1f, 0f, 0f) // Same embedding but different category
        )
        val doc3 = DocumentWithEmbedding(
            title = "Tech Doc 2",
            category = "tech",
            embedding = Embedding.of(0.5f, 0.5f, 0f)
        )

        table.insert(listOf(doc1, doc2, doc3))

        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = table.findSimilar(
            vectorField = embeddingPath,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 10
            ),
            condition = categoryPath eq "tech"
        ).toList()

        assertEquals(2, results.size)
        assertTrue(results.all { it.model.category == "tech" })
        assertEquals("Tech Doc 1", results[0].model.title) // Most similar
    }

    @Test
    fun testFindSimilarWithLimit() = runTest {
        val table = InMemoryTable(serializer = serializer)

        // Insert 10 documents
        val docs = (1..10).map { i ->
            DocumentWithEmbedding(
                title = "Doc $i",
                category = "tech",
                embedding = Embedding.of(1f - i * 0.05f, i * 0.05f, 0f)
            )
        }
        table.insert(docs)

        val queryVector = Embedding.of(1f, 0f, 0f)
        val results = table.findSimilar(
            vectorField = embeddingPath,
            params = VectorSearchParams(
                queryVector = queryVector,
                metric = SimilarityMetric.Cosine,
                limit = 3
            )
        ).toList()

        assertEquals(3, results.size)
    }

    @Test
    fun testFindSimilarEuclidean() = runTest {
        val table = InMemoryTable(serializer = serializer)

        val doc1 = DocumentWithEmbedding(
            title = "Close",
            category = "tech",
            embedding = Embedding.of(1f, 1f, 0f)
        )
        val doc2 = DocumentWithEmbedding(
            title = "Far",
            category = "tech",
            embedding = Embedding.of(10f, 10f, 0f)
        )

        table.insert(listOf(doc1, doc2))

        val queryVector = Embedding.of(0f, 0f, 0f)
        val results = table.findSimilar(
            vectorField = embeddingPath,
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
}
