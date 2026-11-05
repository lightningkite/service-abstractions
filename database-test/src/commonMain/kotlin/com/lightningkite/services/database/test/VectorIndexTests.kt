package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import com.lightningkite.services.embedding.EmbeddingModelId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.math.sqrt
import kotlin.test.*
import kotlin.uuid.Uuid

/** The contract of [VectorDatabase] that every driver must meet. */
abstract class VectorIndexTests {
    abstract val database: VectorDatabase

    /** Waits until a search sees [count] entries, for drivers whose index catches up asynchronously. */
    protected open suspend fun waitForIndex(index: VectorIndex<String>, count: Int) {}

    private val space = VectorSpace(EmbeddingModelId("test-model"), 3)

    private suspend fun index(name: String, vararg entries: VectorIndexEntry<String>): VectorIndex<String> {
        val index = database.prepare(VectorIndexDefinition(name, String.serializer(), space))
        index.delete(index.ids().toList())
        index.upsert(entries.toList())
        waitForIndex(index, entries.size)
        return index
    }

    @Test
    fun searchRanksBySimilarity() = runTest {
        val index = index(
            "vectorRanks",
            VectorIndexEntry("a", Embedding.of(1f, 0f, 0f)),
            VectorIndexEntry("b", Embedding.of(1f, 1f, 0f)),
            VectorIndexEntry("c", Embedding.of(0f, 0f, 1f)),
        )
        val results = index.search(Embedding.of(1f, 0f, 0f), limit = 5)
        assertEquals(listOf("a", "b", "c"), results.map { it.model })
        assertEquals(1f, results[0].score, 0.001f)
        assertEquals((1f + 1f / sqrt(2f)) / 2f, results[1].score, 0.001f, "Scores are (1 + cos) / 2")
        assertEquals(0.5f, results[2].score, 0.001f)
        assertEquals(listOf("a"), index.search(Embedding.of(1f, 0f, 0f), limit = 1).map { it.model })
    }

    @Test
    fun rejectsVectorsOfAnotherSize() = runTest {
        val index = index("vectorSizes")
        assertFailsWith<IllegalArgumentException> { index.upsert(listOf(VectorIndexEntry("a", Embedding.of(1f)))) }
        assertFailsWith<IllegalArgumentException> { index.search(Embedding.of(1f, 0f), limit = 5) }
    }

    @Test
    fun upsertReplacesAndDeleteRemoves() = runTest {
        val index = index("vectorWrites", VectorIndexEntry("a", Embedding.of(1f, 0f, 0f)), VectorIndexEntry("b", Embedding.of(0f, 1f, 0f)))
        index.upsert(listOf(VectorIndexEntry("a", Embedding.of(0f, 0f, 1f))))
        assertEquals(VectorIndexEntry("a", Embedding.of(0f, 0f, 1f)), index.get(listOf("a")).single())
        index.delete(listOf("b"))
        assertEquals(setOf("a"), index.ids().toList().toSet())
        assertEquals(listOf(), index.get(listOf("b")))
    }

    @Test
    fun anotherModelGetsItsOwnIndex() = runTest {
        val name = "vectorSpace${Uuid.random().toHexString().take(8)}"
        val first = database.prepare(VectorIndexDefinition(name, String.serializer(), space))
        first.upsert(listOf(VectorIndexEntry("a", Embedding.of(1f, 0f, 0f))))
        val other = database.prepare(VectorIndexDefinition(name, String.serializer(), space.copy(model = EmbeddingModelId("another-model"))))
        assertEquals(listOf(), other.ids().toList())
        assertEquals(listOf("a"), first.ids().toList())
    }
}
