package com.lightningkite.services.database

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

class InterceptChangePerInstanceUpsertTest {

    private class Setup {
        val base = InMemoryDatabase("test", context = TestSettingContext())
            .table(DatabaseTableDefinition<ForwardingTestModel>("intercept-upsert"))
        val seen = mutableListOf<ForwardingTestModel>()

        // Records the instance it's given and raises order by 100, so whether it ran shows in what's stored.
        val table = base.interceptChangePerInstance { current, modification ->
            seen += current
            Modification.Chain(listOf(modification, modification { it.order += 100 }))
        }
    }

    @Test
    fun upsertOneThatInsertsIsIntercepted() = runTest {
        val s = Setup()
        val model = ForwardingTestModel(order = 1)

        val result = s.table.upsertOneById(model._id, model)

        assertEquals(listOf(model), s.seen, "the interceptor gets the inserted model, as with insert")
        assertEquals(101, result.new?.order)
        assertEquals(101, s.base.get(model._id)?.order)
    }

    @Test
    fun upsertOneIgnoringResultThatInsertsIsIntercepted() = runTest {
        val s = Setup()
        val model = ForwardingTestModel(order = 1)

        s.table.upsertOneIgnoringResult(
            condition { it._id eq model._id },
            Modification.Assign(model),
            model,
        )

        assertEquals(listOf(model), s.seen)
        assertEquals(101, s.base.get(model._id)?.order)
    }

    @Test
    fun upsertOneThatUpdatesIsStillInterceptedWithTheCurrentInstance() = runTest {
        val s = Setup()
        val existing = s.base.insertOne(ForwardingTestModel(order = 1))
        val replacement = existing.copy(order = 2)

        s.table.upsertOneById(existing._id, replacement)

        assertEquals(listOf(existing), s.seen)
        assertEquals(102, s.base.get(existing._id)?.order)
    }
}
