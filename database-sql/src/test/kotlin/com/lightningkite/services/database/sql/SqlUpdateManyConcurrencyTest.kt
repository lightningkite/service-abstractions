package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for release review finding 7 (BLOCKER): `updateMany`/`updateManyIgnoringResult`
 * fall back to a client-side read-modify-write for any modification that isn't a single pushable SQL
 * `UPDATE` (anything touching a List/Set/Map field), via `findManyInTransaction`. Unlike
 * `findOneInTransaction` (which locks with `forUpdate` and documents why), `findManyInTransaction` did
 * not lock its select, so two concurrent callers could read the same row, each compute its own new
 * value, and one write would be silently lost — the exact class of bug [ConcurrencyTests] exists to
 * catch, except that suite never exercises `updateMany` with a non-scalar modification.
 */
class SqlUpdateManyConcurrencyTest {
    private val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:updateManyConcurrencyTest;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }

    private val concurrency = 16

    private suspend fun <T> inParallel(count: Int, block: suspend (Int) -> T): List<T> =
        withContext(Dispatchers.Default) {
            (0 until count).map { index -> async { block(index) } }.awaitAll()
        }

    @Test
    fun concurrentUpdateManyListAppendsAreAllApplied() = runTest {
        // LargeTestModel has List/Set/Map fields, so it always has child tables, which forces
        // updateMany's read-modify-write fallback (see SqlCollection.updateManyIgnoringResult)
        // regardless of which field this particular modification touches.
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("cmt_listAppend"))
        val model = LargeTestModel(list = listOf())
        collection.insertOne(model)

        inParallel(concurrency) { index ->
            collection.updateManyIgnoringResult(
                condition { it._id eq model._id },
                modification { it.list += index },
            )
        }

        val found = collection.findOne(condition { it._id eq model._id })
        assertEquals(
            concurrency,
            found?.list?.size,
            "every concurrent append must survive; a lower count means an update was lost to an unlocked read-modify-write",
        )
    }
}
