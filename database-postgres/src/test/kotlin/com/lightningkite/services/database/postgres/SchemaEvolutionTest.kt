package com.lightningkite.services.database.postgres

import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Condition
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.sql.Database
import org.junit.ClassRule
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

/**
 * Tests for schema evolution scenarios in PostgreSQL.
 *
 * This test suite demonstrates what works and what doesn't when evolving
 * Kotlin data class schemas with the PostgreSQL driver.
 *
 * See SCHEMA_EVOLUTION_ANALYSIS.md for detailed analysis.
 *
 * ## Passing Tests (What Works):
 * - Adding nullable fields
 * - Reading data after removing nullable fields
 * - Adding multiple nullable fields
 *
 * ## Known Limitations (Documented Failures):
 * - Adding non-nullable fields without database defaults
 * - Inserting data after removing non-nullable fields
 * - Type changes require manual migration
 */
class SchemaEvolutionTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = EmbeddedPostgresRules.singleInstance()
    }

    @Rule
    @JvmField
    val pg = postgres

    private fun makeDatabase(): PostgresDatabase {
        return PostgresDatabase(
            "test",
            TestSettingContext(EmptySerializersModule())
        ) {
            Database.connect(pg.embeddedPostgres.postgresDatabase)
        }
    }

    /**
     * Test adding a nullable field to an existing model.
     *
     * Scenario:
     * 1. Create table with original schema (no newField)
     * 2. Insert data
     * 3. Recreate collection with new schema (includes newField: String?)
     * 4. Verify old data can be read (newField should be null)
     * 5. Insert new data with newField populated
     * 6. Verify both old and new records work correctly
     */
    @Test
    fun testAddNullableField() = runTest {
        val tableName = "schema_evolution_nullable_${Uuid.random()}"

        // Phase 1: Original schema without newField
        @Serializable
        data class OriginalModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int
        )

        val db1 = makeDatabase()
        val collection1 = db1.table(OriginalModel.serializer(), tableName)

        val original = OriginalModel(
            name = "original",
            value = 42
        )
        collection1.insert(listOf(original))

        // Phase 2: New schema with additional nullable field
        @Serializable
        data class ExpandedModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int,
            val newField: String? = null  // New nullable field
        )

        val db2 = makeDatabase()
        val collection2 = db2.table(ExpandedModel.serializer(), tableName)

        // Read old data - newField should be null
        val readOriginal = collection2.find(Condition.Always).toList()
        assertEquals(1, readOriginal.size)
        assertEquals(original._id, readOriginal[0]._id)
        assertEquals(original.name, readOriginal[0].name)
        assertEquals(original.value, readOriginal[0].value)
        assertEquals(null, readOriginal[0].newField)

        // Insert new data with newField populated
        val expanded = ExpandedModel(
            name = "expanded",
            value = 99,
            newField = "new data"
        )
        collection2.insert(listOf(expanded))

        // Verify both records exist and are correct
        val allRecords = collection2.find(Condition.Always).toList().sortedBy { it.name }
        assertEquals(2, allRecords.size)

        // Original record
        assertEquals(original._id, allRecords[1]._id)
        assertEquals(null, allRecords[1].newField)

        // New record
        assertEquals(expanded._id, allRecords[0]._id)
        assertEquals("new data", allRecords[0].newField)
    }

    /**
     * Test reading data after removing a nullable field.
     *
     * Scenario:
     * 1. Create table with nullable field
     * 2. Insert data
     * 3. Recreate collection without that field
     * 4. Verify data can still be read (old column ignored)
     * 5. Insert new data (without old field)
     * 6. Verify both old and new records work
     */
    @Test
    fun testRemoveNullableField() = runTest {
        val tableName = "schema_evolution_remove_nullable_${Uuid.random()}"

        // Phase 1: Original schema with nullable field
        @Serializable
        data class OriginalModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int,
            val deprecatedField: String? = null
        )

        val db1 = makeDatabase()
        val collection1 = db1.table(OriginalModel.serializer(), tableName)

        val original = OriginalModel(
            name = "original",
            value = 42,
            deprecatedField = "will be ignored"
        )
        collection1.insert(listOf(original))

        // Phase 2: New schema without the nullable field
        @Serializable
        data class ReducedModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int
        )

        val db2 = makeDatabase()
        val collection2 = db2.table(ReducedModel.serializer(), tableName)

        // Read old data - should work, deprecatedField column ignored
        val readOriginal = collection2.find(Condition.Always).firstOrNull()
        assertNotNull(readOriginal)
        assertEquals(original._id, readOriginal._id)
        assertEquals(original.name, readOriginal.name)
        assertEquals(original.value, readOriginal.value)

        // Insert new data (without deprecatedField) - should work
        val reduced = ReducedModel(
            name = "new",
            value = 99
        )
        collection2.insert(listOf(reduced))

        // Verify both records
        val allRecords = collection2.find(Condition.Always).toList().sortedBy { it.name }
        assertEquals(2, allRecords.size)
        assertEquals(reduced._id, allRecords[0]._id)
        assertEquals(original._id, allRecords[1]._id)
    }

    /**
     * KNOWN LIMITATION: Adding non-nullable fields without database defaults fails.
     *
     * This test documents the current behavior where adding a non-nullable field
     * to an existing table fails because:
     * 1. Kotlin defaults (= "active") are only used when constructing objects in code
     * 2. They are NOT translated to SQL DEFAULT clauses
     * 3. PostgreSQL can't add NOT NULL columns to tables with existing data without a default
     *
     * Scenario:
     * 1. Create table with original schema
     * 2. Insert data
     * 3. Try to recreate collection with non-nullable field
     * 4. EXPECT: Schema update fails with "contains null values" error
     *
     * Workaround: Add field as nullable first, or use manual migration.
     */
    @Test
    fun testAddNonNullableFieldWithDefault_Fails() = runTest {
        val tableName = "schema_evolution_nonnull_fail_${Uuid.random()}"

        // Phase 1: Original schema
        @Serializable
        data class OriginalModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int
        )

        val db1 = makeDatabase()
        val collection1 = db1.table(OriginalModel.serializer(), tableName)

        val original = OriginalModel(
            name = "original",
            value = 42
        )
        collection1.insert(listOf(original))

        // Phase 2: New schema with additional non-nullable field with Kotlin default
        @Serializable
        data class ExpandedModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int,
            val status: String = "active"  // Kotlin default (NOT a DB default!)
        )

        val db2 = makeDatabase()

        // EXPECT: Creating the collection should fail when trying to add NOT NULL column
        val exception = kotlin.runCatching {
            val collection2 = db2.table(ExpandedModel.serializer(), tableName)
            // Trigger schema sync by performing a query
            collection2.find(Condition.Always).firstOrNull()
        }.exceptionOrNull()

        assertNotNull(exception, "Expected exception when adding non-nullable field to table with existing data")
        val message = exception.message ?: ""
        assert(message.contains("null") || message.contains("NOT NULL")) {
            "Expected error about NULL constraint, got: $message"
        }
    }

    /**
     * Test the WORKAROUND for adding non-nullable fields:
     * Add as nullable first, then the field works with Kotlin defaults.
     */
    @Test
    fun testAddNonNullableField_Workaround() = runTest {
        val tableName = "schema_evolution_nonnull_workaround_${Uuid.random()}"

        // Phase 1: Original schema
        @Serializable
        data class V1Model(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int
        )

        val db1 = makeDatabase()
        val collection1 = db1.table(V1Model.serializer(), tableName)

        val v1 = V1Model(name = "original", value = 42)
        collection1.insert(listOf(v1))

        // Phase 2: Add as NULLABLE (this works!)
        @Serializable
        data class V2Model(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int,
            val status: String? = "active"  // Nullable with default
        )

        val db2 = makeDatabase()
        val collection2 = db2.table(V2Model.serializer(), tableName)

        // Read old data - gets Kotlin default
        val readV1 = collection2.find(Condition.Always).firstOrNull()
        assertNotNull(readV1)
        assertEquals("active", readV1.status)  // Kotlin default applied

        // Insert new data
        val v2 = V2Model(name = "new", value = 99, status = "pending")
        collection2.insert(listOf(v2))

        // Verify both records
        val all = collection2.find(Condition.Always).toList().sortedBy { it.name }
        assertEquals(2, all.size)
        assertEquals("pending", all[0].status)
        assertEquals("active", all[1].status)
    }

    /**
     * KNOWN LIMITATION: Removing non-nullable fields breaks inserts.
     *
     * When a non-nullable field is removed from the model but the column still exists
     * in the database as NOT NULL, new inserts fail.
     *
     * Scenario:
     * 1. Create table with non-nullable field
     * 2. Insert data
     * 3. Remove field from model
     * 4. Reading old data works (column ignored)
     * 5. EXPECT: Inserting new data fails (NULL violates NOT NULL constraint)
     */
    @Test
    fun testRemoveNonNullableField_Fails() = runTest {
        val tableName = "schema_evolution_remove_fail_${Uuid.random()}"

        // Phase 1: Original schema with non-nullable field
        @Serializable
        data class OriginalModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int,
            val oldField: String = "will be removed"
        )

        val db1 = makeDatabase()
        val collection1 = db1.table(OriginalModel.serializer(), tableName)

        val original = OriginalModel(
            name = "original",
            value = 42,
            oldField = "old data"
        )
        collection1.insert(listOf(original))

        // Phase 2: New schema without oldField
        @Serializable
        data class ReducedModel(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val value: Int
        )

        val db2 = makeDatabase()
        val collection2 = db2.table(ReducedModel.serializer(), tableName)

        // Reading old data works - oldField column ignored
        val readOriginal = collection2.find(Condition.Always).firstOrNull()
        assertNotNull(readOriginal)
        assertEquals(original._id, readOriginal._id)

        // EXPECT: Inserting new data fails because oldField column is NOT NULL
        val exception = kotlin.runCatching {
            val reduced = ReducedModel(name = "new", value = 99)
            collection2.insert(listOf(reduced))
        }.exceptionOrNull()

        assertNotNull(exception, "Expected exception when inserting after removing non-nullable field")
        val message = exception.message ?: ""
        assert(message.contains("oldField") && message.contains("null")) {
            "Expected error about oldField NULL constraint, got: $message"
        }
    }

    /**
     * Test adding multiple nullable fields at once (this works!).
     */
    @Test
    fun testAddMultipleNullableFields() = runTest {
        val tableName = "schema_evolution_multi_nullable_${Uuid.random()}"

        // Original schema
        @Serializable
        data class V1Model(
            val _id: Uuid = Uuid.random(),
            val name: String
        )

        val db1 = makeDatabase()
        val collection1 = db1.table(V1Model.serializer(), tableName)

        val v1 = V1Model(name = "v1")
        collection1.insert(listOf(v1))

        // Expanded schema with multiple nullable fields (all work!)
        @Serializable
        data class V2Model(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val count: Int? = 0,              // Nullable with default
            val flag: Boolean? = false,       // Nullable with default
            val description: String? = null,  // Nullable
            val tags: List<String>? = null    // Nullable
        )

        val db2 = makeDatabase()
        val collection2 = db2.table(V2Model.serializer(), tableName)

        // Read old data - gets Kotlin defaults
        val readV1 = collection2.find(Condition.Always).firstOrNull()
        assertNotNull(readV1)
        assertEquals(v1._id, readV1._id)
        assertEquals(v1.name, readV1.name)
        assertEquals(0, readV1.count)
        assertEquals(false, readV1.flag)
        assertEquals(null, readV1.description)
        assertEquals(null, readV1.tags)

        // Insert new data
        val v2 = V2Model(
            name = "v2",
            count = 5,
            flag = true,
            description = "new version",
            tags = listOf("tag1", "tag2")
        )
        collection2.insert(listOf(v2))

        // Verify both
        val allRecords = collection2.find(Condition.Always).toList().sortedBy { it.name }
        assertEquals(2, allRecords.size)
        assertEquals(0, allRecords[0].count)  // v1 with Kotlin defaults
        assertEquals(5, allRecords[1].count)  // v2 with provided values
    }

    /**
     * Test adding nested nullable object fields (this works!).
     */
    @Test
    fun testAddNullableNestedField() = runTest {
        val tableName = "schema_evolution_nested_${Uuid.random()}"

        @Serializable
        data class Address(
            val street: String,
            val city: String
        )

        // Original schema
        @Serializable
        data class V1Person(
            val _id: Uuid = Uuid.random(),
            val name: String
        )

        val db1 = makeDatabase()
        val collection1 = db1.table(V1Person.serializer(), tableName)

        val v1 = V1Person(name = "John")
        collection1.insert(listOf(v1))

        // Expanded schema with nullable nested object
        @Serializable
        data class V2Person(
            val _id: Uuid = Uuid.random(),
            val name: String,
            val address: Address? = null  // Nullable nested object
        )

        val db2 = makeDatabase()
        val collection2 = db2.table(V2Person.serializer(), tableName)

        // Read old data - address should be null
        val readV1 = collection2.find(Condition.Always).firstOrNull()
        assertNotNull(readV1)
        assertEquals(v1._id, readV1._id)
        assertEquals(null, readV1.address)

        // Insert new data with address
        val v2 = V2Person(
            name = "Jane",
            address = Address("123 Main St", "Springfield")
        )
        collection2.insert(listOf(v2))

        // Verify both
        val allRecords = collection2.find(Condition.Always).toList().sortedBy { it.name }
        assertEquals(2, allRecords.size)
        assertEquals("Jane", allRecords[0].name)
        assertNotNull(allRecords[0].address)
        assertEquals("Springfield", allRecords[0].address?.city)
        assertEquals("John", allRecords[1].name)
        assertEquals(null, allRecords[1].address)
    }
}
