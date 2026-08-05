package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for FIX 4: `Condition.SetAllElements` translated a compound (And/Or) inner condition
 * without the De Morgan negation that `ListAllElements` correctly applies, producing an always-true (or
 * always-false) Mongo query instead of a real per-element check. Modeled on
 * `ConditionTests.test_List_all_equal_and_string_equal`, the closest existing compound-condition test for
 * the sibling `ListAllElements`, applied to `set.all { ... }` instead.
 */
class SetAllElementsCompoundTest {
    @Test
    fun test_Set_all_equal_and_string_equal() = runTest {
        val collection = db().prepare(
            DatabaseTableDefinition<LargeTestModel>("LargeTestModel_test_Set_all_equal_and_string_equal")
        )

        val allMatch = LargeTestModel(
            setEmbedded = setOf(
                ClassUsedForEmbedding(value1 = "one", value2 = 5),
            )
        )

        val oneWrong = LargeTestModel(
            setEmbedded = setOf(
                ClassUsedForEmbedding(value1 = "one", value2 = 5),
                ClassUsedForEmbedding(value1 = "two", value2 = 5),
            )
        )

        val bothWrong = LargeTestModel(
            setEmbedded = setOf(
                ClassUsedForEmbedding(value1 = "two", value2 = 1),
                ClassUsedForEmbedding(value1 = "three", value2 = 2),
            )
        )

        val manualList = listOf(allMatch, oneWrong, bothWrong)
        collection.insertOne(allMatch)
        collection.insertOne(oneWrong)
        collection.insertOne(bothWrong)

        val condition = path<LargeTestModel>().setEmbedded.all {
            (it.value2 eq 5) and (it.value1 eq "one")
        }

        val results = collection.find(condition).toList()

        assertContains(results, allMatch, "Should find the document where every element satisfies both conditions")
        assertTrue(oneWrong !in results, "Should NOT find a document with one element that fails the compound condition")
        assertTrue(bothWrong !in results, "Should NOT find a document where all elements fail")

        val expected = manualList
            .filter { model -> model.setEmbedded.all { it.value2 == 5 && it.value1 == "one" } }
            .sortedBy { it._id }

        assertEquals(expected, results.sortedBy { it._id })
    }
}
