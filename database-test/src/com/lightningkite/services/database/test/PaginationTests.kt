package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `skip` and `limit`, alone and together.
 *
 * `skip` was previously not exercised anywhere in this suite, which makes offset arithmetic and
 * unstable ordering the two most likely undetected defects in any driver. Every test here sorts on
 * a field with a total order, because paging without one is meaningless — the database is free to
 * return rows in a different order between two calls, and then no page-based assertion can hold.
 */
abstract class PaginationTests {

    abstract val database: Database

    /** Rows with `int` 1..[ROWS]; `int` is a total order over the table. */
    private val ROWS = 10

    private suspend fun seeded(name: String): Table<LargeTestModel> {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>(name))
        collection.insert((1..ROWS).map { LargeTestModel(int = it, long = it.toLong()) })
        return collection
    }

    private val byInt = sort<LargeTestModel> { it.int.ascending() }

    private suspend fun Table<LargeTestModel>.page(skip: Int, limit: Int) =
        find(Condition.Always, byInt, skip = skip, limit = limit).toList().map { it.int }

    @Test
    fun limitCapsTheResultSize() = runTest {
        val collection = seeded("pag_limit")
        assertEquals(listOf(1, 2, 3), collection.page(skip = 0, limit = 3))
    }

    @Test
    fun skipDropsFromTheFront() = runTest {
        val collection = seeded("pag_skip")
        assertEquals((4..ROWS).toList(), collection.page(skip = 3, limit = Int.MAX_VALUE))
    }

    @Test
    fun skipAndLimitSelectAWindow() = runTest {
        val collection = seeded("pag_window")
        assertEquals(listOf(4, 5, 6), collection.page(skip = 3, limit = 3))
    }

    @Test
    fun skipPastTheEndReturnsNothing() = runTest {
        val collection = seeded("pag_skipPastEnd")
        assertEquals(emptyList(), collection.page(skip = ROWS, limit = 5))
        assertEquals(emptyList(), collection.page(skip = ROWS * 2, limit = 5))
    }

    @Test
    fun limitZeroReturnsNothing() = runTest {
        val collection = seeded("pag_limitZero")
        assertEquals(emptyList(), collection.page(skip = 0, limit = 0))
    }

    @Test
    fun limitBeyondTheEndReturnsEverythingRemaining() = runTest {
        val collection = seeded("pag_limitBeyond")
        assertEquals((1..ROWS).toList(), collection.page(skip = 0, limit = ROWS * 5))
        assertEquals(listOf(ROWS), collection.page(skip = ROWS - 1, limit = ROWS * 5))
    }

    @Test
    fun walkingPagesVisitsEveryRowExactlyOnce() = runTest {
        val collection = seeded("pag_walk")
        val pageSize = 3
        val seen = mutableListOf<Int>()
        var skip = 0
        while (true) {
            val page = collection.page(skip = skip, limit = pageSize)
            if (page.isEmpty()) break
            assertTrue(page.size <= pageSize, "a page must never exceed the limit")
            seen += page
            skip += pageSize
        }
        // Every row once, in order: this is what makes offset paging usable at all.
        assertEquals((1..ROWS).toList(), seen)
    }

    @Test
    fun pagingRespectsDescendingOrder() = runTest {
        val collection = seeded("pag_desc")
        val page = collection.find(
            Condition.Always,
            sort { it.int.descending() },
            skip = 2,
            limit = 3,
        ).toList().map { it.int }
        assertEquals(listOf(8, 7, 6), page)
    }

    @Test
    fun pagingAppliesAfterTheCondition() = runTest {
        val collection = seeded("pag_conditioned")
        // Condition first (6 rows), then skip 2, then limit 3.
        val page = collection.find(
            condition { it.int gt 4 },
            byInt,
            skip = 2,
            limit = 3,
        ).toList().map { it.int }
        assertEquals(listOf(7, 8, 9), page)
    }

    @Test
    fun pagingASecondarySortKeyIsStable() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("pag_tiebreak"))
        // Two groups of five sharing `int`, so only `long` can order within a group.
        collection.insert((1..10).map { LargeTestModel(int = if (it <= 5) 1 else 2, long = it.toLong()) })
        val order = sort<LargeTestModel> { it.int.ascending(); it.long.ascending() }

        val all = collection.find(Condition.Always, order).toList().map { it.long }
        assertEquals((1L..10L).toList(), all)

        val pages = (0 until 10 step 3).map { skip ->
            collection.find(Condition.Always, order, skip = skip, limit = 3).toList().map { it.long }
        }
        assertEquals(all, pages.flatten())
    }

    @Test
    fun countIgnoresSkipAndLimit() = runTest {
        val collection = seeded("pag_count")
        // count has no paging arguments; make sure paging a find never leaks into it.
        assertEquals(ROWS, collection.count())
        collection.page(skip = 5, limit = 2)
        assertEquals(ROWS, collection.count())
    }
}
