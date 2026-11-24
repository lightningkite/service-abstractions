package com.lightningkite.services.database

import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.IndexUniqueness
import com.lightningkite.services.data.Queryable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@Queryable
@IndexSet(fields = ["a", "b"], unique = IndexUniqueness.Unique)
data class UniquePair(override val _id: String, val a: String? = null, val b: Int? = null) : HasId<String>

@Queryable
@IndexSet(fields = ["a", "b"], unique = IndexUniqueness.UniqueNullSparse)
data class SparsePair(override val _id: String, val a: String? = null, val b: Int? = null) : HasId<String>

class InMemoryTableUniqueIndexTest {
    private fun pairs() = InMemoryTable(serializer = UniquePair.serializer())
    private fun sparse() = InMemoryTable(serializer = SparsePair.serializer())

    @Test
    fun duplicateOnInsertIsRejected() = runTest {
        val table = pairs()
        table.insertOne(UniquePair("1", "x", 1))
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("2", "x", 1)) }
        table.insertOne(UniquePair("3", "x", 2))
        assertEquals(setOf("1", "3"), table.all().map { it._id }.toSet())
    }

    @Test
    fun duplicateWithinOneBatchIsRejected() = runTest {
        val table = pairs()
        assertFailsWith<UniqueViolationException> {
            table.insert(listOf(UniquePair("1", "x", 1), UniquePair("2", "x", 1)))
        }
        // Rows before the conflicting one stay, as they always have.
        assertEquals(listOf("1"), table.all().map { it._id })
    }

    @Test
    fun updateIntoConflictIsRejectedAndLeavesNoStaleEntry() = runTest {
        val table = pairs()
        table.insert(listOf(UniquePair("1", "x", 1), UniquePair("2", "y", 1)))
        assertFailsWith<UniqueViolationException> {
            table.updateOne(Condition { it._id eq "2" }, Modification { it.a assign "x" })
        }
        assertEquals(UniquePair("2", "y", 1), table.get("2"))
        // The failed update must not have claimed ("x", 1) for row 2 nor released ("y", 1).
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("3", "y", 1)) }
        table.deleteOne(Condition { it._id eq "1" })
        table.updateOne(Condition { it._id eq "2" }, Modification { it.a assign "x" })
        assertEquals(UniquePair("2", "x", 1), table.get("2"))
    }

    @Test
    fun updateNotTouchingKeyDoesNotConflictWithItself() = runTest {
        val table = pairs()
        table.insertOne(UniquePair("1", "x", 1))
        table.updateOne(Condition { it._id eq "1" }, Modification { it.b assign 1 })
        table.replaceOne(Condition { it._id eq "1" }, UniquePair("1", "x", 1))
        table.upsertOne(Condition { it._id eq "1" }, Modification { it.a assign "x" }, UniquePair("1", "x", 1))
        assertEquals(1, table.count())
    }

    @Test
    fun keyFreedByUpdateCanBeTaken() = runTest {
        val table = pairs()
        table.insertOne(UniquePair("1", "x", 1))
        table.updateOne(Condition { it._id eq "1" }, Modification { it.b assign 2 })
        table.insertOne(UniquePair("2", "x", 1))
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("3", "x", 2)) }
    }

    @Test
    fun keyFreedByReplaceWithNewIdCanBeTaken() = runTest {
        val table = pairs()
        table.insertOne(UniquePair("1", "x", 1))
        table.replaceOne(Condition { it._id eq "1" }, UniquePair("9", "x", 2))
        table.insertOne(UniquePair("2", "x", 1))
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("3", "x", 2)) }
    }

    @Test
    fun updateManyIntoEachOtherIsRejected() = runTest {
        val table = pairs()
        table.insert(listOf(UniquePair("1", "x", 1), UniquePair("2", "x", 2)))
        assertFailsWith<UniqueViolationException> {
            table.updateMany(Condition.Always, Modification { it.b assign 3 })
        }
    }

    @Test
    fun deleteFreesTheKey() = runTest {
        val table = pairs()
        table.insert(listOf(UniquePair("1", "x", 1), UniquePair("2", "y", 1)))
        table.deleteOne(Condition { it._id eq "1" })
        table.insertOne(UniquePair("3", "x", 1))
        table.deleteMany(Condition.Always)
        table.insert(listOf(UniquePair("4", "x", 1), UniquePair("5", "y", 1)))
        table.drop()
        table.insertOne(UniquePair("6", "x", 1))
    }

    @Test
    fun uniqueTreatsNullAsAValue() = runTest {
        val table = pairs()
        table.insertOne(UniquePair("1", null, 1))
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("2", null, 1)) }
        table.insertOne(UniquePair("3", null, null))
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("4", null, null)) }
    }

    @Test
    fun sparseExemptsRowsWithAnyNullKeyValue() = runTest {
        val table = sparse()
        table.insert(listOf(SparsePair("1", null, 1), SparsePair("2", null, 1)))
        table.insert(listOf(SparsePair("3", "x", null), SparsePair("4", "x", null)))
        table.insert(listOf(SparsePair("5", null, null), SparsePair("6", null, null)))
        table.insertOne(SparsePair("7", "x", 1))
        assertFailsWith<UniqueViolationException> { table.insertOne(SparsePair("8", "x", 1)) }
        // Moving a row from a null key onto a taken one is still a conflict.
        assertFailsWith<UniqueViolationException> {
            table.updateOne(Condition { it._id eq "3" }, Modification { it.b assign 1 })
        }
    }

    @Test
    fun preloadReplacingARowReleasesItsOldKey() = runTest {
        val table = pairs()
        table.insertOne(UniquePair("1", "x", 1))
        table.preload(listOf(UniquePair("1", "x", 2)))
        table.insertOne(UniquePair("2", "x", 1))
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("3", "x", 2)) }
    }

    @Test
    fun initialDataIsIndexed() = runTest {
        val table = InMemoryTable(hashMapOf<Any?, UniquePair>("1" to UniquePair("1", "x", 1)), UniquePair.serializer())
        assertFailsWith<UniqueViolationException> { table.insertOne(UniquePair("2", "x", 1)) }
    }

    private suspend fun InMemoryTable<UniquePair>.all() = find(Condition.Always).toList()
}
