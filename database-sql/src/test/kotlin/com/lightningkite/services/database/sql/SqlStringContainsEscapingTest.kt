package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for release review finding 5 (BLOCKER): `StringContains`/`RawStringContains`
 * built LIKE patterns as `"%${value}%"` with no escaping, so a literal `%`/`_` in the *search value*
 * was interpreted as a SQL wildcard instead of a literal character — silently broadening matches
 * versus the in-memory reference (`on.contains(value)`, a literal substring search).
 */
class SqlStringContainsEscapingTest {
    private val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:stringContainsEscapingTest;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }

    @Test
    fun percentInSearchValueIsTreatedLiterally() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("percentInSearchValueIsTreatedLiterally"))
        val literalMatch = LargeTestModel(string = "50% off")
        val wildcardOnlyMatch = LargeTestModel(string = "50xyz")
        collection.insertMany(listOf(literalMatch, wildcardOnlyMatch))

        // In-memory reference: "50% off".contains("50%") == true, "50xyz".contains("50%") == false.
        val results = collection.find(condition { it.string.contains("50%") }).toList()
        assertEquals(listOf(literalMatch._id), results.map { it._id })
    }

    @Test
    fun underscoreInSearchValueIsTreatedLiterally() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("underscoreInSearchValueIsTreatedLiterally"))
        val literalMatch = LargeTestModel(string = "a_b")
        val wildcardOnlyMatch = LargeTestModel(string = "axb")
        collection.insertMany(listOf(literalMatch, wildcardOnlyMatch))

        // In-memory reference: "a_b".contains("a_b") == true, "axb".contains("a_b") == false
        // (a naive LIKE would treat '_' as "any one character" and match both).
        val results = collection.find(condition { it.string.contains("a_b") }).toList()
        assertEquals(listOf(literalMatch._id), results.map { it._id })
    }
}
