package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces the row-level atomicity that [Table] guarantees.
 *
 * There is deliberately no capability flag here: row-level atomicity is a contract every
 * implementation must meet, not a feature some may decline. A driver that reads a row, applies a
 * modification in memory, and writes it back must hold a lock across that sequence — otherwise two
 * callers read the same row, each computes its own result, and one caller's write is silently lost.
 *
 * Assertions only ever look at [EntryChange.old] and at the final state of the table.
 * [EntryChange.new] is computed on the client and is a prediction, so under contention it is not
 * something a correct driver can be held to.
 */
abstract class ConcurrencyTests {

    abstract val database: Database

    /**
     * Concurrent callers per test. Kept modest so the suite stays quick while still being wide
     * enough that an unsynchronized read-modify-write loses at least one write in practice.
     */
    protected open val concurrency: Int = 16

    /**
     * Runs [block] once per index, genuinely in parallel.
     *
     * `runTest` uses a single-threaded scheduler that would serialize everything and make these
     * tests pass vacuously, so the work is moved onto a real dispatcher.
     */
    private suspend fun <T> inParallel(count: Int, block: suspend (Int) -> T): List<T> =
        withContext(Dispatchers.Default) {
            (0 until count).map { index -> async { block(index) } }.awaitAll()
        }

    @Test
    fun concurrentIncrementsAreAllApplied() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("cct_increment"))
        val model = LargeTestModel(int = 0)
        collection.insert(listOf(model))

        inParallel(concurrency) {
            collection.updateOneIgnoringResult(
                condition { it._id eq model._id },
                modification { it.int plusAssign 1 },
            )
        }

        assertEquals(
            concurrency,
            collection.findOne(condition { it._id eq model._id })?.int,
            "every increment must survive; a lower value means an update was lost",
        )
    }

    @Test
    fun concurrentUpdateOneEachSeeADistinctOldValue() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("cct_distinctOld"))
        val model = LargeTestModel(int = 0)
        collection.insert(listOf(model))

        val olds = inParallel(concurrency) {
            collection.updateOne(
                condition { it._id eq model._id },
                modification { it.int plusAssign 1 },
            ).old?.int
        }

        // updateOne reads and writes atomically, so the values observed must be 0..n-1 with no
        // repeats — two callers seeing the same old value means they raced on the read.
        assertEquals((0 until concurrency).toList(), olds.filterNotNull().sorted())
    }

    @Test
    fun concurrentDeleteOneHandsEachRowToExactlyOneCaller() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("cct_claim"))
        val rows = (1..concurrency).map { LargeTestModel(int = it) }
        collection.insert(rows)

        // Every caller claims one row; used as a work queue, so a row handed out twice is a bug.
        val claimed = inParallel(concurrency) { collection.deleteOne(Condition.Always) }.filterNotNull()

        assertEquals(concurrency, claimed.size, "every caller should have claimed a row")
        assertEquals(concurrency, claimed.map { it._id }.toSet().size, "no row may be claimed twice")
        assertEquals(0, collection.count())
    }

    @Test
    fun concurrentDeleteOneOverAShortQueueNeverOverDelivers() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("cct_shortQueue"))
        val available = concurrency / 2
        collection.insert((1..available).map { LargeTestModel(int = it) })

        // More callers than rows: the surplus must come away empty-handed, not with a duplicate.
        val claimed = inParallel(concurrency) { collection.deleteOne(Condition.Always) }.filterNotNull()

        assertEquals(available, claimed.size)
        assertEquals(available, claimed.map { it._id }.toSet().size, "no row may be claimed twice")
        assertEquals(0, collection.count())
    }

    @Test
    fun concurrentCompareAndSwapHasExactlyOneWinner() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("cct_cas"))
        val model = LargeTestModel(int = 0, boolean = false)
        collection.insert(listOf(model))

        // The classic use of updateOne: fold the expected state into the condition, and a non-null
        // `old` means this caller is the one that made the transition.
        val winners = inParallel(concurrency) {
            collection.updateOne(
                condition { (it._id eq model._id) and (it.boolean eq false) },
                modification { it.boolean assign true },
            ).old
        }.filterNotNull()

        assertEquals(1, winners.size, "exactly one caller may win the transition")
        assertTrue(collection.findOne(condition { it._id eq model._id })!!.boolean)
    }

    @Test
    fun concurrentDeletesAndUpdatesLeaveConsistentState() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("cct_mixed"))
        val rows = (1..concurrency).map { LargeTestModel(int = it) }
        collection.insert(rows)

        // Deleters and updaters racing over the same rows: whatever interleaving happens, no row may
        // end up half-written, and a row reported deleted must actually be gone.
        val deleted = inParallel(concurrency) { index ->
            if (index % 2 == 0) {
                collection.deleteOne(condition { it.int eq index + 1 })?._id
            } else {
                collection.updateOneIgnoringResult(
                    condition { it.int eq index + 1 },
                    modification { it.string assign "touched" },
                )
                null
            }
        }.filterNotNull().toSet()

        val remaining = collection.find(Condition.Always).toList()
        assertEquals(emptySet(), remaining.map { it._id }.toSet() intersect deleted)
        assertEquals(rows.size - deleted.size, remaining.size)
    }
}
