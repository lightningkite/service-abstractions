package com.lightningkite.services.database.sql

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies that [SqlDatabase.disconnect] cleans up per-collection scopes and that a subsequent
 * [SqlDatabase.connect] yields a working, fresh state.
 *
 * Regression coverage for release review finding 3 (BLOCKER): `database-sql` never received the
 * fix `database-postgres` got for exactly this leak — see [PostgresDisconnectConnectTest] for the
 * sibling test this mirrors. Without the fix, `SqlDatabase.table()`/`prepare()` keep returning the
 * *same* cached [SqlCollection] after disconnect/reconnect, still bound to the closed connection.
 */
class SqlDisconnectConnectTest {
    // DB_CLOSE_DELAY=-1 keeps the H2 in-memory schema alive across connection close/reopen,
    // mirroring how a real serverless target (Postgres, MySQL) survives a client disconnect.
    private fun newDatabase(): SqlDatabase = SqlDatabase(
        "test",
        TestSettingContext(EmptySerializersModule())
    ) { PooledDatabase(Database.connect("jdbc:h2:mem:disconnectReconnectTest;DB_CLOSE_DELAY=-1", "org.h2.Driver"), null) }

    @Test
    fun disconnectClearsCollectionsAndReconnectWorks() = runTest {
        val database = newDatabase()

        // 1. Insert + find roundtrip to force lazy initialization of both
        //    the database connection and the per-collection scope.
        val collection = database.prepare(DatabaseTableDefinition<LargeTestModel>("DisconnectReconnectTest"))
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

        // 4. Connect again and verify data persists and a fresh collection is handed out
        //    rather than the stale one bound to the closed pool.
        database.connect()
        val collection2 = database.prepare(DatabaseTableDefinition<LargeTestModel>("DisconnectReconnectTest"))
        assertNotSame(collection, collection2, "table() must not return the stale pre-disconnect collection")

        val after = collection2.find(Condition.Always).toList()
        assertEquals(listOf(original), after, "row should still be readable after reconnect")

        // The lazy must now be initialized again via the fresh factory.
        assertTrue(database.dbInitialized(), "_db should be re-initialized after reconnect")
    }
}

/** Reads the private `_db` lazy from [SqlDatabase] and reports whether it's initialized. */
private fun SqlDatabase.dbInitialized(): Boolean {
    val field = SqlDatabase::class.java.getDeclaredField("_db")
    field.isAccessible = true
    val lazyValue = field.get(this) as Lazy<*>
    return lazyValue.isInitialized()
}

/** Reads the private `collections` map from [SqlDatabase] for inspection. */
@Suppress("UNCHECKED_CAST")
private fun SqlDatabase.collectionsSnapshot(): Map<Any, Any?> {
    val field = SqlDatabase::class.java.getDeclaredField("collections")
    field.isAccessible = true
    return (field.get(this) as Map<Any, Any?>).toMap()
}
