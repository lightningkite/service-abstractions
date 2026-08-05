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
 * Regression coverage for release review finding 6 (BLOCKER): `NotInside` on a nullable column
 * translated straight to `NOT (col IN (...))`. Under SQL three-valued logic `NOT (NULL IN (...))`
 * evaluates to NULL, which `WHERE` treats as "exclude" — silently diverging from the in-memory
 * reference, which treats "not in the list" as vacuously true for a null field.
 */
class PostgresNotInsideNullableTest {
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
    fun notInsideIncludesNullColumnValues() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("notInsideIncludesNullColumnValues"))
        val nullRow = LargeTestModel(intNullable = null)
        val outsideListRow = LargeTestModel(intNullable = 99)
        val insideListRow = LargeTestModel(intNullable = 2)
        collection.insertMany(listOf(nullRow, outsideListRow, insideListRow))

        // In-memory semantics: !listOf(1,2,3).contains(value) -> true for both null and 99.
        val results = collection.find(condition { it.intNullable notInside listOf(1, 2, 3) }).toList()
        assertEquals(setOf(nullRow._id, outsideListRow._id), results.map { it._id }.toSet())
    }
}
