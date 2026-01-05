# Database Migration Wrapper Implementation Plan

## Overview

Implement a database wrapper that enables zero-downtime migrations between database implementations by supporting dual-write, backfill, and gradual cutover.

## Design Decisions

### Retry Strategy (Question 1)
**Decision: In-memory async queue with bounded retries**

Rationale:
- Synchronous retry blocks primary operations and increases latency unacceptably
- Log-only approach loses data and requires manual intervention
- Persistent queue adds complexity and external dependencies
- In-memory queue with bounded retries (e.g., 3 attempts with exponential backoff) handles transient failures
- If secondary DB is down for extended periods, operators should pause migration anyway
- Failed writes after max retries are logged with full context for manual recovery

### Backfill Triggers (Question 2)
**Decision: Manual triggering only**

Rationale:
- Automatic backfill could overwhelm systems unexpectedly
- Operators need control over timing (low-traffic periods)
- Enables dry-run verification before actual migration
- Supports pause/resume for large datasets
- Progress monitoring requires explicit start point

## Module Structure

Create new module: `database-migration`

```
database-migration/
├── build.gradle.kts
└── src/
    ├── commonMain/kotlin/com/lightningkite/services/database/migration/
    │   ├── MigrationDatabase.kt       # Database wrapper
    │   ├── MigrationTable.kt          # Table wrapper with dual-write logic
    │   ├── MigrationMode.kt           # Enum for migration phases
    │   ├── MigrationStatus.kt         # Progress tracking data classes
    │   ├── RetryQueue.kt              # In-memory retry queue
    │   └── BackfillJob.kt             # Backfill orchestration
    └── commonTest/kotlin/com/lightningkite/services/database/migration/
        └── MigrationDatabaseTest.kt   # Tests using InMemoryDatabase
```

## Implementation Steps

### Step 1: Create Module Structure

1. Create `database-migration/build.gradle.kts`:
   - Kotlin multiplatform (common + JVM initially)
   - Dependencies: `:database`, `:basis`, kotlinx-coroutines, kotlin-logging
   - Use `explicitApi()` mode

2. Add module to `settings.gradle.kts`

### Step 2: Define Data Classes

**MigrationMode.kt**
```kotlin
@Serializable
enum class MigrationMode {
    SOURCE_ONLY,            // Normal operation, source database only
    DUAL_WRITE_READ_SOURCE, // Write both, read from source (backfill phase)
    DUAL_WRITE_READ_TARGET, // Write both, read from target (verification phase)
    TARGET_ONLY             // Migration complete, target only
}
```

**MigrationStatus.kt**
```kotlin
@Serializable
data class MigrationTableStatus(
    val tableName: String,
    val mode: MigrationMode,
    val backfillStatus: BackfillStatus? = null
)

@Serializable
data class BackfillStatus(
    val state: BackfillState,
    val totalEstimate: Long?,        // Estimated total records (from initial count)
    val processedCount: Long,
    val errorCount: Long,
    val lastProcessedId: String?,    // Serialized ID for resume
    val startedAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val errors: List<BackfillError> = emptyList()
)

@Serializable
enum class BackfillState {
    NOT_STARTED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED
}

@Serializable
data class BackfillError(
    val recordId: String,
    val message: String,
    val timestamp: Instant
)

@Serializable
data class SyncVerificationResult(
    val tableName: String,
    val sourceCount: Int,
    val targetCount: Int,
    val countMatches: Boolean,
    val sampledRecords: Int,
    val matchingRecords: Int,
    val missingInTarget: Int,
    val differentInTarget: Int,
    val verifiedAt: Instant
)
```

### Step 3: Implement RetryQueue

**RetryQueue.kt**
```kotlin
class RetryQueue<T>(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 100,
    val maxDelayMs: Long = 5000,
    val onMaxRetriesExceeded: suspend (T, Exception) -> Unit = { _, _ -> }
) {
    private data class QueuedItem<T>(
        val item: T,
        val attemptCount: Int,
        val nextAttemptAt: Instant
    )

    private val queue = Channel<QueuedItem<T>>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun enqueue(item: T)

    fun start(processor: suspend (T) -> Unit)

    fun stop()

    val pendingCount: Int
    val failedCount: Int
}
```

Features:
- Exponential backoff between retries
- Configurable max retries
- Callback when max retries exceeded (for logging/alerting)
- Graceful shutdown (process remaining items)

### Step 4: Implement MigrationTable

**MigrationTable.kt**

Core responsibilities:
- Route reads to appropriate database based on mode
- Dual-write with retry queue for secondary
- Track write failures for monitoring

```kotlin
class MigrationTable<T : Any>(
    val sourceTable: Table<T>,
    val targetTable: Table<T>,
    override val serializer: KSerializer<T>,
    val modeProvider: () -> MigrationMode,  // Dynamic mode lookup
    val retryQueue: RetryQueue<RetryOperation<T>>,
    val context: SettingContext
) : Table<T> {

    override val wraps: Table<T>? get() = when (modeProvider()) {
        SOURCE_ONLY, DUAL_WRITE_READ_SOURCE -> sourceTable
        DUAL_WRITE_READ_TARGET, TARGET_ONLY -> targetTable
    }

    // Sealed class for retry operations
    sealed class RetryOperation<T> {
        data class Insert<T>(val models: List<T>) : RetryOperation<T>()
        data class Update<T>(val condition: Condition<T>, val modification: Modification<T>) : RetryOperation<T>()
        data class Delete<T>(val condition: Condition<T>) : RetryOperation<T>()
        data class Upsert<T>(val condition: Condition<T>, val modification: Modification<T>, val model: T) : RetryOperation<T>()
        data class Replace<T>(val condition: Condition<T>, val model: T) : RetryOperation<T>()
    }
}
```

**Read Operations** (route based on mode):
- `find()` - Route to primary read database
- `findPartial()` - Route to primary read database
- `count()` - Route to primary read database
- `groupCount()` - Route to primary read database
- `aggregate()` - Route to primary read database
- `groupAggregate()` - Route to primary read database
- `findSimilar()` - Route to primary read database
- `findSimilarSparse()` - Route to primary read database

**Write Operations** (dual-write pattern):

```kotlin
override suspend fun insert(models: Iterable<Model>): List<Model> {
    val mode = modeProvider()
    return when (mode) {
        SOURCE_ONLY -> sourceTable.insert(models)
        TARGET_ONLY -> targetTable.insert(models)
        DUAL_WRITE_READ_SOURCE -> {
            val result = sourceTable.insert(models)
            writeToSecondary { targetTable.insert(models) }
            result
        }
        DUAL_WRITE_READ_TARGET -> {
            val result = targetTable.insert(models)
            writeToSecondary { sourceTable.insert(models) }
            result
        }
    }
}

private suspend fun writeToSecondary(
    retryOp: RetryOperation<T>,
    block: suspend () -> Unit
) {
    try {
        block()
    } catch (e: Exception) {
        logger.warn(e) { "Secondary write failed, queueing for retry" }
        retryQueue.enqueue(retryOp)
    }
}
```

All write methods follow same pattern:
- `insert()`, `replaceOne()`, `replaceOneIgnoringResult()`
- `upsertOne()`, `upsertOneIgnoringResult()`
- `updateOne()`, `updateOneIgnoringResult()`
- `updateMany()`, `updateManyIgnoringResult()`
- `deleteOne()`, `deleteOneIgnoringOld()`
- `deleteMany()`, `deleteManyIgnoringOld()`

### Step 5: Implement MigrationDatabase

**MigrationDatabase.kt**

```kotlin
class MigrationDatabase(
    override val name: String,
    val source: Database,
    val target: Database,
    override val context: SettingContext,
    val defaultMode: MigrationMode = MigrationMode.SOURCE_ONLY,
    val retryConfig: RetryConfig = RetryConfig()
) : Database {

    // Per-table mode overrides (for gradual rollout)
    private val tableModes = ConcurrentHashMap<String, MigrationMode>()

    // Per-table status tracking
    private val tableStatus = ConcurrentHashMap<String, MigrationTableStatus>()

    // Shared retry queue
    private val retryQueue = RetryQueue<Any>(...)

    // Table cache
    private val tables = ConcurrentHashMap<Pair<KSerializer<*>, String>, MigrationTable<*>>()

    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> {
        return tables.getOrPut(serializer to name) {
            MigrationTable(
                sourceTable = source.table(serializer, name),
                targetTable = target.table(serializer, name),
                serializer = serializer,
                modeProvider = { tableModes[name] ?: defaultMode },
                retryQueue = retryQueue as RetryQueue<RetryOperation<T>>,
                context = context
            )
        } as MigrationTable<T>
    }

    // Mode management
    fun setMode(mode: MigrationMode)
    fun setTableMode(tableName: String, mode: MigrationMode)
    fun getTableMode(tableName: String): MigrationMode

    // Status
    fun getStatus(): Map<String, MigrationTableStatus>
    fun getTableStatus(tableName: String): MigrationTableStatus?

    // Backfill API
    suspend fun <T : HasId<ID>, ID : Comparable<ID>> startBackfill(
        tableName: String,
        serializer: KSerializer<T>,
        idPath: DataClassPath<T, ID>,
        config: BackfillConfig = BackfillConfig()
    ): BackfillJob<T, ID>

    // Verification API
    suspend fun <T : HasId<ID>, ID> verifySync(
        tableName: String,
        serializer: KSerializer<T>,
        idPath: DataClassPath<T, ID>,
        sampleSize: Int = 100
    ): SyncVerificationResult

    // Health check combines both databases
    override suspend fun healthCheck(): HealthStatus

    // Connect/disconnect both
    override suspend fun connect()
    override suspend fun disconnect()
}

@Serializable
data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 100,
    val maxDelayMs: Long = 5000
)

@Serializable
data class BackfillConfig(
    val batchSize: Int = 1000,
    val delayBetweenBatchesMs: Long = 0,  // Rate limiting
    val maxErrorsBeforePause: Int = 100,
    val continueOnError: Boolean = true
)
```

### Step 6: Implement BackfillJob

**BackfillJob.kt**

```kotlin
class BackfillJob<T : HasId<ID>, ID : Comparable<ID>>(
    val tableName: String,
    val sourceTable: Table<T>,
    val targetTable: Table<T>,
    val serializer: KSerializer<T>,
    val idPath: DataClassPath<T, ID>,
    val idSerializer: KSerializer<ID>,
    val config: BackfillConfig,
    val statusCallback: (BackfillStatus) -> Unit
) {
    private val status = AtomicRef(BackfillStatus(...))
    private var job: Job? = null

    val currentStatus: BackfillStatus get() = status.value
    val isRunning: Boolean get() = job?.isActive == true

    suspend fun start() {
        require(job == null || !job!!.isActive) { "Backfill already running" }

        job = scope.launch {
            runBackfill()
        }
    }

    suspend fun pause() {
        job?.cancel()
        updateStatus { copy(state = BackfillState.PAUSED) }
    }

    suspend fun resume() {
        start() // Will continue from lastProcessedId
    }

    suspend fun awaitCompletion(): BackfillStatus {
        job?.join()
        return currentStatus
    }

    private suspend fun runBackfill() {
        updateStatus { copy(state = BackfillState.IN_PROGRESS) }

        // Get initial count estimate
        val totalEstimate = sourceTable.count()
        updateStatus { copy(totalEstimate = totalEstimate.toLong()) }

        var lastId: ID? = status.value.lastProcessedId?.let {
            Json.decodeFromString(idSerializer, it)
        }
        var processedCount = status.value.processedCount
        var errorCount = status.value.errorCount
        val errors = mutableListOf<BackfillError>()

        try {
            while (isActive) {
                // Build condition for next batch
                val condition: Condition<T> = if (lastId != null) {
                    idPath greaterThan lastId!!
                } else {
                    Condition.Always
                }

                val batch = sourceTable.find(
                    condition = condition,
                    orderBy = listOf(SortPart(idPath, ascending = true)),
                    limit = config.batchSize
                ).toList()

                if (batch.isEmpty()) {
                    // Backfill complete
                    updateStatus {
                        copy(
                            state = BackfillState.COMPLETED,
                            completedAt = Clock.System.now()
                        )
                    }
                    return
                }

                // Process batch
                for (record in batch) {
                    try {
                        val id = idPath.get(record)

                        // Upsert handles both new records and concurrent modifications
                        targetTable.upsertOneIgnoringResult(
                            condition = idPath eq id,
                            modification = Modification.Assign(record),
                            model = record
                        )

                        processedCount++
                        lastId = id

                    } catch (e: Exception) {
                        errorCount++
                        val id = try { idPath.get(record).toString() } catch (_: Exception) { "unknown" }
                        errors.add(BackfillError(id, e.message ?: "Unknown error", Clock.System.now()))

                        if (!config.continueOnError) {
                            throw e
                        }

                        if (errorCount >= config.maxErrorsBeforePause) {
                            updateStatus {
                                copy(
                                    state = BackfillState.PAUSED,
                                    processedCount = processedCount,
                                    errorCount = errorCount,
                                    lastProcessedId = lastId?.let { Json.encodeToString(idSerializer, it) },
                                    errors = errors.takeLast(100)
                                )
                            }
                            return
                        }
                    }
                }

                // Update status after each batch
                updateStatus {
                    copy(
                        processedCount = processedCount,
                        errorCount = errorCount,
                        lastProcessedId = lastId?.let { Json.encodeToString(idSerializer, it) },
                        updatedAt = Clock.System.now()
                    )
                }

                // Optional delay for rate limiting
                if (config.delayBetweenBatchesMs > 0) {
                    delay(config.delayBetweenBatchesMs)
                }
            }
        } catch (e: CancellationException) {
            updateStatus { copy(state = BackfillState.PAUSED) }
            throw e
        } catch (e: Exception) {
            updateStatus {
                copy(
                    state = BackfillState.FAILED,
                    errors = errors + BackfillError("batch", e.message ?: "Unknown error", Clock.System.now())
                )
            }
        }
    }

    private fun updateStatus(block: BackfillStatus.() -> BackfillStatus) {
        status.update(block)
        statusCallback(status.value)
    }
}
```

### Step 7: Register URL Scheme

Add to MigrationDatabase companion object:

```kotlin
companion object {
    init {
        Database.Settings.register("migration") { name, url, context ->
            // Parse URL: migration://?source=...&target=...&mode=...
            val params = parseQueryParams(url)

            val sourceUrl = params["source"]
                ?: throw IllegalArgumentException("migration:// requires 'source' parameter")
            val targetUrl = params["target"]
                ?: throw IllegalArgumentException("migration:// requires 'target' parameter")
            val mode = params["mode"]?.let { MigrationMode.valueOf(it.uppercase()) }
                ?: MigrationMode.SOURCE_ONLY

            MigrationDatabase(
                name = name,
                source = Database.Settings(sourceUrl).invoke("$name-source", context),
                target = Database.Settings(targetUrl).invoke("$name-target", context),
                context = context,
                defaultMode = mode
            )
        }
    }
}
```

URL format:
```
migration://?source=mongodb://old-server/db&target=postgresql://new-server/db&mode=dual_write_read_source
```

### Step 8: Write Tests

**MigrationDatabaseTest.kt**

Test scenarios using two InMemoryDatabase instances:

1. **Mode routing tests**
   - SOURCE_ONLY: reads/writes go to source only
   - TARGET_ONLY: reads/writes go to target only
   - DUAL_WRITE_READ_SOURCE: writes to both, reads from source
   - DUAL_WRITE_READ_TARGET: writes to both, reads from target

2. **Dual-write consistency tests**
   - Insert appears in both databases
   - Update appears in both databases
   - Delete appears in both databases
   - Upsert appears in both databases

3. **Retry queue tests**
   - Failed secondary write is queued
   - Retry succeeds on transient failure
   - Max retries exceeded triggers callback

4. **Backfill tests**
   - Empty source completes immediately
   - All records copied to target
   - Resume from checkpoint works
   - Concurrent modifications during backfill handled
   - Error handling and pause on max errors

5. **Verification tests**
   - Counts match detection
   - Missing records detection
   - Different records detection

6. **Mode transition tests**
   - SOURCE_ONLY → DUAL_WRITE_READ_SOURCE
   - DUAL_WRITE_READ_SOURCE → DUAL_WRITE_READ_TARGET
   - DUAL_WRITE_READ_TARGET → TARGET_ONLY

7. **Health check tests**
   - Healthy when both databases healthy
   - Degraded when secondary unhealthy
   - Unhealthy when primary unhealthy

## File Checklist

- [x] `database-migration/build.gradle.kts`
- [x] `settings.gradle.kts` (add module)
- [x] `MigrationMode.kt`
- [x] `MigrationStatus.kt` (BackfillStatus, SyncVerificationResult, etc.)
- [x] `RetryQueue.kt`
- [x] `MigrationTable.kt`
- [x] `MigrationDatabase.kt`
- [x] `BackfillJob.kt`
- [x] `MigrationDatabaseTest.kt`

## Implementation Status

**Completed** - All files implemented and tests passing.

## Usage Example

```kotlin
// Configuration
val settings = Database.Settings(
    "migration://?source=mongodb://old/db&target=postgresql://new/db&mode=source_only"
)

// Or programmatic setup
val migrationDb = MigrationDatabase(
    name = "main",
    source = mongoDb,
    target = postgresDb,
    context = context,
    defaultMode = MigrationMode.SOURCE_ONLY
)

// Step 1: Enable dual-write
migrationDb.setMode(MigrationMode.DUAL_WRITE_READ_SOURCE)

// Step 2: Start backfill
val backfillJob = migrationDb.startBackfill(
    tableName = "User",
    serializer = User.serializer(),
    idPath = User.path._id,
    config = BackfillConfig(batchSize = 1000)
)

// Monitor progress
backfillJob.currentStatus.let { status ->
    println("Progress: ${status.processedCount}/${status.totalEstimate}")
}

// Wait for completion
val finalStatus = backfillJob.awaitCompletion()

// Step 3: Verify sync
val verification = migrationDb.verifySync(
    tableName = "User",
    serializer = User.serializer(),
    idPath = User.path._id,
    sampleSize = 1000
)

if (verification.countMatches && verification.missingInTarget == 0) {
    // Step 4: Switch reads to target
    migrationDb.setMode(MigrationMode.DUAL_WRITE_READ_TARGET)

    // After validation period...

    // Step 5: Complete migration
    migrationDb.setMode(MigrationMode.TARGET_ONLY)
}
```

## Future Enhancements (Out of Scope)

1. **Persistent retry queue** - Use external queue (Redis, SQS) for durability
2. **Schema migration support** - Transform records between schemas
3. **Bidirectional sync** - Support rollback by syncing target→source
4. **Metrics/observability** - OpenTelemetry integration for monitoring
5. **Admin API** - REST endpoints for monitoring and control
6. **Automatic cutover** - Auto-switch modes based on sync verification
