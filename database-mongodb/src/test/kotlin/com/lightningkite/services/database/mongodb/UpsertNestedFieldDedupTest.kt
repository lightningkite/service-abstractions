package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Regression tests for `UpdateWithOptions.upsert`'s handling of modifications on **nested** fields.
 *
 * `upsertOne`/`upsertOneIgnoringResult` try to collapse into a single atomic upsert by attaching a
 * `$setOnInsert` of the fallback model, deduped against whatever `$set`/`$inc` keys the modification
 * dumped. That dedup looks each operator key up in the serialized model with `Document.get`, which
 * does no dotted-path traversal -- so for a nested field (`"embedded.value2"`) the lookup always
 * missed. `$set` degraded gracefully via `==`, but `$inc` force-cast the miss and threw
 * `NullPointerException` (confirmed against real embedded Mongo at bson.kt's `inc?.keys?.forEach`).
 *
 * `upsert` now declines a dotted operator key outright, so all of these take the
 * findOneAndUpdate-then-insertOne path. Every case must insert the fallback model **unmodified** --
 * see `Table.upsertOne`'s KDoc and `InMemoryTable.upsertOne`, which on no match stores `model`, not
 * `modification(model)`.
 */
class UpsertNestedFieldDedupTest {

    @Test
    fun upsertOne_insertBranch_nestedIncrement_insertsModelUnmodified() = runTest {
        val collection = db().prepare(
            DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_UpsertNestedIncrement_upsertOne")
        )
        val fallback = LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 5))
        val modification = modification<LargeTestModel> { it.embedded.value2 += 1 }
        val neverMatches = path<LargeTestModel>()._id eq Uuid.random()

        val result = collection.upsertOne(neverMatches, modification, fallback)
        assertEquals(null, result.old)
        assertEquals(fallback, result.new)
        // The increment must NOT have been applied to the inserted document.
        assertEquals(5, collection.get(fallback._id)!!.embedded.value2)
    }

    @Test
    fun upsertOneIgnoringResult_insertBranch_nestedIncrement_insertsModelUnmodified() = runTest {
        val collection = db().prepare(
            DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_UpsertNestedIncrement_upsertOneIgnoringResult")
        )
        val fallback = LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 5))
        val modification = modification<LargeTestModel> { it.embedded.value2 += 1 }
        val neverMatches = path<LargeTestModel>()._id eq Uuid.random()

        // false == "no existing document matched, so this was an insert".
        assertEquals(false, collection.upsertOneIgnoringResult(neverMatches, modification, fallback))
        assertEquals(fallback, collection.get(fallback._id))
    }

    @Test
    fun upsertOne_insertBranch_nestedSet_insertsModelUnmodified() = runTest {
        val collection = db().prepare(
            DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_UpsertNestedSet_fallsBack")
        )
        val fallback = LargeTestModel(embedded = ClassUsedForEmbedding(value1 = "fallback-default"))
        val modification = modification<LargeTestModel> { it.embedded.value1 assign "x" }
        val neverMatches = path<LargeTestModel>()._id eq Uuid.random()

        val result = collection.upsertOne(neverMatches, modification, fallback)
        assertEquals(null, result.old)
        assertEquals(fallback, result.new)
        assertEquals(fallback, collection.get(fallback._id))
    }

    /**
     * The update branch is unaffected by any of the above -- when a document *does* match, no
     * `$setOnInsert` is built and the nested modification applies through Mongo's operators as
     * normal. Guards against a fix to the insert branch accidentally disabling nested upserts.
     */
    @Test
    fun upsertOne_updateBranch_nestedIncrement_stillApplies() = runTest {
        val collection = db().prepare(
            DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_UpsertNestedIncrement_updateBranch")
        )
        val existing = LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 5))
        collection.insertOne(existing)
        val modification = modification<LargeTestModel> { it.embedded.value2 += 1 }

        val result = collection.upsertOne(
            path<LargeTestModel>()._id eq existing._id,
            modification,
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 99)),
        )
        assertEquals(existing, result.old)
        assertEquals(6, collection.get(existing._id)!!.embedded.value2)
    }

    /**
     * Top-level fields never had the dotted-path problem, so they still take the atomic
     * single-round-trip upsert (`$setOnInsert`) rather than the fallback. Pins that the new
     * dotted-key bail-out didn't over-reach and disable them.
     *
     * The condition here deliberately matches on a non-`_id` field. Writing it as
     * `_id eq Uuid.random()` -- an id different from the one `fallback` carries -- makes this same
     * call fail with `MongoCommandException` error 66 (ImmutableField), because the filter implies
     * an `_id` the `$setOnInsert` then contradicts. That is a separate, pre-existing defect on the
     * atomic path; the fallback path handles it fine, so on Mongo today the call succeeds or fails
     * depending on whether the modification happens to touch a nested field. Reported separately,
     * not fixed here.
     */
    @Test
    fun upsertOne_insertBranch_topLevelIncrement_insertsModelUnmodified() = runTest {
        val collection = db().prepare(
            DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_UpsertTopLevelIncrement")
        )
        val fallback = LargeTestModel(int = 5, string = "no-such-string")
        val modification = modification<LargeTestModel> { it.int += 5 }
        val neverMatches = path<LargeTestModel>().string eq "no-such-string"

        val result = collection.upsertOne(neverMatches, modification, fallback)
        assertEquals(null, result.old)
        assertEquals(fallback, result.new)
        assertEquals(5, collection.get(fallback._id)!!.int)
    }
}
