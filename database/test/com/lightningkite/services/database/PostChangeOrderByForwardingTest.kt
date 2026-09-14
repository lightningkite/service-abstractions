package com.lightningkite.services.database

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@GenerateDataClassPaths
@Serializable
data class ForwardingTestModel(
    override val _id: Uuid = Uuid.random(),
    val order: Int = 0,
) : HasId<Uuid> {
    companion object
}

/**
 * FIX 33: postChange and postNewValue's *IgnoringResult overrides for replaceOne/updateOne called the
 * result-returning override without forwarding the caller's orderBy, silently falling back to
 * unordered matching under a multi-match condition. postRawChanges forwards orderBy correctly at its
 * equivalent spots, confirming this was a copy-paste omission rather than intentional.
 */
@OptIn(ExperimentalUuidApi::class)
class PostChangeOrderByForwardingTest {

    /** Records the orderBy passed to each call, delegating everything else to [base]. */
    private class OrderByCapturingTable<Model : Any>(private val base: Table<Model>) : Table<Model> by base {
        var lastReplaceOrderBy: List<SortPart<Model>>? = null
        var lastUpdateOrderBy: List<SortPart<Model>>? = null

        override suspend fun replaceOne(
            condition: Condition<Model>,
            model: Model,
            orderBy: List<SortPart<Model>>,
        ): EntryChange<Model> {
            lastReplaceOrderBy = orderBy
            return base.replaceOne(condition, model, orderBy)
        }

        override suspend fun updateOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            orderBy: List<SortPart<Model>>,
        ): EntryChange<Model> {
            lastUpdateOrderBy = orderBy
            return base.updateOne(condition, modification, orderBy)
        }
    }

    private fun newSpy(): Pair<InMemoryDatabase, OrderByCapturingTable<ForwardingTestModel>> {
        val db = InMemoryDatabase("test", context = TestSettingContext())
        val base = db.table(DatabaseTableDefinition<ForwardingTestModel>("forwarding"))
        return db to OrderByCapturingTable(base)
    }

    @Test
    fun postChangeForwardsOrderByOnIgnoringResultOverrides() = runTest {
        val (_, spy) = newSpy()
        val wrapped = spy.postChange { _, _ -> }
        val order = sort<ForwardingTestModel> { it.order.descending() }

        wrapped.updateOneIgnoringResult(Condition.Always, modification { it.order assign 1 }, order)
        assertEquals(order, spy.lastUpdateOrderBy, "postChange's updateOneIgnoringResult must forward orderBy")

        wrapped.replaceOneIgnoringResult(Condition.Always, ForwardingTestModel(order = 2), order)
        assertEquals(order, spy.lastReplaceOrderBy, "postChange's replaceOneIgnoringResult must forward orderBy")
    }

    @Test
    fun postNewValueForwardsOrderByOnIgnoringResultOverrides() = runTest {
        val (_, spy) = newSpy()
        val wrapped = spy.postNewValue { }
        val order = sort<ForwardingTestModel> { it.order.descending() }

        wrapped.updateOneIgnoringResult(Condition.Always, modification { it.order assign 1 }, order)
        assertEquals(order, spy.lastUpdateOrderBy, "postNewValue's updateOneIgnoringResult must forward orderBy")

        wrapped.replaceOneIgnoringResult(Condition.Always, ForwardingTestModel(order = 2), order)
        assertEquals(order, spy.lastReplaceOrderBy, "postNewValue's replaceOneIgnoringResult must forward orderBy")
    }
}
