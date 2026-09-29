package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

abstract class FullTextSearchTests {

    abstract val database: Database

    private suspend fun table(name: String, vararg models: LargeTestModel): Table<LargeTestModel> {
        val table = database.prepare(DatabaseTableDefinition<LargeTestModel>(name))
        table.deleteMany(Condition.Always)
        table.insert(models.toList())
        return table
    }

    private suspend fun Table<LargeTestModel>.searchStrings(
        query: String,
        condition: Condition<LargeTestModel> = Condition.Always,
    ): List<String> = fullTextSearch(query, condition).toList().map { it.model.string }

    @Test
    fun requiresEveryTerm() = runTest {
        val table = table(
            "fts_requiresEveryTerm",
            LargeTestModel(string = "zebra walrus"),
            LargeTestModel(string = "zebra giraffe"),
            LargeTestModel(string = "walrus giraffe"),
        )
        assertEquals(listOf("zebra walrus"), table.searchStrings("zebra walrus"))
        assertEquals(setOf("zebra walrus", "zebra giraffe"), table.searchStrings("zebra").toSet())
    }

    @Test
    fun excludesRejectedTerms() = runTest {
        val table = table(
            "fts_excludesRejectedTerms",
            LargeTestModel(string = "zebra walrus"),
            LargeTestModel(string = "zebra giraffe"),
        )
        assertEquals(listOf("zebra giraffe"), table.searchStrings("zebra -walrus"))
    }

    @Test
    fun onlySearchesTextIndexFields() = runTest {
        val table = table(
            "fts_onlySearchesTextIndexFields",
            LargeTestModel(string = "zebra"),
            LargeTestModel(string = "other", embedded = ClassUsedForEmbedding(value1 = "zebra")),
            LargeTestModel(string = "unindexed", stringNullable = "zebra"),
        )
        assertEquals(setOf("zebra", "other"), table.searchStrings("zebra").toSet())
    }

    @Test
    fun appliesCondition() = runTest {
        val table = table(
            "fts_appliesCondition",
            LargeTestModel(string = "zebra one", int = 1),
            LargeTestModel(string = "zebra two", int = 2),
        )
        assertEquals(
            listOf("zebra two"),
            table.searchStrings("zebra", condition<LargeTestModel> { it.int eq 2 })
        )
    }

    @Test
    fun ranksMoreRelevantFirst() = runTest {
        val table = table(
            "fts_ranksMoreRelevantFirst",
            LargeTestModel(string = "zebra", embedded = ClassUsedForEmbedding(value1 = "unrelated")),
            LargeTestModel(string = "zebra herd", embedded = ClassUsedForEmbedding(value1 = "zebra")),
        )
        val results = table.fullTextSearch("zebra").toList()
        assertEquals(listOf("zebra herd", "zebra"), results.map { it.model.string })
        assertTrue(results[0].score > results[1].score)
    }

    @Test
    fun excludesRowsWhoseTextFieldsAreMasked() = runTest {
        val table = table(
            "fts_excludesRowsWhoseTextFieldsAreMasked",
            LargeTestModel(string = "readable", embedded = ClassUsedForEmbedding(value1 = "zebra"), int = 1),
            LargeTestModel(string = "masked", embedded = ClassUsedForEmbedding(value1 = "zebra"), int = 2),
        ).withPermissions(
            ModelPermissions(
                read = Condition.Always,
                readMask = mask { it.embedded.value1.mask("", unless = it.int eq 1) },
            )
        )
        assertEquals(listOf("readable"), table.searchStrings("zebra"))
    }

    @Test
    fun pagesInRelevanceOrder() = runTest {
        val table = table(
            "fts_pagesInRelevanceOrder",
            LargeTestModel(string = "zebra", embedded = ClassUsedForEmbedding(value1 = "unrelated")),
            LargeTestModel(string = "zebra herd", embedded = ClassUsedForEmbedding(value1 = "zebra")),
        )
        assertEquals(listOf("zebra herd"), table.fullTextSearch("zebra", limit = 1).toList().map { it.model.string })
        assertEquals(listOf("zebra"), table.fullTextSearch("zebra", skip = 1).toList().map { it.model.string })
    }
}
