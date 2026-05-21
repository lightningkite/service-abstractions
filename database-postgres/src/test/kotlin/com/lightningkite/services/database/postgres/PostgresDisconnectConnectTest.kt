package com.lightningkite.services.database.postgres

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.LargeTestModel
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.sql.Database
import org.junit.ClassRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies that [PostgresDatabase.disconnect] cleans up per-collection scopes
 * and that a subsequent [PostgresDatabase.connect] yields a working, fresh state.
 *
 * Regression coverage for the 1.0.0 performance pass which added scope cleanup
 * to disconnect to prevent leaked GlobalScope.async jobs in PostgresCollection.
 */
class PostgresDisconnectConnectTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = EmbeddedPostgresRules.singleInstance()
    }

    private fun newDatabase(): PostgresDatabase = PostgresDatabase(
        "test",
        TestSettingContext(EmptySerializersModule())
    ) { Database.connect(postgres.embeddedPostgres.postgresDatabase) }

    @Test
    fun disconnectClearsCollectionsAndReconnectWorks() = runTest {
        val database = newDatabase()

        // 1. Insert + find roundtrip to force lazy initialization of both
        //    the database connection and the per-collection scope.
        val collection = database.table<LargeTestModel>("DisconnectReconnectTest")
        val original = LargeTestModel(instant = Instant.fromEpochMilliseconds(42L))
        collection.insertOne(original)
        val before = collection.find(Condition.Always).toList()
        assertEquals(listOf(original), before)

        // After use, both lazies must be initialized.
        assertTrue(database.dbInitialized(), "_db should be initialized after use")
        assertFalse(
            database.collectionsSnapshot().isEmpty(),
            "collections map should contain the requested table"
        )

        // 2. Disconnect.
        database.disconnect()

        // 3. State assertions: collections cleared and the _db lazy was replaced
        //    by a fresh, uninitialized one.
        assertTrue(
            database.collectionsSnapshot().isEmpty(),
            "collections should be cleared after disconnect"
        )
        assertFalse(
            database.dbInitialized(),
            "_db should be reset to a fresh uninitialized lazy after disconnect"
        )

        // 4. Connect again and verify data persists (Postgres is durable across
        //    a logical disconnect) and a new collection works end-to-end.
        database.connect()
        val collection2 = database.table<LargeTestModel>("DisconnectReconnectTest")
        val after = collection2.find(Condition.Always).toList()
        assertEquals(listOf(original), after, "row should still be readable after reconnect")

        // The lazy must now be initialized again via the fresh factory.
        assertTrue(database.dbInitialized(), "_db should be re-initialized after reconnect")
    }
}

/** Reads the private `_db` lazy from [PostgresDatabase] and reports whether it's initialized. */
private fun PostgresDatabase.dbInitialized(): Boolean {
    val field = PostgresDatabase::class.java.getDeclaredField("_db")
    field.isAccessible = true
    val lazyValue = field.get(this) as Lazy<*>
    return lazyValue.isInitialized()
}

/** Reads the private `collections` map from [PostgresDatabase] for inspection. */
@Suppress("UNCHECKED_CAST")
private fun PostgresDatabase.collectionsSnapshot(): Map<Any, Any?> {
    val field = PostgresDatabase::class.java.getDeclaredField("collections")
    field.isAccessible = true
    return (field.get(this) as Map<Any, Any?>).toMap()
}
