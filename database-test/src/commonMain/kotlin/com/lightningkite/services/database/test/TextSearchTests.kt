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

    /**
     * Whether results rank as MongoDB Atlas Search ranks them (Lucene's BM25): rarer words count for more and repeats
     * count for less each time. Atlas is the reference; other backends match it where they can.
     */
    open val supportsBm25Ranking: Boolean = true

    /** Waits until [table]'s search index holds [count] rows, for backends whose index catches up asynchronously. */
    protected open suspend fun waitForIndex(table: Table<*>, count: Int) {}

    private suspend fun table(name: String, vararg models: TextSearchTestModel): Table<TextSearchTestModel> {
        val table = database.prepare(DatabaseTableDefinition<TextSearchTestModel>(name))
        table.deleteManyIgnoringOld(Condition.Always)
        waitForIndex(table, 0)
        table.insert(models.toList())
        waitForIndex(table, models.size)
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
        val results = table.findByTextIndex("kotlin", Condition { it.category eq "b" }).toList()
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

    private suspend fun Table<TextSearchTestModel>.titlesInOrder(query: String): List<String> =
        findByTextIndex(query).toList().map { it.model.title }

    @Test
    fun ranksMoreOccurrencesHigher() = runTest {
        val table = table(
            "textSearchRankOccurrences",
            TextSearchTestModel(title = "kotlin java scala guide"),
            TextSearchTestModel(title = "kotlin kotlin kotlin guide"),
            TextSearchTestModel(title = "kotlin kotlin scala guide"),
        )
        assertEquals(
            listOf("kotlin kotlin kotlin guide", "kotlin kotlin scala guide", "kotlin java scala guide"),
            table.titlesInOrder("kotlin"),
        )
    }

    @Test
    fun ranksShorterTextHigher() = runTest {
        val table = table(
            "textSearchRankLength",
            TextSearchTestModel(title = "kotlin coroutines flows channels"),
            TextSearchTestModel(title = "kotlin"),
            TextSearchTestModel(title = "kotlin coroutines"),
        )
        assertEquals(
            listOf("kotlin", "kotlin coroutines", "kotlin coroutines flows channels"),
            table.titlesInOrder("kotlin"),
        )
    }

    @Test
    fun ranksOccurrencesOfEveryWord() = runTest {
        val table = table(
            "textSearchRankEveryWord",
            TextSearchTestModel(title = "kotlin coroutines java scala"),
            TextSearchTestModel(title = "kotlin coroutines kotlin coroutines"),
            TextSearchTestModel(title = "kotlin coroutines kotlin scala"),
        )
        assertEquals(
            listOf("kotlin coroutines kotlin coroutines", "kotlin coroutines kotlin scala", "kotlin coroutines java scala"),
            table.titlesInOrder("kotlin coroutines"),
        )
    }

    @Test
    fun ranksRarerWordsHigher() = runTest {
        if (!supportsBm25Ranking) return@runTest
        val table = table(
            "textSearchRankRarity",
            TextSearchTestModel(title = "kotlin kotlin coroutines"),
            TextSearchTestModel(title = "kotlin coroutines coroutines"),
            TextSearchTestModel(title = "kotlin guide"),
            TextSearchTestModel(title = "kotlin java"),
            TextSearchTestModel(title = "kotlin scala"),
        )
        assertEquals(
            listOf("kotlin coroutines coroutines", "kotlin kotlin coroutines"),
            table.titlesInOrder("kotlin coroutines"),
            "coroutines is rarer than kotlin, so it counts for more",
        )
    }

    @Test
    fun ranksRepeatsLowerEachTime() = runTest {
        if (!supportsBm25Ranking) return@runTest
        val table = table(
            "textSearchRankSaturation",
            TextSearchTestModel(title = "kotlin kotlin kotlin kotlin kotlin coroutines"),
            TextSearchTestModel(title = "kotlin kotlin kotlin coroutines coroutines coroutines"),
        )
        assertEquals(
            listOf("kotlin kotlin kotlin coroutines coroutines coroutines", "kotlin kotlin kotlin kotlin kotlin coroutines"),
            table.titlesInOrder("kotlin coroutines"),
            "a fifth kotlin adds less than a second or third coroutines",
        )
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
        waitForIndex(raw, 0)
        raw.insert(listOf(LargeTestModel(string = "secret plan", byte = 0), LargeTestModel(string = "secret plan", byte = 1)))
        waitForIndex(raw, 2)
        val table = raw.withPermissions(
            ModelPermissions(
                all = Condition.Always,
                readMask = mask<LargeTestModel> { it.string.mask("", unless = Condition<LargeTestModel> { it.byte eq 1 }) },
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
