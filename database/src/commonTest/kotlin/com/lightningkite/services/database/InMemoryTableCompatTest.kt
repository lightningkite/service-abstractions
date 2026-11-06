package com.lightningkite.services.database

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi

/**
 * Verifies the source-compatibility path retained for the 1.x line: the deprecated
 * list-accepting constructor must still compile and seed the (now map-backed) table.
 */
@OptIn(ExperimentalUuidApi::class)
class InMemoryTableCompatTest {

    private val serializer = Note.serializer()

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedListConstructorSeedsTable() = runTest {
        val doc1 = Note("1", "a", "x")
        val doc2 = Note("2", "b", "y")

        // Old call shape: InMemoryTable(mutableListOf(...), serializer)
        val table = InMemoryTable(mutableListOf(doc1, doc2), serializer)

        val all = table.find(Condition.Always).toList()
        assertEquals(setOf(doc1._id, doc2._id), all.map { it._id }.toSet())
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedListIsNotRetainedAsBackingStore() = runTest {
        val seed = mutableListOf(
            Note("1", "a", "x")
        )
        val table = InMemoryTable(seed, serializer)

        // Mutating the original list after construction must not affect the table.
        seed.clear()

        assertEquals(1, table.find(Condition.Always).toList().size)
    }
}
