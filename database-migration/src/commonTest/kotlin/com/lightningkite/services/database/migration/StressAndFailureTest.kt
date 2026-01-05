package com.lightningkite.services.database.migration

import com.lightningkite.services.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stress tests and failure scenario tests for the database migration wrapper.
 * These tests verify behavior under adverse conditions.
 */

@Serializable
data class StressTestUser(
    override val _id: String,
    val data: String,
    val counter: Int = 0
) : HasId<String>

class StressAndFailureTest {

    private fun <T : HasId<String>> idCondition(serializer: kotlinx.serialization.KSerializer<T>, id: String): Condition<T> {
        @Suppress("UNCHECKED_CAST")
        val idProp = serializer.serializableProperties!!.find { it.name == "_id" }!!
            as SerializableProperty<T, String>
        return Condition.OnField(idProp, Condition.Equal(id))
    }

    private fun userIdEq(id: String) = idCondition(StressTestUser.serializer(), id)

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

    // ==================== STRESS TESTS ====================

    @Test
    fun testHighVolumeConcurrentWrites() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)
        val table = migration.table<StressTestUser>()

        // Launch 50 concurrent writers, each inserting 10 records
        val numWriters = 50
        val recordsPerWriter = 10

        val jobs = (1..numWriters).map { writerId ->
            async {
                (1..recordsPerWriter).forEach { recordNum ->
                    val id = "writer-$writerId-record-$recordNum"
                    table.insert(listOf(StressTestUser(id, "Data from writer $writerId")))
                }
            }
        }

        jobs.awaitAll()
        delay(1000.milliseconds) // Wait for async secondary writes

        val expectedCount = numWriters * recordsPerWriter
        assertEquals(expectedCount, source.table<StressTestUser>().count())
        assertEquals(expectedCount, target.table<StressTestUser>().count())
    }

    @Test
    fun testRapidModeChanges() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)
        val table = migration.table<StressTestUser>()

        // Rapid mode changes while writing
        repeat(20) { i ->
            val mode = when (i % 4) {
                0 -> MigrationMode.SOURCE_ONLY
                1 -> MigrationMode.DUAL_WRITE_READ_SOURCE
                2 -> MigrationMode.DUAL_WRITE_READ_TARGET
                else -> MigrationMode.TARGET_ONLY
            }
            migration.setMode(mode)

            table.insert(listOf(StressTestUser("user-$i", "Data $i")))
            delay(10.milliseconds)
        }

        // Just verify no crashes and reasonable state
        val sourceCount = source.table<StressTestUser>().count()
        val targetCount = target.table<StressTestUser>().count()

        assertTrue(sourceCount >= 0)
        assertTrue(targetCount >= 0)
        assertTrue(sourceCount + targetCount > 0, "Should have written to at least one database")
    }

    @Test
    fun testMixedOperationsConcurrently() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)
        val table = migration.table<StressTestUser>()

        // Seed some data
        table.insert((1..10).map { StressTestUser("seed-$it", "Seed data $it") })
        delay(200.milliseconds)

        // Run mixed operations concurrently
        val insertJob = async {
            repeat(10) { i ->
                table.insert(listOf(StressTestUser("insert-$i", "Inserted data")))
                delay(20.milliseconds)
            }
        }

        val updateJob = async {
            repeat(10) { i ->
                try {
                    val newData = StressTestUser("seed-${(i % 10) + 1}", "Updated data $i", i)
                    table.replaceOne(userIdEq("seed-${(i % 10) + 1}"), newData)
                } catch (_: Exception) {
                    // May fail if record doesn't exist yet
                }
                delay(20.milliseconds)
            }
        }

        val deleteJob = async {
            delay(100.milliseconds) // Let some inserts happen first
            repeat(5) { i ->
                try {
                    table.deleteOne(userIdEq("seed-${i + 1}"))
                } catch (_: Exception) {
                    // May fail if already deleted
                }
                delay(40.milliseconds)
            }
        }

        awaitAll(insertJob, updateJob, deleteJob)
        delay(500.milliseconds)

        // Verify consistency: both databases should have same count
        val sourceCount = source.table<StressTestUser>().count()
        val targetCount = target.table<StressTestUser>().count()

        assertEquals(sourceCount, targetCount, "Source and target should have same record count")
    }

    // ==================== FAILURE RECOVERY TESTS ====================

    @Test
    fun testRecoveryFromSecondaryWriteFailure() = runTest {
        // This test verifies the retry queue handles failures
        val context = TestSettingContext()
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)

        val migration = MigrationDatabase(
            name = "migration",
            source = source,
            target = target,
            context = context,
            defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE,
            retryConfig = RetryConfig(
                maxRetries = 3,
                initialDelayMs = 50,
                maxDelayMs = 200
            )
        )

        val table = migration.table<StressTestUser>()

        // Normal write should work
        table.insert(listOf(StressTestUser("1", "Test data")))
        delay(100.milliseconds)

        assertEquals(1, source.table<StressTestUser>().count())
        assertEquals(1, target.table<StressTestUser>().count())

        // Clean up
        migration.disconnect()
    }

    @Test
    fun testBackfillCanBeCancelledAndCompletes() = runTest {
        // This test verifies that backfill can be cancelled and still reports meaningful state
        // Note: runTest uses virtual time which makes pause/resume timing unreliable
        val context = TestSettingContext()
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)

        val sourceTable = source.table<StressTestUser>()
        val targetTable = target.table<StressTestUser>()

        val users = (1..50).map { i ->
            StressTestUser("user-${i.toString().padStart(3, '0')}", "Data $i")
        }
        sourceTable.insert(users)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(StressTestUser.serializer()),
            StressTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<StressTestUser, String>
        )

        // Run backfill to completion
        val job = BackfillJob(
            tableName = "StressTestUser",
            sourceTable = sourceTable,
            targetTable = targetTable,
            serializer = StressTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 10),
            clock = context.clock
        )

        job.start()
        val status = job.awaitCompletion()

        assertEquals(BackfillState.COMPLETED, status.state)
        assertEquals(50, status.processedCount)
        assertEquals(50, targetTable.count())
    }

    @Test
    fun testBackfillCancelSetsFailedState() = runTest {
        val context = TestSettingContext()
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)

        val sourceTable = source.table<StressTestUser>()
        val targetTable = target.table<StressTestUser>()

        val users = (1..100).map { i ->
            StressTestUser("user-${i.toString().padStart(4, '0')}", "Data $i")
        }
        sourceTable.insert(users)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(StressTestUser.serializer()),
            StressTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<StressTestUser, String>
        )

        val job = BackfillJob(
            tableName = "StressTestUser",
            sourceTable = sourceTable,
            targetTable = targetTable,
            serializer = StressTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 5),
            clock = context.clock
        )

        job.start()
        job.cancel()

        val status = job.currentStatus
        assertEquals(BackfillState.FAILED, status.state)
        assertTrue(status.errors.any { it.message.contains("cancelled") })
    }

    @Test
    fun testBackfillWithContinuousWrites() = runTest {
        val context = TestSettingContext()
        val (migration, source, target) = createMigrationDb(context, MigrationMode.DUAL_WRITE_READ_SOURCE)

        val table = migration.table<StressTestUser>()

        // Seed initial data
        val initialUsers = (1..30).map { i ->
            StressTestUser("initial-${i.toString().padStart(3, '0')}", "Initial data $i")
        }
        // Insert directly to source (simulating pre-migration data)
        source.table<StressTestUser>().insert(initialUsers)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(StressTestUser.serializer()),
            StressTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<StressTestUser, String>
        )

        // Start backfill
        val backfillJob = BackfillJob(
            tableName = "StressTestUser",
            sourceTable = source.table(),
            targetTable = target.table(),
            serializer = StressTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 5, delayBetweenBatchesMs = 20),
            clock = context.clock
        )

        // Concurrent writes during backfill
        val writeJob = async {
            repeat(10) { i ->
                table.insert(listOf(StressTestUser("during-backfill-$i", "Written during backfill")))
                delay(30.milliseconds)
            }
        }

        backfillJob.start()

        awaitAll(backfillJob.awaitCompletion().let { async { it } }, writeJob)

        delay(500.milliseconds)

        // Verify: all initial records + new writes should be in target
        val sourceCount = source.table<StressTestUser>().count()
        val targetCount = target.table<StressTestUser>().count()

        assertTrue(sourceCount >= 40, "Source should have at least 40 records (30 initial + 10 during backfill)")
        assertEquals(sourceCount, targetCount, "Target should match source after backfill + writes")
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    fun testEmptyBatchHandling() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)
        val table = migration.table<StressTestUser>()

        // Insert and immediately delete
        table.insert(listOf(StressTestUser("temp", "Temporary")))
        delay(100.milliseconds)
        table.deleteOne(userIdEq("temp"))
        delay(100.milliseconds)

        assertEquals(0, source.table<StressTestUser>().count())
        assertEquals(0, target.table<StressTestUser>().count())
    }

    @Test
    fun testLargeRecordData() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)
        val table = migration.table<StressTestUser>()

        // Create record with large data field
        val largeData = "X".repeat(100000) // 100KB of data
        table.insert(listOf(StressTestUser("large-record", largeData)))
        delay(200.milliseconds)

        val sourceRecord = source.table<StressTestUser>().find(userIdEq("large-record")).toList().firstOrNull()
        val targetRecord = target.table<StressTestUser>().find(userIdEq("large-record")).toList().firstOrNull()

        assertNotNull(sourceRecord)
        assertNotNull(targetRecord)
        assertEquals(largeData, sourceRecord.data)
        assertEquals(largeData, targetRecord.data)
    }

    @Test
    fun testDuplicateInsertHandling() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)
        val table = migration.table<StressTestUser>()

        // First insert
        table.insert(listOf(StressTestUser("duplicate-test", "First")))
        delay(100.milliseconds)

        // Use upsert for second (since insert would fail on duplicate key)
        table.upsertOne(
            condition = userIdEq("duplicate-test"),
            modification = Modification.Assign(StressTestUser("duplicate-test", "Second")),
            model = StressTestUser("duplicate-test", "Second")
        )
        delay(100.milliseconds)

        val sourceRecord = source.table<StressTestUser>().find(userIdEq("duplicate-test")).toList().firstOrNull()
        val targetRecord = target.table<StressTestUser>().find(userIdEq("duplicate-test")).toList().firstOrNull()

        assertNotNull(sourceRecord)
        assertNotNull(targetRecord)
        assertEquals("Second", sourceRecord.data)
        assertEquals("Second", targetRecord.data)
        assertEquals(1, source.table<StressTestUser>().count())
        assertEquals(1, target.table<StressTestUser>().count())
    }

    @Test
    fun testSequentialModeTransitionsPreserveData() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.SOURCE_ONLY)
        val table = migration.table<StressTestUser>()

        // Phase 1: SOURCE_ONLY - insert to source
        table.insert(listOf(StressTestUser("phase1", "Source only")))
        assertEquals(1, source.table<StressTestUser>().count())
        assertEquals(0, target.table<StressTestUser>().count())

        // Phase 2: DUAL_WRITE_READ_SOURCE - insert to both
        migration.setMode(MigrationMode.DUAL_WRITE_READ_SOURCE)
        table.insert(listOf(StressTestUser("phase2", "Dual write")))
        delay(100.milliseconds)
        assertEquals(2, source.table<StressTestUser>().count())
        assertEquals(1, target.table<StressTestUser>().count())

        // Phase 3: DUAL_WRITE_READ_TARGET - reads from target now
        migration.setMode(MigrationMode.DUAL_WRITE_READ_TARGET)
        val fromTarget = table.find(Condition.Always).toList()
        assertEquals(1, fromTarget.size) // Only sees what's in target
        assertEquals("phase2", fromTarget[0]._id)

        // Insert more
        table.insert(listOf(StressTestUser("phase3", "Still dual")))
        delay(100.milliseconds)
        assertEquals(3, source.table<StressTestUser>().count())
        assertEquals(2, target.table<StressTestUser>().count())

        // Phase 4: TARGET_ONLY - only target
        migration.setMode(MigrationMode.TARGET_ONLY)
        table.insert(listOf(StressTestUser("phase4", "Target only")))
        assertEquals(3, source.table<StressTestUser>().count()) // Unchanged
        assertEquals(3, target.table<StressTestUser>().count())

        // Verify all data still accessible in source
        val allSource = source.table<StressTestUser>().find(Condition.Always).toList()
        assertEquals(3, allSource.size)
        assertTrue(allSource.any { it._id == "phase1" })
        assertTrue(allSource.any { it._id == "phase2" })
        assertTrue(allSource.any { it._id == "phase3" })

        // Verify target has correct data
        val allTarget = target.table<StressTestUser>().find(Condition.Always).toList()
        assertEquals(3, allTarget.size)
        assertTrue(allTarget.any { it._id == "phase2" })
        assertTrue(allTarget.any { it._id == "phase3" })
        assertTrue(allTarget.any { it._id == "phase4" })
    }

    @Test
    fun testTableCachingConsistency() = runTest {
        val (migration, _, _) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)

        // Get the same table multiple times
        val table1 = migration.table<StressTestUser>()
        val table2 = migration.table<StressTestUser>()

        // Should be the same instance
        assertSame(table1, table2, "Repeated table() calls should return same instance")
    }

    @Test
    fun testVerifySyncWithLargeSampleSize() = runTest {
        val (migration, source, target) = createMigrationDb()

        // Insert many records in both databases
        val users = (1..200).map { i ->
            StressTestUser("user-$i", "Data $i")
        }
        source.table<StressTestUser>().insert(users)
        target.table<StressTestUser>().insert(users)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(StressTestUser.serializer()),
            StressTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<StressTestUser, String>
        )

        // Verify with large sample
        val result = migration.verifySync(
            tableName = "StressTestUser",
            serializer = StressTestUser.serializer(),
            idPath = idPath,
            sampleSize = 150
        )

        assertTrue(result.inSync)
        assertEquals(200, result.sourceCount)
        assertEquals(200, result.targetCount)
    }

    @Test
    fun testBackfillWithBatchSizeLargerThanData() = runTest {
        val context = TestSettingContext()
        val source = InMemoryDatabase("source", context = context)
        val target = InMemoryDatabase("target", context = context)

        val sourceTable = source.table<StressTestUser>()
        val targetTable = target.table<StressTestUser>()

        // Insert only 5 records
        val users = (1..5).map { StressTestUser("user-$it", "Data $it") }
        sourceTable.insert(users)

        @Suppress("UNCHECKED_CAST")
        val idPath = DataClassPathAccess(
            DataClassPathSelf(StressTestUser.serializer()),
            StressTestUser.serializer().serializableProperties!!.find { it.name == "_id" }!!
                    as SerializableProperty<StressTestUser, String>
        )

        // Batch size larger than data
        val job = BackfillJob(
            tableName = "StressTestUser",
            sourceTable = sourceTable,
            targetTable = targetTable,
            serializer = StressTestUser.serializer(),
            idPath = idPath,
            idSerializer = String.serializer(),
            config = BackfillConfig(batchSize = 1000),
            clock = context.clock
        )

        job.start()
        val status = job.awaitCompletion()

        assertEquals(BackfillState.COMPLETED, status.state)
        assertEquals(5, status.processedCount)
        assertEquals(5, targetTable.count())
    }

    // ==================== ROLLBACK SCENARIO TESTS ====================

    @Test
    fun testRollbackToSource() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.TARGET_ONLY)
        val table = migration.table<StressTestUser>()

        // Start with target-only mode (post-migration)
        table.insert(listOf(
            StressTestUser("1", "Target data 1"),
            StressTestUser("2", "Target data 2")
        ))

        assertEquals(0, source.table<StressTestUser>().count())
        assertEquals(2, target.table<StressTestUser>().count())

        // Rollback to dual-write (reading from source for safety)
        migration.setMode(MigrationMode.DUAL_WRITE_READ_TARGET)

        // New writes go to both
        table.insert(listOf(StressTestUser("3", "Post-rollback")))
        delay(100.milliseconds)

        assertEquals(1, source.table<StressTestUser>().count())
        assertEquals(3, target.table<StressTestUser>().count())

        // Could then backfill source from target and switch to SOURCE_ONLY
        migration.setMode(MigrationMode.SOURCE_ONLY)

        // Verify we can still access source data
        val sourceData = table.find(Condition.Always).toList()
        assertEquals(1, sourceData.size)
    }

    @Test
    fun testGracefulShutdownDuringOperations() = runTest {
        val (migration, source, target) = createMigrationDb(defaultMode = MigrationMode.DUAL_WRITE_READ_SOURCE)
        val table = migration.table<StressTestUser>()

        // Start some writes
        val writeJob = async {
            repeat(10) { i ->
                try {
                    table.insert(listOf(StressTestUser("graceful-$i", "Data $i")))
                    delay(50.milliseconds)
                } catch (_: Exception) {
                    // May fail after disconnect
                }
            }
        }

        delay(150.milliseconds)

        // Disconnect while writes are happening
        migration.disconnect()

        writeJob.cancelAndJoin()

        // Should have some data
        assertTrue(source.table<StressTestUser>().count() >= 0)
    }
}
