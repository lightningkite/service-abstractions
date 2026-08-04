package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Contract tests for the six single-row operations: [Table.updateOne], [Table.updateOneIgnoringResult],
 * [Table.replaceOne], [Table.replaceOneIgnoringResult], [Table.deleteOne], [Table.deleteOneIgnoringOld].
 *
 * Every test here runs against a table where **many rows match the condition**. That is the whole
 * point: the rest of the suite works on tables of one or two rows, which cannot distinguish "changed
 * the right row" from "changed every row". Three separate production bugs lived in that blind spot.
 *
 * The properties checked are:
 * - **Cardinality** — exactly one row is affected, never zero and never all of them.
 * - **Selection** — with an `orderBy`, the affected row is the one the sort puts first.
 * - **Twin agreement** — an operation and its `Ignoring` variant pick the same row from the same
 *   state. Only asserted with an explicit `orderBy`, because without one the choice is legitimately
 *   database-dependent.
 * - **Misses** — a condition matching nothing, and [Condition.Never], change nothing and say so.
 */
abstract class SingleRowOperationTests {

    abstract val database: Database

    /** Rows are seeded with `int` running 1..[ROWS], so sorting on `int` gives a known total order. */
    private val ROWS = 5

    private suspend fun seeded(name: String): Table<LargeTestModel> {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>(name))
        collection.insert(seedRows())
        return collection
    }

    private fun seedRows() = (1..ROWS).map { LargeTestModel(int = it, long = it.toLong()) }

    /** Two tables holding identical rows, for comparing an operation against its `Ignoring` twin. */
    private suspend fun twins(name: String): Pair<Table<LargeTestModel>, Table<LargeTestModel>> {
        val rows = seedRows()
        val a = database.prepare(DatabaseTableDefinition<LargeTestModel>("${name}_a"))
        val b = database.prepare(DatabaseTableDefinition<LargeTestModel>("${name}_b"))
        a.insert(rows)
        b.insert(rows)
        return a to b
    }

    private suspend fun Table<LargeTestModel>.all() = find(Condition.Always).toList()

    /** The mark an update-style operation leaves; every seeded row starts `false`. */
    private val mark = modification<LargeTestModel> { it.boolean assign true }

    /** Asserts the table still holds every row and that exactly one of them carries [mark]. */
    private suspend fun Table<LargeTestModel>.assertOneMarked(): LargeTestModel {
        val after = all()
        assertEquals(ROWS, after.size, "row count must not change")
        val marked = after.filter { it.boolean }
        assertEquals(1, marked.size, "expected exactly one row to be modified, got ${marked.map { it.int }}")
        return marked.single()
    }

    /**
     * Asserts exactly one row was removed, and returns the row that is now missing. [before] must be
     * the rows as they actually were, not a freshly built seed — ids are random per row.
     */
    private suspend fun Table<LargeTestModel>.assertOneDeleted(before: List<LargeTestModel>): LargeTestModel {
        val after = all()
        assertEquals(before.size - 1, after.size, "expected exactly one row to be deleted")
        val remaining = after.map { it._id }.toSet()
        return before.single { it._id !in remaining }
    }

    // region cardinality — exactly one row, even though every row matches

    @Test
    fun updateOne_affectsExactlyOneRow() = runTest {
        val collection = seeded("srot_updateOne_cardinality")
        collection.updateOne(Condition.Always, mark)
        collection.assertOneMarked()
    }

    @Test
    fun updateOneIgnoringResult_affectsExactlyOneRow() = runTest {
        val collection = seeded("srot_updateOneIgnoring_cardinality")
        assertTrue(collection.updateOneIgnoringResult(Condition.Always, mark))
        collection.assertOneMarked()
    }

    @Test
    fun deleteOne_affectsExactlyOneRow() = runTest {
        val collection = seeded("srot_deleteOne_cardinality")
        val before = collection.all()
        assertNotNull(collection.deleteOne(Condition.Always))
        collection.assertOneDeleted(before)
    }

    @Test
    fun deleteOneIgnoringOld_affectsExactlyOneRow() = runTest {
        val collection = seeded("srot_deleteOneIgnoring_cardinality")
        val before = collection.all()
        assertTrue(collection.deleteOneIgnoringOld(Condition.Always))
        collection.assertOneDeleted(before)
    }

    // A replacement always keeps the matched row's id. MongoDB rejects an update that would alter
    // `_id` outright, so a replacement carrying a fresh id is not portable — and re-keying a row is
    // not something a caller should express as "replace" anyway. These use `orderBy` to make the
    // target knowable, then assert the cardinality property: one row replaced, the rest untouched.

    @Test
    fun replaceOne_affectsExactlyOneRow() = runTest {
        val collection = seeded("srot_replaceOne_cardinality")
        val before = collection.all()
        val target = before.single { it.int == ROWS }
        collection.replaceOne(Condition.Always, target.copy(string = REPLACED), sort { it.int.descending() })
        collection.assertOnlyTargetReplaced(before, target)
    }

    @Test
    fun replaceOneIgnoringResult_affectsExactlyOneRow() = runTest {
        val collection = seeded("srot_replaceOneIgnoring_cardinality")
        val before = collection.all()
        val target = before.single { it.int == ROWS }
        assertTrue(
            collection.replaceOneIgnoringResult(
                Condition.Always,
                target.copy(string = REPLACED),
                sort { it.int.descending() },
            )
        )
        collection.assertOnlyTargetReplaced(before, target)
    }

    /** Exactly [target] carries the replacement, and every other row is byte-for-byte as it was. */
    private suspend fun Table<LargeTestModel>.assertOnlyTargetReplaced(
        before: List<LargeTestModel>,
        target: LargeTestModel,
    ) {
        val after = all()
        assertEquals(before.size, after.size, "row count must not change")
        assertEquals(listOf(REPLACED), after.filter { it.string == REPLACED }.map { it.string })
        assertEquals(
            before.filter { it._id != target._id }.toSet(),
            after.filter { it._id != target._id }.toSet(),
            "rows other than the replaced one must be untouched",
        )
    }

    // endregion

    // region selection — orderBy decides which row

    @Test
    fun updateOne_orderByDescendingPicksLast() = runTest {
        val collection = seeded("srot_updateOne_desc")
        val change = collection.updateOne(Condition.Always, mark, sort { it.int.descending() })
        assertEquals(ROWS, change.old?.int)
        assertEquals(ROWS, collection.assertOneMarked().int)
    }

    @Test
    fun updateOne_orderByAscendingPicksFirst() = runTest {
        val collection = seeded("srot_updateOne_asc")
        val change = collection.updateOne(Condition.Always, mark, sort { it.int.ascending() })
        assertEquals(1, change.old?.int)
        assertEquals(1, collection.assertOneMarked().int)
    }

    @Test
    fun updateOneIgnoringResult_honorsOrderBy() = runTest {
        val collection = seeded("srot_updateOneIgnoring_desc")
        assertTrue(collection.updateOneIgnoringResult(Condition.Always, mark, sort { it.int.descending() }))
        assertEquals(ROWS, collection.assertOneMarked().int)
    }

    @Test
    fun deleteOne_honorsOrderBy() = runTest {
        val collection = seeded("srot_deleteOne_desc")
        val before = collection.all()
        assertEquals(ROWS, collection.deleteOne(Condition.Always, sort { it.int.descending() })?.int)
        assertEquals(ROWS, collection.assertOneDeleted(before).int)
    }

    @Test
    fun deleteOneIgnoringOld_honorsOrderBy() = runTest {
        val collection = seeded("srot_deleteOneIgnoring_desc")
        val before = collection.all()
        assertTrue(collection.deleteOneIgnoringOld(Condition.Always, sort { it.int.descending() }))
        assertEquals(ROWS, collection.assertOneDeleted(before).int)
    }

    @Test
    fun replaceOne_honorsOrderBy() = runTest {
        val collection = seeded("srot_replaceOne_desc")
        val target = collection.all().single { it.int == ROWS }
        // Keeping the id makes identity unambiguous no matter how a driver treats the id on replace.
        val change = collection.replaceOne(
            Condition.Always,
            target.copy(string = REPLACED),
            sort { it.int.descending() },
        )
        assertEquals(ROWS, change.old?.int)
        assertEquals(listOf(ROWS), collection.all().filter { it.string == REPLACED }.map { it.int })
    }

    @Test
    fun replaceOneIgnoringResult_honorsOrderBy() = runTest {
        val collection = seeded("srot_replaceOneIgnoring_desc")
        val target = collection.all().single { it.int == ROWS }
        assertTrue(
            collection.replaceOneIgnoringResult(
                Condition.Always,
                target.copy(string = REPLACED),
                sort { it.int.descending() },
            )
        )
        assertEquals(listOf(ROWS), collection.all().filter { it.string == REPLACED }.map { it.int })
    }

    @Test
    fun updateOne_orderByBreaksTiesWithSecondKey() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("srot_updateOne_tiebreak"))
        // Every row shares `int`, so the second sort key is the only thing that can decide.
        collection.insert((1..ROWS).map { LargeTestModel(int = 7, long = it.toLong()) })
        collection.updateOne(Condition.Always, mark, sort { it.int.ascending(); it.long.descending() })
        val marked = collection.all().filter { it.boolean }
        assertEquals(1, marked.size)
        assertEquals(ROWS.toLong(), marked.single().long)
    }

    // endregion

    // region twin agreement — an operation and its Ignoring variant behave alike

    @Test
    fun updateOne_agreesWithIgnoringResultTwin() = runTest {
        val (a, b) = twins("srot_twin_update")
        val order = sort<LargeTestModel> { it.long.descending() }
        a.updateOne(Condition.Always, mark, order)
        b.updateOneIgnoringResult(Condition.Always, mark, order)
        assertEquals(a.assertOneMarked()._id, b.assertOneMarked()._id)
    }

    @Test
    fun deleteOne_agreesWithIgnoringOldTwin() = runTest {
        val (a, b) = twins("srot_twin_delete")
        val order = sort<LargeTestModel> { it.long.descending() }
        val before = a.all()
        a.deleteOne(Condition.Always, order)
        b.deleteOneIgnoringOld(Condition.Always, order)
        assertEquals(a.assertOneDeleted(before)._id, b.assertOneDeleted(before)._id)
    }

    @Test
    fun replaceOne_agreesWithIgnoringResultTwin() = runTest {
        val (a, b) = twins("srot_twin_replace")
        val order = sort<LargeTestModel> { it.long.descending() }
        val target = a.all().single { it.long == ROWS.toLong() }
        val replacement = target.copy(string = REPLACED)
        a.replaceOne(Condition.Always, replacement, order)
        b.replaceOneIgnoringResult(Condition.Always, replacement, order)
        assertEquals(
            a.all().filter { it.string == REPLACED }.map { it._id },
            b.all().filter { it.string == REPLACED }.map { it._id },
        )
    }

    // endregion

    // region misses — nothing matches, nothing changes

    @Test
    fun singleRowOps_reportNothingWhenConditionMatchesNothing() = runTest {
        val noMatch = condition<LargeTestModel> { it.int eq 9999 }
        assertNoOpUnderCondition("srot_nomatch", noMatch)
    }

    @Test
    fun singleRowOps_reportNothingUnderConditionNever() = runTest {
        assertNoOpUnderCondition("srot_never", Condition.Never)
    }

    /** Every single-row operation must leave the table untouched and admit it did nothing. */
    private suspend fun assertNoOpUnderCondition(prefix: String, condition: Condition<LargeTestModel>) {
        val before = seedRows()

        suspend fun fresh(suffix: String): Table<LargeTestModel> {
            val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("${prefix}_$suffix"))
            collection.insert(before)
            return collection
        }

        fresh("updateOne").let {
            val change = it.updateOne(condition, mark)
            assertNull(change.old, "updateOne must not report an old row it never found")
            assertEquals(0, it.all().count { row -> row.boolean })
        }
        fresh("updateOneIgnoring").let {
            assertEquals(false, it.updateOneIgnoringResult(condition, mark))
            assertEquals(0, it.all().count { row -> row.boolean })
        }
        fresh("replaceOne").let {
            assertNull(it.replaceOne(condition, LargeTestModel(string = REPLACED)).old)
            assertEquals(0, it.all().count { row -> row.string == REPLACED })
        }
        fresh("replaceOneIgnoring").let {
            assertEquals(false, it.replaceOneIgnoringResult(condition, LargeTestModel(string = REPLACED)))
            assertEquals(0, it.all().count { row -> row.string == REPLACED })
        }
        fresh("deleteOne").let {
            assertNull(it.deleteOne(condition))
            assertEquals(ROWS, it.count())
        }
        fresh("deleteOneIgnoring").let {
            assertEquals(false, it.deleteOneIgnoringOld(condition))
            assertEquals(ROWS, it.count())
        }
    }

    // endregion

    @Test
    fun updateOne_returnsTheRowAsItWasBeforeTheChange() = runTest {
        val collection = seeded("srot_updateOne_oldValue")
        val change = collection.updateOne(
            condition { it.int eq 3 },
            modification { it.string assign "changed" },
        )
        // `old` is an observation of the database and must reflect the pre-change row exactly.
        assertEquals(3, change.old?.int)
        assertEquals("", change.old?.string)
        assertEquals("changed", collection.findOne(condition { it.int eq 3 })?.string)
    }

    private companion object {
        const val REPLACED = "replaced"
    }
}
