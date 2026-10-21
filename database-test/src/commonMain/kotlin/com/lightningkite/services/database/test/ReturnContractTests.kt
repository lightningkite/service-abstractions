package com.lightningkite.services.database.test

import com.lightningkite.services.database.optics.Lens
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Cross-checks what each operation *reports* against what the database actually *holds*.
 *
 * A driver that returns a plausible count or a plausible row while having done something else
 * entirely will pass most of the rest of the suite. These tests close that gap by never trusting a
 * return value on its own: every reported result is compared against a read-back of the table.
 *
 * The `count` versus `find` agreement test is worth calling out — it drives the same condition
 * through two independent code paths in every driver, so a condition that compiles differently for
 * counting than for selecting shows up immediately.
 */
abstract class ReturnContractTests {

    abstract val database: Database

    private suspend fun table(name: String) =
        database.prepare(DatabaseTableDefinition<LargeTestModel>(name))

    private suspend fun Table<LargeTestModel>.all() = find(Condition.Always).toList()

    @Test
    fun insert_returnsWhatWasStored() = runTest {
        val collection = table("rct_insert")
        val models = (1..5).map { LargeTestModel(int = it, string = "row$it") }
        val returned = collection.insert(models)
        assertEquals(models.size, returned.size)
        // Comparing as sets: insert makes no promise about the order it hands rows back.
        assertEquals(models.toSet(), returned.toSet())
        assertEquals(models.toSet(), collection.all().toSet())
    }

    @Test
    fun updateOne_oldIsPreStateAndNewMatchesReadBack() = runTest {
        val collection = table("rct_updateOne")
        val original = LargeTestModel(int = 1, string = "before")
        collection.insert(listOf(original))

        val change = collection.updateOne(
            Condition { it.int eq 1 },
            Modification { it.string assign "after" },
        )

        assertEquals(original, change.old)
        // Uncontended, the client-side prediction must agree with what actually landed.
        assertEquals(change.new, collection.findOne(Condition { it.int eq 1 }))
    }

    @Test
    fun updateMany_reportsOneChangePerAffectedRow() = runTest {
        val collection = table("rct_updateMany")
        collection.insert((1..6).map { LargeTestModel(int = it) })

        val matching = Condition<LargeTestModel> { it.int gt 3 }
        val before = collection.find(matching).toList().toSet()
        val changes = collection.updateMany(matching, Modification { it.boolean assign true })

        assertEquals(3, changes.changes.size)
        assertEquals(before, changes.changes.map { it.old!! }.toSet())
        assertEquals(3, collection.all().count { it.boolean })
        // Rows outside the condition must be untouched.
        assertEquals(3, collection.all().count { !it.boolean })
    }

    @Test
    fun updateManyIgnoringResult_countAgreesWithUpdateMany() = runTest {
        val a = table("rct_updateManyCount_a")
        val b = table("rct_updateManyCount_b")
        val rows = (1..6).map { LargeTestModel(int = it) }
        a.insert(rows)
        b.insert(rows)

        val matching = Condition<LargeTestModel> { it.int gt 3 }
        val mark = Modification<LargeTestModel> { it.boolean assign true }
        assertEquals(a.updateMany(matching, mark).changes.size, b.updateManyIgnoringResult(matching, mark))
    }

    @Test
    fun deleteMany_returnsExactlyTheRowsThatVanished() = runTest {
        val collection = table("rct_deleteMany")
        collection.insert((1..6).map { LargeTestModel(int = it) })

        val before = collection.all().toSet()
        val deleted = collection.deleteMany(Condition { it.int gt 3 }).toSet()
        val after = collection.all().toSet()

        assertEquals(3, deleted.size)
        assertEquals(before - after, deleted, "the returned rows must be exactly the rows that disappeared")
        assertEquals(before - deleted, after)
    }

    @Test
    fun deleteManyIgnoringOld_countAgreesWithDeleteMany() = runTest {
        val a = table("rct_deleteManyCount_a")
        val b = table("rct_deleteManyCount_b")
        val rows = (1..6).map { LargeTestModel(int = it) }
        a.insert(rows)
        b.insert(rows)

        val matching = Condition<LargeTestModel> { it.int gt 3 }
        assertEquals(a.deleteMany(matching).size, b.deleteManyIgnoringOld(matching))
    }

    @Test
    fun upsertOne_insertPathReportsNoOldRow() = runTest {
        val collection = table("rct_upsertInsert")
        val model = LargeTestModel(int = 1)

        val result = collection.upsertOne(Condition { it._id eq model._id }, Modification { it.boolean assign true }, model)

        assertNull(result.old, "nothing existed, so there is no old row to report")
        assertEquals(model, result.new)
        assertEquals(model, collection.findOne(Condition { it._id eq model._id }))
    }

    @Test
    fun upsertOne_updatePathReportsTheExistingRow() = runTest {
        val collection = table("rct_upsertUpdate")
        val model = LargeTestModel(int = 1)
        collection.insert(listOf(model))

        val result = collection.upsertOne(
            Condition { it._id eq model._id },
            Modification { it.boolean assign true },
            LargeTestModel(int = 99),
        )

        assertEquals(model, result.old)
        assertEquals(result.new, collection.findOne(Condition { it._id eq model._id }))
        assertEquals(1, collection.count(), "the update path must not also insert")
    }

    @Test
    fun upsertOneIgnoringResult_reportsWhetherARowExisted() = runTest {
        val collection = table("rct_upsertIgnoring")
        val model = LargeTestModel(int = 1)
        val mark = Modification<LargeTestModel> { it.boolean assign true }

        assertEquals(false, collection.upsertOneIgnoringResult(Condition { it._id eq model._id }, mark, model))
        assertEquals(true, collection.upsertOneIgnoringResult(Condition { it._id eq model._id }, mark, model))
        assertEquals(1, collection.count())
    }

    @Test
    fun count_agreesWithFind() = runTest {
        val collection = table("rct_countAgreement")
        collection.insert(
            (1..10).map {
                LargeTestModel(
                    int = it,
                    string = "row${it % 3}",
                    boolean = it % 2 == 0,
                    intNullable = if (it % 4 == 0) null else it,
                )
            }
        )

        val conditions = listOf<Condition<LargeTestModel>>(
            Condition.Always,
            Condition.Never,
            Condition<LargeTestModel> { it.int gt 5 },
            Condition<LargeTestModel> { it.int lte 5 },
            Condition<LargeTestModel> { it.boolean eq true },
            Condition<LargeTestModel> { it.string eq "row1" },
            Condition<LargeTestModel> { it.intNullable eq null },
            Condition<LargeTestModel> { (it.int gt 2) and (it.int lt 8) },
            Condition<LargeTestModel> { (it.int lt 2) or (it.int gt 8) },
        )
        for (c in conditions) {
            assertEquals(
                collection.find(c).toList().size,
                collection.count(c),
                "count and find disagree for $c",
            )
        }
    }

    @Test
    fun count_reflectsWritesImmediately() = runTest {
        val collection = table("rct_countAfterWrites")
        collection.insert((1..5).map { LargeTestModel(int = it) })
        assertEquals(5, collection.count())

        collection.deleteOne(Condition { it.int eq 1 })
        assertEquals(4, collection.count())

        collection.deleteMany(Condition { it.int gt 3 })
        assertEquals(2, collection.count())

        collection.insert(listOf(LargeTestModel(int = 100)))
        assertEquals(3, collection.count())
        assertEquals(collection.all().size, collection.count())
    }

    @Test
    fun replaceOne_reportsOldAndLeavesReplacementInPlace() = runTest {
        val collection = table("rct_replaceOne")
        val original = LargeTestModel(int = 1, string = "before")
        collection.insert(listOf(original))

        val replacement = original.copy(string = "after")
        val change = collection.replaceOne(Condition { it.int eq 1 }, replacement)

        assertEquals(original, change.old)
        assertEquals(replacement, change.new)
        assertEquals(listOf(replacement), collection.all())
    }

    @Test
    fun findPartial_agreesWithFindOnTheSelectedFields() = runTest {
        val collection = table("rct_findPartial")
        collection.insert((1..5).map { LargeTestModel(int = it, string = "row$it") })

        val matching = Condition<LargeTestModel> { it.int gt 2 }
        val partials = collection.findPartial(setOf(Lens<LargeTestModel>().int), matching).toList()
        val full = collection.find(matching).toList()

        assertEquals(full.size, partials.size)
        assertEquals(full.map { row -> partialOf<LargeTestModel> { it.int assign row.int } }.toSet(), partials.toSet())
    }

    @Test
    fun deleteOne_returnedRowIsGoneAndTheRestRemain() = runTest {
        val collection = table("rct_deleteOneConsistency")
        collection.insert((1..5).map { LargeTestModel(int = it) })

        val before = collection.all().toSet()
        val deleted = assertNotNull(collection.deleteOne(Condition.Always))
        val after = collection.all().toSet()

        assertEquals(before - setOf(deleted), after, "exactly the returned row must be missing")
    }
}
