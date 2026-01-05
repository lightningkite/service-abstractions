package com.lightningkite.services.database.migration

import com.lightningkite.services.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class TestUser(
    override val _id: String,
    val name: String,
    val age: Int
) : HasId<String>

class MigrationDatabaseTest {

    private fun createMigrationDb(
        context: SettingContext = TestSettingContext(),
        defaultMode: MigrationMode = MigrationMode.SOURCE_ONLY
    ): Triple<MigrationDatabase, Database, Database> {
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)
        val migration = MigrationDatabase(
            name = "migration",
            source = source,
            target = target,
            context = context,
            defaultMode = defaultMode
        )
        return Triple(migration, source, target)
    }

    // Helper to create id condition using SerializableProperty
    private fun idEq(id: String): Condition<TestUser> {
        @Suppress("UNCHECKED_CAST")
        val idProp = TestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
            as SerializableProperty<TestUser, String>
        return Condition.OnField(idProp, Condition.Equal(id))
    }

    // ===== Mode Routing Tests =====

    @Test
    fun testSourceOnlyModeWritesToSourceOnly() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        val table = migration.table<TestUser>()
        table.insert(listOf(TestUser("1", "Alice", 30)))

        // Should be in source only
        assertEquals(1, source.table<TestUser>().count())
        assertEquals(0, target.table<TestUser>().count())
    }

    @Test
    fun testSourceOnlyModeReadsFromSource() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        // Insert directly into both databases
        source.table<TestUser>().insert(listOf(TestUser("1", "Alice", 30)))
        target.table<TestUser>().insert(listOf(TestUser("2", "Bob", 25)))

        // Migration should read from source
        val users = migration.table<TestUser>().find(Condition.Always).toList()
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].name)
    }

    @Test
    fun testTargetOnlyModeWritesToTargetOnly() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.TARGET_ONLY)

        val table = migration.table<TestUser>()
        table.insert(listOf(TestUser("1", "Alice", 30)))

        // Should be in target only
        assertEquals(0, source.table<TestUser>().count())
        assertEquals(1, target.table<TestUser>().count())
    }

    @Test
    fun testTargetOnlyModeReadsFromTarget() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.TARGET_ONLY)

        // Insert directly into both databases
        source.table<TestUser>().insert(listOf(TestUser("1", "Alice", 30)))
        target.table<TestUser>().insert(listOf(TestUser("2", "Bob", 25)))

        // Migration should read from target
        val users = migration.table<TestUser>().find(Condition.Always).toList()
        assertEquals(1, users.size)
        assertEquals("Bob", users[0].name)
    }

    @Test
    fun testDualWriteReadSourceWritesToBoth() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<TestUser>()
        table.insert(listOf(TestUser("1", "Alice", 30)))

        // Give async write time to complete
        delay(100)

        // Should be in both databases
        assertEquals(1, source.table<TestUser>().count())
        assertEquals(1, target.table<TestUser>().count())
    }

    @Test
    fun testDualWriteReadSourceReadsFromSource() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        // Insert different data directly into each database
        source.table<TestUser>().insert(listOf(TestUser("1", "Alice", 30)))
        target.table<TestUser>().insert(listOf(TestUser("1", "Alice-Target", 31)))

        // Migration should read from source
        val users = migration.table<TestUser>().find(Condition.Always).toList()
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].name)
        assertEquals(30, users[0].age)
    }

    @Test
    fun testDualWriteReadTargetReadsFromTarget() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_TARGET)

        // Insert different data directly into each database
        source.table<TestUser>().insert(listOf(TestUser("1", "Alice", 30)))
        target.table<TestUser>().insert(listOf(TestUser("1", "Alice-Target", 31)))

        // Migration should read from target
        val users = migration.table<TestUser>().find(Condition.Always).toList()
        assertEquals(1, users.size)
        assertEquals("Alice-Target", users[0].name)
        assertEquals(31, users[0].age)
    }

    // ===== Dual-Write Operation Tests =====

    @Test
    fun testDualWriteUpdateAppearsInBoth() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        // Insert initial data
        val table = migration.table<TestUser>()
        table.insert(listOf(TestUser("1", "Alice", 30)))
        delay(100)

        // Update through migration using replace (simpler without generated paths)
        val updated = TestUser("1", "Alice", 31)
        table.replaceOne(idEq("1"), updated)
        delay(100)

        // Both should have the updated value
        assertEquals(31, source.table<TestUser>().find(idEq("1")).firstOrNull()?.age)
        assertEquals(31, target.table<TestUser>().find(idEq("1")).firstOrNull()?.age)
    }

    @Test
    fun testDualWriteDeleteAppearsInBoth() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        // Insert initial data
        val table = migration.table<TestUser>()
        table.insert(listOf(TestUser("1", "Alice", 30)))
        delay(100)

        // Delete through migration
        table.deleteOne(idEq("1"))
        delay(100)

        // Both should be empty
        assertEquals(0, source.table<TestUser>().count())
        assertEquals(0, target.table<TestUser>().count())
    }

    @Test
    fun testDualWriteUpsertAppearsInBoth() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<TestUser>()

        // Upsert a new record
        table.upsertOne(
            condition = idEq("1"),
            modification = Modification.Assign(TestUser("1", "Alice", 31)),
            model = TestUser("1", "Alice", 30)
        )
        delay(100)

        // Both should have the record
        assertEquals(1, source.table<TestUser>().count())
        assertEquals(1, target.table<TestUser>().count())
    }

    // ===== Mode Switching Tests =====

    @Test
    fun testModeSwitching() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        val table = migration.table<TestUser>()

        // Phase 1: Source only
        table.insert(listOf(TestUser("1", "Alice", 30)))
        assertEquals(1, source.table<TestUser>().count())
        assertEquals(0, target.table<TestUser>().count())

        // Phase 2: Enable dual-write
        migration.setMode(MigrationMode.DUAL_WRITE_READ_SOURCE)
        table.insert(listOf(TestUser("2", "Bob", 25)))
        delay(100)
        assertEquals(2, source.table<TestUser>().count())
        assertEquals(1, target.table<TestUser>().count()) // Only new record in target

        // Phase 3: Switch to target only
        migration.setMode(MigrationMode.TARGET_ONLY)
        table.insert(listOf(TestUser("3", "Charlie", 35)))
        assertEquals(2, source.table<TestUser>().count()) // Source unchanged
        assertEquals(2, target.table<TestUser>().count())
    }

    @Test
    fun testPerTableModeOverride() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        // Override mode for User table only
        migration.setTableMode("TestUser", MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<TestUser>()
        table.insert(listOf(TestUser("1", "Alice", 30)))
        delay(100)

        // Should be in both databases because of table-specific override
        assertEquals(1, source.table<TestUser>().count())
        assertEquals(1, target.table<TestUser>().count())
    }

    // Note: Verification tests (verifySync) and Backfill tests are omitted because they
    // require generated DataClassPath extensions which aren't available in test sources.
    // These should be tested via integration tests with real models.

    // ===== Health Check Tests =====

    @Test
    fun testHealthCheckCombinesBothDatabases() = runTest {
        val (migration, _, _) = createMigrationDb()

        val health = migration.healthCheck()
        assertEquals(HealthStatus.Level.OK, health.level)
        assertTrue(health.additionalMessage?.contains("Mode:") == true)
    }

    // ===== Status Tests =====

    @Test
    fun testGetStatus() = runTest {
        val (migration, _, _) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        // Access a table to register it
        migration.table<TestUser>()

        val status = migration.getStatus()
        assertTrue(status.containsKey("TestUser"))
        assertEquals(MigrationMode.SOURCE_ONLY, status["TestUser"]?.mode)
    }

    // ===== Connect/Disconnect Tests =====

    @Test
    fun testConnectAndDisconnect() = runTest {
        val (migration, _, _) = createMigrationDb()

        // Should not throw
        migration.connect()
        migration.disconnect()
    }
}
