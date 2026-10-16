package com.lightningkite.services.database.test

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * The contract of [Table.findByTextIndex] that every backend supporting text search must meet.
 */
abstract class TextSearchTests {

    abstract val database: Database

    /** Whether a word with a typo or two still matches, e.g. `kotlinn` finding `kotlin`. */
    open val supportsTypoTolerance: Boolean = true

    /** Called after inserting, for backends whose search index catches up asynchronously. */
    protected open suspend fun waitForIndex() {}

    private suspend fun table(name: String, vararg models: TextSearchTestModel): Table<TextSearchTestModel> {
        val table = database.prepare(DatabaseTableDefinition<TextSearchTestModel>(name))
        table.deleteManyIgnoringOld(Condition.Always)
        table.insert(models.toList())
        waitForIndex()
        return table
    }

    private suspend fun Table<TextSearchTestModel>.titles(query: String): Set<String> =
        findByTextIndex(query).toList().map { it.model.title }.toSet()

    @Test
    fun searchesEveryIndexedField() = runTest {
        val table = table(
            "textSearchIndexedFields",
            TextSearchTestModel(title = "Learning Kotlin"),
            TextSearchTestModel(title = "In the body", body = "A guide to programming"),
            TextSearchTestModel(title = "In the summary", details = TextSearchDetails(summary = "Electronics catalog")),
        )
        assertEquals(setOf("Learning Kotlin"), table.titles("kotlin"))
        assertEquals(setOf("In the body"), table.titles("programming"))
        assertEquals(setOf("In the summary"), table.titles("electronics"))
    }

    @Test
    fun ignoresFieldsOutsideTheTextIndex() = runTest {
        val table = table(
            "textSearchUnindexed",
            TextSearchTestModel(title = "Public", author = "Zebediah", details = TextSearchDetails(secret = "Confidential")),
        )
        assertEquals(setOf(), table.titles("zebediah"))
        assertEquals(setOf(), table.titles("confidential"))
    }

    @Test
    fun requiresEveryWord() = runTest {
        val table = table(
            "textSearchEveryWord",
            TextSearchTestModel(title = "Learning Kotlin", body = "A guide to programming"),
            TextSearchTestModel(title = "Learning Java"),
        )
        assertEquals(setOf("Learning Kotlin"), table.titles("kotlin programming"))
        assertEquals(setOf(), table.titles("kotlin java"))
    }

    @Test
    fun ignoresCase() = runTest {
        val table = table("textSearchCase", TextSearchTestModel(title = "learning kotlin"))
        assertEquals(setOf("learning kotlin"), table.titles("KOTLIN"))
    }

    @Test
    fun matchesWholeWordsOnly() = runTest {
        val table = table("textSearchWholeWords", TextSearchTestModel(title = "Concatenate strings"))
        assertEquals(setOf(), table.titles("cat"))
    }

    @Test
    fun matchesQuotedPhrasesExactly() = runTest {
        val table = table(
            "textSearchPhrase",
            TextSearchTestModel(title = "Type safe queries"),
            TextSearchTestModel(title = "Safe type queries"),
        )
        assertEquals(setOf("Type safe queries"), table.titles("\"type safe\""))
    }

    @Test
    fun excludesMinusWords() = runTest {
        val table = table(
            "textSearchExclude",
            TextSearchTestModel(title = "Kotlin for Java developers"),
            TextSearchTestModel(title = "Kotlin coroutines"),
        )
        assertEquals(setOf("Kotlin coroutines"), table.titles("kotlin -java"))
    }

    @Test
    fun queryWithoutWordsReturnsNothing() = runTest {
        val table = table("textSearchEmpty", TextSearchTestModel(title = "Kotlin"))
        assertEquals(setOf(), table.titles(""))
        assertEquals(setOf(), table.titles("-java"))
    }

    @Test
    fun appliesCondition() = runTest {
        val table = table(
            "textSearchCondition",
            TextSearchTestModel(title = "Kotlin A", category = "a"),
            TextSearchTestModel(title = "Kotlin B", category = "b"),
        )
        val results = table.findByTextIndex("kotlin", condition { it.category eq "b" }).toList()
        assertEquals(listOf("Kotlin B"), results.map { it.model.title })
    }

    @Test
    fun ordersByScoreAndPages() = runTest {
        val table = table("textSearchPaging", *Array(5) { TextSearchTestModel(title = "Kotlin $it", body = "kotlin ".repeat(it)) })
        val all = table.findByTextIndex("kotlin").toList()
        assertEquals(5, all.size)
        assertEquals(all.sortedByDescending { it.score }.map { it.score }, all.map { it.score })
        assertEquals(all.drop(1).take(2).map { it.score }, table.findByTextIndex("kotlin", skip = 1, limit = 2).toList().map { it.score })
    }

    @Test
    fun throwsWithoutTextIndex() = runTest {
        val table = database.prepare(DatabaseTableDefinition<VectorTestModel>("textSearchNoIndex"))
        assertFailsWith<IllegalStateException> { table.findByTextIndex("anything").toList() }
    }

    @Test
    fun skipsRowsWhoseTextFieldsAreMasked() = runTest {
        val raw = database.prepare(DatabaseTableDefinition<LargeTestModel>("textSearchMasked"))
        raw.deleteManyIgnoringOld(Condition.Always)
        raw.insert(listOf(LargeTestModel(string = "secret plan", byte = 0), LargeTestModel(string = "secret plan", byte = 1)))
        waitForIndex()
        val table = raw.withPermissions(
            ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> { it.string.mask("", unless = condition<LargeTestModel> { it.byte eq 1 }) },
            )
        )
        assertEquals(listOf<Byte>(1), table.findByTextIndex("secret").toList().map { it.model.byte })
    }

    @Test
    fun toleratesTyposScaledToWordLength() = runTest {
        if (!supportsTypoTolerance) return@runTest
        val table = table(
            "textSearchTypos",
            TextSearchTestModel(title = "Learning Kotlin"),
            TextSearchTestModel(title = "Java basics"),
        )
        assertEquals(setOf("Learning Kotlin"), table.titles("kotlinn"))
        assertEquals(setOf(), table.titles("kotxxn"), "two edits is too many for a six letter word")
        assertEquals(setOf(), table.titles("jav"), "words of three letters or fewer must match exactly")
    }

    @Test
    fun ranksExactMatchesAboveTypos() = runTest {
        if (!supportsTypoTolerance) return@runTest
        val table = table(
            "textSearchTypoRank",
            TextSearchTestModel(title = "Kotlin"),
            TextSearchTestModel(title = "Kotlinn"),
        )
        assertEquals(listOf("Kotlin", "Kotlinn"), table.findByTextIndex("kotlin").toList().map { it.model.title })
    }
}
