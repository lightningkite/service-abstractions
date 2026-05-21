package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.LargeTestModel
import com.lightningkite.services.database.test._id
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Verifies that `MongoTable.deleteOne(condition, orderBy)` is atomic at the server.
 *
 * Before the 1.0.0 fix it did a non-atomic `find().limit(1).firstOrNull()` followed by
 * `deleteOne(_id eq ...)`, so two concurrent callers could both observe the same document
 * and both believe they had deleted it (one of the `deleteOne` calls would actually be a
 * no-op, but both callers would return the parsed model and the caller would process the
 * work twice). The fix uses `findOneAndDelete`, which atomically returns the deleted doc.
 *
 * This test inserts a single document, fires N concurrent `deleteOne` calls, and asserts
 * that exactly one caller observes the document while the rest observe `null`.
 *
 * Uses the JVM-only mongo test infrastructure (embedded mongod), so this test is kept in
 * the mongodb module rather than the shared `database-test` common test suite.
 */
@OptIn(ExperimentalUuidApi::class)
class DeleteOneAtomicityTest {

    private val database = TestDatabase.mongoClient

    @Test
    fun deleteOneIsAtomicUnderConcurrency() = runBlocking {
        // Repeat a few times — a non-atomic implementation may occasionally win the race
        // (single doc, fast handoff), but over multiple rounds the race will be exposed.
        repeat(10) { round ->
            val collection = database.table<LargeTestModel>("delete_atomicity_round_$round")
            val model = LargeTestModel(int = round)
            collection.insertOne(model)

            val workers = 16
            val matchById: Condition<LargeTestModel> = condition<LargeTestModel> { it._id eq model._id }
            val results = coroutineScope {
                withContext(Dispatchers.IO) {
                    (1..workers).map {
                        async {
                            collection.deleteOne(matchById)
                        }
                    }.awaitAll()
                }
            }

            val winners = results.count { it != null }
            val nulls = results.count { it == null }
            assertEquals(
                1, winners,
                "Round $round: expected exactly 1 caller to observe the deleted doc, " +
                        "got $winners. Results=$results"
            )
            assertEquals(workers - 1, nulls, "Round $round: wrong number of null results")
            // Winning result must actually be the document we inserted.
            assertEquals(model, results.first { it != null })
            // And it must really be gone.
            assertTrue(collection.get(model._id) == null, "Round $round: document still present after deleteOne")
        }
    }
}
