package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class MigrationTestModel(
    override val _id: String = "",
    val name: String = "",
) : HasId<String>

@Serializable
data class MigrationIndexedModel(
    override val _id: String = "",
    @Index val category: String = "",
) : HasId<String>

/**
 * Covers the split between the additive schema preparation collections run automatically and the
 * destructive [migrationStatements] callers opt into deliberately.
 */
class MigrationStatementsTest {

    private fun database(dbName: String) = SqlDatabase("test", TestSettingContext(EmptySerializersModule())) {
        PooledDatabase(Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null)
    }

    @Test
    fun `nothing to do once the table is prepared`() = runTest {
        val db = database("migrationInSync")
        val collection = db.prepare(DatabaseTableDefinition<MigrationTestModel>("MigrationInSync"))
        collection.insertOne(MigrationTestModel("a", "Alice"))

        assertEquals(
            emptyList(),
            migrationStatements(collection, withLogs = false),
            "A table that was just prepared is already in sync, so no migration is required",
        )
    }

    @Test
    fun `creates the table when it does not exist yet`() = runTest {
        val db = database("migrationMissing")
        // `table` builds the collection without running preparation, so the table is absent from the database.
        val collection = db.table(DatabaseTableDefinition<MigrationTestModel>("MigrationMissing"))

        val statements = migrationStatements(collection, withLogs = false)

        assertTrue(
            statements.any { it.contains("CREATE TABLE", ignoreCase = true) },
            "Expected a CREATE TABLE statement, got $statements",
        )
    }

    /**
     * The reason automatic preparation does not use this: it reports columns that exist in the database
     * but not in the model as `DROP COLUMN`. Running that on startup would delete real data, so it stays
     * opt-in and is never executed here — only reported.
     */
    @Test
    fun `reports a column the model no longer has as a drop`() = runTest {
        val db = database("migrationDrop")
        val collection = db.prepare(DatabaseTableDefinition<MigrationTestModel>("MigrationDrop"))
        inTopLevelSuspendTransaction(db = collection.db) {
            exec("ALTER TABLE MigrationDrop ADD COLUMN legacy_note VARCHAR(64)")
        }

        val statements = migrationStatements(collection, withLogs = false)

        assertTrue(
            statements.any { it.contains("DROP COLUMN", ignoreCase = true) && it.contains("legacy_note", ignoreCase = true) },
            "Expected a DROP COLUMN statement for legacy_note, got $statements",
        )
        // Reported, not applied: the column must still be there.
        val stillPresent = inTopLevelSuspendTransaction(db = collection.db) {
            // H2 folds unquoted identifiers to upper case, which is how they land in information_schema.
            exec("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'MIGRATIONDROP' AND column_name = 'LEGACY_NOTE'") { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        assertEquals(1, stillPresent, "migrationStatements must only report; it must never execute the drop")
    }

    /**
     * The additive path must leave that same unmapped column alone — this is the guarantee that lets
     * preparation run automatically on every startup.
     */
    @Test
    fun `additive preparation never drops anything`() = runTest {
        val db = database("migrationAdditive")
        val collection = db.prepare(DatabaseTableDefinition<MigrationTestModel>("MigrationAdditive"))
        inTopLevelSuspendTransaction(db = collection.db) {
            exec("ALTER TABLE MigrationAdditive ADD COLUMN legacy_note VARCHAR(64)")
        }

        val statements = inTopLevelSuspendTransaction(db = collection.db) {
            additiveSchemaStatements(collection.exposedTables)
        }

        assertEquals(
            emptyList(),
            statements.filter { it.contains("DROP", ignoreCase = true) },
            "Additive preparation must never produce DROP statements",
        )
    }

    /**
     * Indices reach the database on both additive paths, and they arrive by different routes:
     * `createStatements` appends a new table's declared indices, while `checkMappingConsistence`
     * catches indices missing from a table that already exists.
     */
    @Test
    fun `creates declared indices for a table that does not exist yet`() = runTest {
        val db = database("migrationIndexNew")
        val collection = db.table(DatabaseTableDefinition<MigrationIndexedModel>("IndexNew"))

        val statements = inTopLevelSuspendTransaction(db = collection.db) {
            additiveSchemaStatements(collection.exposedTables, withLogs = false)
        }

        assertTrue(
            statements.any { it.contains("CREATE INDEX", ignoreCase = true) && it.contains("category", ignoreCase = true) },
            "Expected a CREATE INDEX for the indexed column, got $statements",
        )
    }

    @Test
    fun `creates a missing index on a table that already exists`() = runTest {
        val db = database("migrationIndexExisting")
        val collection = db.table(DatabaseTableDefinition<MigrationIndexedModel>("IndexExisting"))
        // Create the table WITHOUT its index, so the index is the only thing left to actualize.
        inTopLevelSuspendTransaction(db = collection.db) {
            exec("CREATE TABLE IndexExisting (_id TEXT PRIMARY KEY, category TEXT NOT NULL)")
        }

        val statements = inTopLevelSuspendTransaction(db = collection.db) {
            currentDialectMetadata.resetCaches()
            additiveSchemaStatements(collection.exposedTables, withLogs = false)
        }

        assertTrue(
            statements.any { it.contains("CREATE INDEX", ignoreCase = true) && it.contains("category", ignoreCase = true) },
            "Expected the missing index to be created on the existing table, got $statements",
        )
        assertTrue(
            statements.none { it.contains("CREATE TABLE", ignoreCase = true) },
            "The table already exists, so it must not be recreated: $statements",
        )
    }

    @Test
    fun `rejects collections from different databases`() = runTest {
        val first = database("migrationSplitA").prepare(DatabaseTableDefinition<MigrationTestModel>("SplitA"))
        val second = database("migrationSplitB").prepare(DatabaseTableDefinition<MigrationTestModel>("SplitB"))

        val failure = kotlin.runCatching { migrationStatements(first, second, withLogs = false) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "Expected IllegalArgumentException, got $failure")
        assertContains(failure.message ?: "", "one database")
    }
}
