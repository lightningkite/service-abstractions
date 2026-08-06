package com.lightningkite.services.database.postgres

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.ClassRule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for LIBRARY-BUGS.md #1: `RegexMatches(ignoreCase = true)` hardcoded
 * `caseSensitive = true` into Exposed's `RegexpOp`, so `ignoreCase` was silently dropped on
 * Postgres while still honored in-memory and on Mongo — the same query returning different rows
 * depending on backend.
 */
class PostgresRegexMatchesIgnoreCaseTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = EmbeddedPostgresRules.singleInstance()
    }

    private val database: com.lightningkite.services.database.Database by lazy {
        PostgresDatabase(
            "test",
            TestSettingContext(EmptySerializersModule())
        ) { PooledDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase), null) }
    }

    @Test
    fun ignoreCaseTrueMatchesRegardlessOfCase() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("ignoreCaseTrueMatchesRegardlessOfCase"))
        val upper = LargeTestModel(string = "HELLO world")
        val lower = LargeTestModel(string = "hello world")
        val nonMatching = LargeTestModel(string = "goodbye world")
        collection.insertMany(listOf(upper, lower, nonMatching))

        val condition = path<LargeTestModel>().string.mapCondition(Condition.RegexMatches("^hello", ignoreCase = true))
        val results = collection.find(condition).toList()
        assertEquals(setOf(upper._id, lower._id), results.map { it._id }.toSet())
    }

    @Test
    fun ignoreCaseFalseIsCaseSensitive() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("ignoreCaseFalseIsCaseSensitive"))
        val upper = LargeTestModel(string = "HELLO world")
        val lower = LargeTestModel(string = "hello world")
        collection.insertMany(listOf(upper, lower))

        val condition = path<LargeTestModel>().string.mapCondition(Condition.RegexMatches("^hello", ignoreCase = false))
        val results = collection.find(condition).toList()
        assertEquals(listOf(lower._id), results.map { it._id })
    }
}
