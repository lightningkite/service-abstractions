package com.lightningkite.services.database.migration

import com.lightningkite.services.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Production readiness tests for the database migration wrapper.
 * These tests verify scenarios critical for production database rotation.
 */

@Serializable
data class MigrationTestUser(
    override val _id: String,
    val name: String,
    val email: String,
    val age: Int,
    val active: Boolean = true
) : HasId<String>

@Serializable
data class MigrationTestOrder(
    override val _id: String,
    val userId: String,
    val amount: Double,
    val status: String = "pending"
) : HasId<String>

class ProductionReadinessTest {

    // Helper to create id condition
    private fun <T : HasId<String>> idCondition(serializer: kotlinx.serialization.KSerializer<T>, id: String): Condition<T> {
        @Suppress("UNCHECKED_CAST")
        val idProp = serializer.serializableProperties!!.find { it.name == "_id" }!!
            as SerializableProperty<T, String>
        return Condition.OnField(idProp, Condition.Equal(id))
    }

    private fun userIdEq(id: String) = idCondition(MigrationTestUser.serializer(), id)
    private fun orderIdEq(id: String) = idCondition(MigrationTestOrder.serializer(), id)

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

    // ==================== RETRY QUEUE TESTS ====================

    @Test
    fun testRetryQueueSuccessfulRetryAfterTransientFailure() = runTest {
        val context = TestSettingContext()
        var failureCount = 0
        val maxFailures = 2

        val queue = RetryQueue<String>(
            config = RetryConfig(maxRetries = 5, initialDelayMs = 10, maxDelayMs = 100),
            clock = context.clock
        )

        val processedItems = mutableListOf<String>()

        queue.start(this) { item ->
            if (failureCount < maxFailures) {
                failureCount++
                throw RuntimeException("Transient failure $failureCount")
            }
            processedItems.add(item)
        }

        queue.enqueue("test-item")

        // Wait for processing
        delay(500.milliseconds)

        assertEquals(1, processedItems.size)
        assertEquals("test-item", processedItems[0])
        assertEquals(1, queue.successCount)
        assertEquals(0, queue.failedCount)
        assertEquals(0, queue.pendingCount)

        queue.stop()
    }

    @Test
    fun testRetryQueueMaxRetriesExceeded() = runTest {
        val context = TestSettingContext()
        val failedItems = mutableListOf<String>()

        val queue = RetryQueue<String>(
            config = RetryConfig(maxRetries = 3, initialDelayMs = 10, maxDelayMs = 50),
            clock = context.clock,
            onMaxRetriesExceeded = { item, _ ->
                failedItems.add(item)
            }
        )

        queue.start(this) { _ ->
            throw RuntimeException("Permanent failure")
        }

        queue.enqueue("doomed-item")

        // Wait for all retries to complete
        delay(500.milliseconds)

        assertEquals(1, failedItems.size)
        assertEquals("doomed-item", failedItems[0])
        assertEquals(1, queue.failedCount)
        assertEquals(0, queue.successCount)

        queue.stop()
    }

    @Test
    fun testRetryQueueMultipleItems() = runTest {
        val context = TestSettingContext()
        val processedItems = mutableListOf<String>()
        val failedItems = mutableListOf<String>()
        var item2Attempts = 0

        val queue = RetryQueue<String>(
            config = RetryConfig(maxRetries = 3, initialDelayMs = 10, maxDelayMs = 50),
            clock = context.clock,
            onMaxRetriesExceeded = { item, _ -> failedItems.add(item) }
        )

        queue.start(this) { item ->
            when {
                item == "item-1" -> processedItems.add(item)
                item == "item-2" -> {
                    item2Attempts++
                    if (item2Attempts < 2) throw RuntimeException("Retry once")
                    processedItems.add(item)
                }
                item == "item-3" -> throw RuntimeException("Always fails")
            }
        }

        queue.enqueue("item-1")
        queue.enqueue("item-2")
        queue.enqueue("item-3")

        delay(1000.milliseconds)

        assertTrue(processedItems.contains("item-1"))
        assertTrue(processedItems.contains("item-2"))
        assertTrue(failedItems.contains("item-3"))
        assertEquals(2, queue.successCount)
        assertEquals(1, queue.failedCount)

        queue.stop()
    }

    // ==================== BACKFILL TESTS ====================

    @Test
    fun testBackfillEmptySourceCompletesImmediately() = runTest {
        val context = TestSettingContext()
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)

        val sourceTable = source.table<MigrationTestUser>()
        val targetTable = target.table<MigrationTestUser>()

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val job = BackfillJob(
            tableName = "MigrationTestUser",
            sourceTable = sourceTable,
            targetTable = targetTable,
            serializer = MigrationTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 100),
            clock = context.clock
        )

        job.start()
        val status = job.awaitCompletion()

        assertEquals(BackfillState.COMPLETED, status.state)
        assertEquals(0, status.processedCount)
        assertEquals(0, status.errorCount)
    }

    @Test
    fun testBackfillCopiesAllRecords() = runTest {
        val context = TestSettingContext()
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)

        val sourceTable = source.table<MigrationTestUser>()
        val targetTable = target.table<MigrationTestUser>()

        // Insert test data
        val users = (1..50).map { i ->
            MigrationTestUser(
                _id = "user-$i",
                name = "User $i",
                email = "user$i@example.com",
                age = 20 + (i % 50)
            )
        }
        sourceTable.insert(users)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val job = BackfillJob(
            tableName = "MigrationTestUser",
            sourceTable = sourceTable,
            targetTable = targetTable,
            serializer = MigrationTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 10),
            clock = context.clock
        )

        job.start()
        val status = job.awaitCompletion()

        assertEquals(BackfillState.COMPLETED, status.state)
        assertEquals(50, status.processedCount)
        assertEquals(0, status.errorCount)

        // Verify all records in target
        val targetUsers = targetTable.find(Condition.Always).toList()
        assertEquals(50, targetUsers.size)

        // Verify data integrity
        for (user in users) {
            val targetUser = targetTable.find(userIdEq(user._id)).firstOrNull()
            assertNotNull(targetUser, "User ${user._id} not found in target")
            assertEquals(user, targetUser)
        }
    }

    @Test
    fun testBackfillProgressTracking() = runTest {
        val context = TestSettingContext()
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)

        val sourceTable = source.table<MigrationTestUser>()
        val targetTable = target.table<MigrationTestUser>()

        // Insert test data
        val users = (1..25).map { i ->
            MigrationTestUser("user-${i.toString().padStart(3, '0')}", "User $i", "user$i@example.com", 20 + i)
        }
        sourceTable.insert(users)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val statusUpdates = mutableListOf<BackfillStatus>()

        val job = BackfillJob(
            tableName = "MigrationTestUser",
            sourceTable = sourceTable,
            targetTable = targetTable,
            serializer = MigrationTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 5),
            clock = context.clock,
            statusCallback = { statusUpdates.add(it) }
        )

        job.start()
        job.awaitCompletion()

        // Should have status updates showing progress
        assertTrue(statusUpdates.isNotEmpty(), "Should have status updates")

        // Progress should increase
        val progressValues = statusUpdates.mapNotNull { it.progressPercent }
        assertTrue(progressValues.any { it > 0 }, "Should show progress > 0")
        assertTrue(progressValues.last() == 100.0, "Final progress should be 100%")
    }

    // ==================== VERIFICATION TESTS ====================

    @Test
    fun testVerifySyncDetectsCountMismatch() = runTest {
        val (migration, source, target) = createMigrationDb()

        // Insert different amounts
        source.table<MigrationTestUser>().insert(listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 30),
            MigrationTestUser("2", "Bob", "bob@example.com", 25)
        ))
        target.table<MigrationTestUser>().insert(listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 30)
        ))

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val result = migration.verifySync(
            tableName = "MigrationTestUser",
            serializer = MigrationTestUser.serializer(),
            idPath = idPath
        )

        assertFalse(result.countMatches)
        assertEquals(2, result.sourceCount)
        assertEquals(1, result.targetCount)
        assertFalse(result.inSync)
    }

    @Test
    fun testVerifySyncDetectsMissingRecords() = runTest {
        val (migration, source, target) = createMigrationDb()

        source.table<MigrationTestUser>().insert(listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 30),
            MigrationTestUser("2", "Bob", "bob@example.com", 25)
        ))
        // Target has same count but different records
        target.table<MigrationTestUser>().insert(listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 30),
            MigrationTestUser("3", "Charlie", "charlie@example.com", 35)
        ))

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val result = migration.verifySync(
            tableName = "MigrationTestUser",
            serializer = MigrationTestUser.serializer(),
            idPath = idPath,
            sampleSize = 10
        )

        assertTrue(result.countMatches) // Counts match
        assertTrue(result.missingInTarget > 0, "Should detect missing record")
        assertFalse(result.inSync)
    }

    @Test
    fun testVerifySyncDetectsDifferentRecords() = runTest {
        val (migration, source, target) = createMigrationDb()

        source.table<MigrationTestUser>().insert(listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 30)
        ))
        // Same ID but different data
        target.table<MigrationTestUser>().insert(listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 31) // Different age
        ))

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val result = migration.verifySync(
            tableName = "MigrationTestUser",
            serializer = MigrationTestUser.serializer(),
            idPath = idPath
        )

        assertTrue(result.countMatches)
        assertEquals(1, result.differentInTarget)
        assertFalse(result.inSync)
    }

    @Test
    fun testVerifySyncPassesWhenInSync() = runTest {
        val (migration, source, target) = createMigrationDb()

        val users = listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 30),
            MigrationTestUser("2", "Bob", "bob@example.com", 25)
        )
        source.table<MigrationTestUser>().insert(users)
        target.table<MigrationTestUser>().insert(users)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val result = migration.verifySync(
            tableName = "MigrationTestUser",
            serializer = MigrationTestUser.serializer(),
            idPath = idPath
        )

        assertTrue(result.countMatches)
        assertEquals(0, result.missingInTarget)
        assertEquals(0, result.differentInTarget)
        assertTrue(result.inSync)
        assertEquals(100.0, result.matchPercent)
    }

    // ==================== CONCURRENT OPERATION TESTS ====================

    @Test
    fun testConcurrentWritesDuringDualWrite() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<MigrationTestUser>()

        // Launch multiple concurrent writers
        val jobs = (1..10).map { i ->
            async {
                table.insert(listOf(
                    MigrationTestUser("user-$i", "User $i", "user$i@example.com", 20 + i)
                ))
            }
        }

        jobs.awaitAll()
        delay(200.milliseconds) // Wait for async secondary writes

        // Both databases should have all records
        assertEquals(10, source.table<MigrationTestUser>().count())
        assertEquals(10, target.table<MigrationTestUser>().count())
    }

    @Test
    fun testWritesDuringModeTransition() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        val table = migration.table<MigrationTestUser>()

        // Phase 1: Source only
        table.insert(listOf(MigrationTestUser("1", "Alice", "alice@example.com", 30)))
        assertEquals(1, source.table<MigrationTestUser>().count())
        assertEquals(0, target.table<MigrationTestUser>().count())

        // Transition to dual-write
        migration.setMode(MigrationMode.DUAL_WRITE_READ_SOURCE)

        // Phase 2: Dual write
        table.insert(listOf(MigrationTestUser("2", "Bob", "bob@example.com", 25)))
        delay(100.milliseconds)
        assertEquals(2, source.table<MigrationTestUser>().count())
        assertEquals(1, target.table<MigrationTestUser>().count())

        // Transition to dual-write reading from target
        migration.setMode(MigrationMode.DUAL_WRITE_READ_TARGET)

        // Phase 3: Still dual write
        table.insert(listOf(MigrationTestUser("3", "Charlie", "charlie@example.com", 35)))
        delay(100.milliseconds)
        assertEquals(3, source.table<MigrationTestUser>().count())
        assertEquals(2, target.table<MigrationTestUser>().count())

        // Final transition to target only
        migration.setMode(MigrationMode.TARGET_ONLY)

        // Phase 4: Target only
        table.insert(listOf(MigrationTestUser("4", "Diana", "diana@example.com", 28)))
        assertEquals(3, source.table<MigrationTestUser>().count()) // Unchanged
        assertEquals(3, target.table<MigrationTestUser>().count())
    }

    // ==================== FULL MIGRATION WORKFLOW TESTS ====================

    @Test
    fun testFullMigrationWorkflow() = runTest {
        val context = TestSettingContext()
        val (migration, source, target) = createMigrationDb(context, MigrationMode.SOURCE_ONLY)

        val table = migration.table<MigrationTestUser>()

        // Step 1: Start with data in source
        val initialUsers = (1..20).map { i ->
            MigrationTestUser("user-${i.toString().padStart(3, '0')}", "User $i", "user$i@example.com", 20 + i)
        }
        table.insert(initialUsers)

        assertEquals(20, source.table<MigrationTestUser>().count())
        assertEquals(0, target.table<MigrationTestUser>().count())

        // Step 2: Enable dual-write
        migration.setMode(MigrationMode.DUAL_WRITE_READ_SOURCE)

        // Step 3: New writes go to both
        table.insert(listOf(
            MigrationTestUser("user-021", "New User", "newuser@example.com", 40)
        ))
        delay(100.milliseconds)

        assertEquals(21, source.table<MigrationTestUser>().count())
        assertEquals(1, target.table<MigrationTestUser>().count())

        // Step 4: Run backfill
        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val backfillJob = BackfillJob(
            tableName = "MigrationTestUser",
            sourceTable = source.table(),
            targetTable = target.table(),
            serializer = MigrationTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 5),
            clock = context.clock
        )

        backfillJob.start()
        val backfillStatus = backfillJob.awaitCompletion()

        assertEquals(BackfillState.COMPLETED, backfillStatus.state)
        assertEquals(21, target.table<MigrationTestUser>().count())

        // Step 5: Verify sync
        val verifyResult = migration.verifySync(
            tableName = "MigrationTestUser",
            serializer = MigrationTestUser.serializer(),
            idPath = idPath,
            sampleSize = 25
        )

        assertTrue(verifyResult.inSync, "Databases should be in sync after backfill")

        // Step 6: Switch to read from target
        migration.setMode(MigrationMode.DUAL_WRITE_READ_TARGET)

        // Reads should come from target now
        val usersFromTarget = table.find(Condition.Always).toList()
        assertEquals(21, usersFromTarget.size)

        // Step 7: Final cutover to target only
        migration.setMode(MigrationMode.TARGET_ONLY)

        // All operations now on target
        table.insert(listOf(
            MigrationTestUser("user-022", "Final User", "final@example.com", 50)
        ))

        assertEquals(21, source.table<MigrationTestUser>().count()) // Source unchanged
        assertEquals(22, target.table<MigrationTestUser>().count()) // Target updated
    }

    @Test
    fun testDataIntegrityAcrossFullMigration() = runTest {
        val context = TestSettingContext()
        val (migration, source, target) = createMigrationDb(context, MigrationMode.SOURCE_ONLY)

        val table = migration.table<MigrationTestUser>()

        // Insert varied data
        val users = listOf(
            MigrationTestUser("alice", "Alice Smith", "alice@example.com", 30, true),
            MigrationTestUser("bob", "Bob Jones", "bob@example.com", 25, false),
            MigrationTestUser("charlie", "Charlie Brown", "charlie@example.com", 35, true)
        )
        table.insert(users)

        // Enable dual-write and backfill
        migration.setMode(MigrationMode.DUAL_WRITE_READ_SOURCE)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(MigrationTestUser.serializer()),
            MigrationTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<MigrationTestUser, String>
        )

        val backfillJob = BackfillJob(
            tableName = "MigrationTestUser",
            sourceTable = source.table(),
            targetTable = target.table(),
            serializer = MigrationTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 10),
            clock = context.clock
        )

        backfillJob.start()
        backfillJob.awaitCompletion()

        // Verify each record individually
        for (user in users) {
            val sourceUser = source.table<MigrationTestUser>().find(userIdEq(user._id)).firstOrNull()
            val targetUser = target.table<MigrationTestUser>().find(userIdEq(user._id)).firstOrNull()

            assertNotNull(sourceUser, "User ${user._id} missing from source")
            assertNotNull(targetUser, "User ${user._id} missing from target")
            assertEquals(sourceUser, targetUser, "User ${user._id} data mismatch")

            // Verify all fields
            assertEquals(user.name, targetUser.name)
            assertEquals(user.email, targetUser.email)
            assertEquals(user.age, targetUser.age)
            assertEquals(user.active, targetUser.active)
        }
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    fun testUpdatesDuringDualWrite() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<MigrationTestUser>()

        // Insert initial record
        table.insert(listOf(MigrationTestUser("1", "Alice", "alice@example.com", 30)))
        delay(100.milliseconds)

        // Update via replace
        val updated = MigrationTestUser("1", "Alice Updated", "alice.updated@example.com", 31)
        table.replaceOne(userIdEq("1"), updated)
        delay(100.milliseconds)

        // Both should have the update
        val sourceUser = source.table<MigrationTestUser>().find(userIdEq("1")).firstOrNull()
        val targetUser = target.table<MigrationTestUser>().find(userIdEq("1")).firstOrNull()

        assertEquals("Alice Updated", sourceUser?.name)
        assertEquals("Alice Updated", targetUser?.name)
        assertEquals(31, sourceUser?.age)
        assertEquals(31, targetUser?.age)
    }

    @Test
    fun testDeletesDuringDualWrite() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<MigrationTestUser>()

        // Insert records
        table.insert(listOf(
            MigrationTestUser("1", "Alice", "alice@example.com", 30),
            MigrationTestUser("2", "Bob", "bob@example.com", 25)
        ))
        delay(100.milliseconds)

        assertEquals(2, source.table<MigrationTestUser>().count())
        assertEquals(2, target.table<MigrationTestUser>().count())

        // Delete one record
        table.deleteOne(userIdEq("1"))
        delay(100.milliseconds)

        assertEquals(1, source.table<MigrationTestUser>().count())
        assertEquals(1, target.table<MigrationTestUser>().count())

        // Verify correct record was deleted
        assertNull(source.table<MigrationTestUser>().find(userIdEq("1")).firstOrNull())
        assertNull(target.table<MigrationTestUser>().find(userIdEq("1")).firstOrNull())
        assertNotNull(source.table<MigrationTestUser>().find(userIdEq("2")).firstOrNull())
        assertNotNull(target.table<MigrationTestUser>().find(userIdEq("2")).firstOrNull())
    }

    @Test
    fun testBulkOperationsDuringDualWrite() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<MigrationTestUser>()

        // Insert many records
        val users = (1..100).map { i ->
            MigrationTestUser("user-$i", "User $i", "user$i@example.com", 20 + (i % 50))
        }
        table.insert(users)
        delay(500.milliseconds)

        assertEquals(100, source.table<MigrationTestUser>().count())
        assertEquals(100, target.table<MigrationTestUser>().count())
    }

    @Test
    fun testMultipleTablesIndependentModes() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        // Set different modes for different tables
        migration.setTableMode("MigrationTestUser", MigrationMode.DUAL_WRITE_READ_SOURCE)
        migration.setTableMode("MigrationTestOrder", MigrationMode.SOURCE_ONLY)

        val userTable = migration.table<MigrationTestUser>()
        val orderTable = migration.table<MigrationTestOrder>()

        // Write to both tables
        userTable.insert(listOf(MigrationTestUser("1", "Alice", "alice@example.com", 30)))
        orderTable.insert(listOf(MigrationTestOrder("order-1", "1", 99.99)))
        delay(100.milliseconds)

        // User should be in both (dual-write mode)
        assertEquals(1, source.table<MigrationTestUser>().count())
        assertEquals(1, target.table<MigrationTestUser>().count())

        // Order should only be in source (source-only mode)
        assertEquals(1, source.table<MigrationTestOrder>().count())
        assertEquals(0, target.table<MigrationTestOrder>().count())
    }

    @Test
    fun testHealthCheckReflectsMode() = runTest {
        val (migration, _, _) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val health = migration.healthCheck()

        assertEquals(HealthStatus.Level.OK, health.level)
        assertTrue(health.additionalMessage?.contains("DUAL_WRITE_READ_SOURCE") == true)
    }

    @Test
    fun testReconnectAfterDisconnect() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<MigrationTestUser>()

        // Initial write
        table.insert(listOf(MigrationTestUser("1", "Alice", "alice@example.com", 30)))
        delay(100.milliseconds)

        // Disconnect and reconnect
        migration.disconnect()
        migration.connect()

        // Should still work
        table.insert(listOf(MigrationTestUser("2", "Bob", "bob@example.com", 25)))
        delay(100.milliseconds)

        assertEquals(2, source.table<MigrationTestUser>().count())
        assertEquals(2, target.table<MigrationTestUser>().count())
    }

    @Test
    fun testUpsertOperationsDuringDualWrite() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<MigrationTestUser>()

        // Initial insert
        table.insert(listOf(MigrationTestUser("1", "Alice", "alice@example.com", 30)))
        delay(100.milliseconds)

        // Upsert existing record
        table.upsertOne(
            condition = userIdEq("1"),
            modification = Modification.Assign(MigrationTestUser("1", "Alice Updated", "alice@example.com", 31)),
            model = MigrationTestUser("1", "Alice Updated", "alice@example.com", 31)
        )
        delay(100.milliseconds)

        // Verify update in both
        val sourceUser = source.table<MigrationTestUser>().find(userIdEq("1")).firstOrNull()
        val targetUser = target.table<MigrationTestUser>().find(userIdEq("1")).firstOrNull()

        assertEquals(31, sourceUser?.age)
        assertEquals(31, targetUser?.age)

        // Upsert new record
        table.upsertOne(
            condition = userIdEq("2"),
            modification = Modification.Assign(MigrationTestUser("2", "Bob", "bob@example.com", 25)),
            model = MigrationTestUser("2", "Bob", "bob@example.com", 25)
        )
        delay(100.milliseconds)

        assertEquals(2, source.table<MigrationTestUser>().count())
        assertEquals(2, target.table<MigrationTestUser>().count())
    }

    // ==================== STATUS TRACKING TESTS ====================

    @Test
    fun testMigrationStatusTracking() = runTest {
        val context = TestSettingContext()
        val (migration, source, target) = createMigrationDb(context, MigrationMode.SOURCE_ONLY)

        // Access some tables
        migration.table<MigrationTestUser>()
        migration.table<MigrationTestOrder>()

        // Set different modes
        migration.setTableMode("MigrationTestUser", MigrationMode.DUAL_WRITE_READ_SOURCE)

        val status = migration.getStatus()

        assertEquals(2, status.size)
        assertEquals(MigrationMode.DUAL_WRITE_READ_SOURCE, status["MigrationTestUser"]?.mode)
        assertEquals(MigrationMode.SOURCE_ONLY, status["MigrationTestOrder"]?.mode)
    }

    @Test
    fun testClearTableModeRevertsToDefault() = runTest {
        val (migration, _, _) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)

        migration.table<MigrationTestUser>()

        // Set custom mode
        migration.setTableMode("MigrationTestUser", MigrationMode.DUAL_WRITE_READ_SOURCE)
        assertEquals(MigrationMode.DUAL_WRITE_READ_SOURCE, migration.getTableMode("MigrationTestUser"))

        // Clear it
        migration.clearTableMode("MigrationTestUser")
        assertEquals(MigrationMode.SOURCE_ONLY, migration.getTableMode("MigrationTestUser"))
    }
}
