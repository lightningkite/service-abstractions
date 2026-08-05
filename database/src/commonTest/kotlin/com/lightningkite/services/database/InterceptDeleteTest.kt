package com.lightningkite.services.database

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A [Table] that reports [stagnantFindResult] from [find] but actually removes and returns
 * [actualDeleteResult] from [deleteOne] -- standing in for a concurrent mutation landing between a
 * find() and a later deleteOne() over the same condition, which is exactly what interceptDelete's
 * old find-then-delete implementation was vulnerable to.
 */
private class DivergingFindAndDeleteTable(
    base: Table<ForwardingTestModel>,
    private val stagnantFindResult: ForwardingTestModel,
    private val actualDeleteResult: ForwardingTestModel,
) : Table<ForwardingTestModel> by base {
    override suspend fun find(
        condition: Condition<ForwardingTestModel>,
        orderBy: List<SortPart<ForwardingTestModel>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<ForwardingTestModel> = flowOf(stagnantFindResult)

    override suspend fun deleteOne(
        condition: Condition<ForwardingTestModel>,
        orderBy: List<SortPart<ForwardingTestModel>>,
    ): ForwardingTestModel? = actualDeleteResult
}

/**
 * FIX 34: interceptDelete's deleteOne/deleteOneIgnoringOld used to do a separate find() and then a
 * separate deleteOne(), with no shared snapshot between them -- so under a multi-match condition with
 * a concurrent mutation in between, onDelete fired for whatever find() saw, not the row that was
 * actually deleted. The fix calls deleteOne() once and reports the row it returns.
 */
class InterceptDeleteTest {

    @Test
    fun deleteOneReportsTheRowActuallyDeletedNotAStaleFindResult() = runTest {
        val staleRow = ForwardingTestModel(order = 1)
        val actuallyDeletedRow = ForwardingTestModel(order = 2)
        val base = InMemoryDatabase("test", context = TestSettingContext())
            .table(DatabaseTableDefinition<ForwardingTestModel>("interceptDelete_deleteOne"))
        val diverging = DivergingFindAndDeleteTable(base, staleRow, actuallyDeletedRow)

        var reported: ForwardingTestModel? = null
        val wrapped = diverging.interceptDelete { reported = it }

        wrapped.deleteOne(Condition.Always)

        assertEquals(
            actuallyDeletedRow,
            reported,
            "onDelete must report the row deleteOne() actually removed, not a separately-fetched find() result",
        )
    }

    @Test
    fun deleteOneIgnoringOldReportsTheRowActuallyDeletedNotAStaleFindResult() = runTest {
        val staleRow = ForwardingTestModel(order = 1)
        val actuallyDeletedRow = ForwardingTestModel(order = 2)
        val base = InMemoryDatabase("test", context = TestSettingContext())
            .table(DatabaseTableDefinition<ForwardingTestModel>("interceptDelete_deleteOneIgnoringOld"))
        val diverging = DivergingFindAndDeleteTable(base, staleRow, actuallyDeletedRow)

        var reported: ForwardingTestModel? = null
        val wrapped = diverging.interceptDelete { reported = it }

        wrapped.deleteOneIgnoringOld(Condition.Always)

        assertEquals(actuallyDeletedRow, reported)
    }
}
