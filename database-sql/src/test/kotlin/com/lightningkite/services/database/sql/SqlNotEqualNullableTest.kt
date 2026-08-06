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
 * Regression coverage for LIBRARY-BUGS.md #2: `NotEqual` on a nullable column translated
 * straight to `<>`. Under SQL three-valued logic `NULL <> 5` evaluates to NULL, which `WHERE`
 * treats as "exclude" — silently diverging from the in-memory reference, where `null != 5` is
 * `true` and the row is included. Mirrors [SqlNotInsideNullableTest], the sibling condition type
 * that already carried this null guard.
 */
class SqlNotEqualNullableTest {
    private val database: com.lightningkite.services.database.Database by lazy {
        SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
            PooledDatabase(Database.connect("jdbc:h2:mem:notEqualNullableTest;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
        }
    }

    @Test
    fun notEqualIncludesNullColumnValues() = runTest {
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("notEqualIncludesNullColumnValues"))
        val nullRow = LargeTestModel(intNullable = null)
        val matchingRow = LargeTestModel(intNullable = 5)
        val differentRow = LargeTestModel(intNullable = 99)
        collection.insertMany(listOf(nullRow, matchingRow, differentRow))

        // In-memory semantics: null != 5 -> true, 99 != 5 -> true, 5 != 5 -> false.
        val results = collection.find(condition { it.intNullable neq 5 }).toList()
        assertEquals(setOf(nullRow._id, differentRow._id), results.map { it._id }.toSet())
    }
}
