package com.lightningkite.services.database

import com.lightningkite.services.TestSettingContext
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/**
 * FIX 11: [InMemoryDatabase.table] used to be backed by a plain `HashMap` with `getOrPut`, which is
 * not atomic under concurrent access. Two threads racing to access the same table for the first time
 * could each construct their own [InMemoryTable] with its own backing map; whichever call's map won
 * the final `HashMap` write silently discarded the other's table (and any writes already routed
 * through it). This uses real OS threads -- not coroutines on a shared dispatcher -- because the race
 * is on the synchronous, non-suspending [Database.table] call itself.
 */
@OptIn(ExperimentalUuidApi::class)
class InMemoryDatabaseConcurrencyTest {

    @Test
    fun concurrentFirstAccessYieldsTheSameTableInstance() {
        val db = InMemoryDatabase("test", context = TestSettingContext())
        val tableDef = DatabaseTableDefinition<DocumentWithEmbedding>("concurrent-first-access")

        val threadCount = 32
        val barrier = CyclicBarrier(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val tables = pool.invokeAll((0 until threadCount).map {
                Callable {
                    barrier.await() // line every thread up so they all call table() at once
                    db.table(tableDef)
                }
            }).map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(
                1,
                tables.toSet().size,
                "every caller must be handed the same InMemoryTable instance for the same table definition",
            )
        } finally {
            pool.shutdown()
        }
    }
}
