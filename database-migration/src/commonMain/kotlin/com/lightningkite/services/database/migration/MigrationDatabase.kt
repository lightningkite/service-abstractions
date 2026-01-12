package com.lightningkite.services.database.migration

import com.lightningkite.services.*
import com.lightningkite.services.database.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * A Database wrapper that enables zero-downtime migrations between database implementations.
 *
 * MigrationDatabase wraps two database instances (source and target) and supports:
 * - **Dual-write**: Write to both databases simultaneously
 * - **Read routing**: Route reads to source or target based on migration phase
 * - **Backfill**: Copy existing data from source to target
 * - **Verification**: Compare databases to ensure sync
 *
 * ## Migration Workflow
 *
 * 1. Start with [MigrationMode.SOURCE_ONLY] (normal operation)
 * 2. Enable [MigrationMode.DUAL_WRITE_READ_SOURCE] to start dual-write
 * 3. Run backfill to copy existing data: [startBackfill]
 * 4. Verify sync: [verifySync]
 * 5. Switch to [MigrationMode.DUAL_WRITE_READ_TARGET] to validate target reads
 * 6. Complete migration with [MigrationMode.TARGET_ONLY]
 *
 * ## Example
 *
 * ```kotlin
 * val migrationDb = MigrationDatabase(
 *     name = "main",
 *     source = mongoDb,
 *     target = postgresDb,
 *     context = context
 * )
 *
 * // Enable dual-write
 * migrationDb.setMode(MigrationMode.DUAL_WRITE_READ_SOURCE)
 *
 * // Backfill
 * val job = migrationDb.startBackfill<User, UUID>(
 *     tableName = "User",
 *     serializer = User.serializer(),
 *     idPath = User.path._id
 * )
 * job.awaitCompletion()
 *
 * // Verify and switch
 * val result = migrationDb.verifySync<User, UUID>("User", User.serializer(), User.path._id)
 * if (result.inSync) {
 *     migrationDb.setMode(MigrationMode.TARGET_ONLY)
 * }
 * ```
 */
public class MigrationDatabase(
    override val name: String,
    public val source: Database,
    public val target: Database,
    override val context: SettingContext,
    defaultMode: MigrationMode = MigrationMode.SOURCE_ONLY,
    public val retryConfig: RetryConfig = RetryConfig()
) : Database {

    private val logger = KotlinLogging.logger {}

    // Current default mode
    private val _defaultMode = atomic(defaultMode)

    /** Current default migration mode */
    public var defaultMode: MigrationMode
        get() = _defaultMode.value
        set(value) {
            logger.info { "Changing default migration mode from ${_defaultMode.value} to $value" }
            _defaultMode.value = value
        }

    // Per-table mode overrides
    private val tableModes = mutableMapOf<String, MigrationMode>()

    // Per-table backfill status
    private val backfillStatuses = mutableMapOf<String, BackfillStatus>()

    // Table cache
    private val tables = mutableMapOf<Pair<KSerializer<*>, String>, MigrationTable<*>>()

    // Coroutine scope for retry processing
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Retry queues per table (created on demand)
    private val retryQueues = mutableMapOf<String, RetryQueue<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getOrCreateRetryQueue(tableName: String): RetryQueue<MigrationTable.RetryOperation<T>> {
        return retryQueues.getOrPut(tableName) {
            val queue = RetryQueue<MigrationTable.RetryOperation<T>>(
                config = retryConfig,
                clock = context.clock,
                onMaxRetriesExceeded = { op, error ->
                    logger.error(error) {
                        "Max retries exceeded for operation on table $tableName: $op"
                    }
                }
            )
            // Start the processor
            queue.start(scope) { operation ->
                val table = tables.entries.find { it.key.second == tableName }?.value
                    ?: throw IllegalStateException("Table $tableName not found for retry")
                executeRetryOperation(table as MigrationTable<T>, operation)
            }
            queue
        } as RetryQueue<MigrationTable.RetryOperation<T>>
    }

    private suspend fun <T : Any> executeRetryOperation(
        table: MigrationTable<T>,
        operation: MigrationTable.RetryOperation<T>
    ) {
        val secondaryTable = when (table.currentMode) {
            MigrationMode.SOURCE_ONLY, MigrationMode.TARGET_ONLY -> return
            MigrationMode.DUAL_WRITE_READ_SOURCE -> table.targetTable
            MigrationMode.DUAL_WRITE_READ_TARGET -> table.sourceTable
        }

        when (operation) {
            is MigrationTable.RetryOperation.Insert -> secondaryTable.insert(operation.models)
            is MigrationTable.RetryOperation.Replace -> secondaryTable.replaceOneIgnoringResult(
                operation.condition, operation.model, operation.orderBy
            )
            is MigrationTable.RetryOperation.Upsert -> secondaryTable.upsertOneIgnoringResult(
                operation.condition, operation.modification, operation.model
            )
            is MigrationTable.RetryOperation.UpdateOne -> secondaryTable.updateOneIgnoringResult(
                operation.condition, operation.modification, operation.orderBy
            )
            is MigrationTable.RetryOperation.UpdateMany -> secondaryTable.updateManyIgnoringResult(
                operation.condition, operation.modification
            )
            is MigrationTable.RetryOperation.DeleteOne -> secondaryTable.deleteOneIgnoringOld(
                operation.condition, operation.orderBy
            )
            is MigrationTable.RetryOperation.DeleteMany -> secondaryTable.deleteManyIgnoringOld(
                operation.condition
            )
        }
    }

    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> {
        @Suppress("UNCHECKED_CAST")
        return tables.getOrPut(serializer to name) {
            MigrationTable(
                sourceTable = source.table(serializer, name),
                targetTable = target.table(serializer, name),
                serializer = serializer,
                modeProvider = { tableModes[name] ?: defaultMode },
                retryQueue = getOrCreateRetryQueue(name)
            )
        } as MigrationTable<T>
    }

    // ===== Mode Management =====

    /**
     * Set the default migration mode for all tables.
     */
    public fun setMode(mode: MigrationMode) {
        defaultMode = mode
    }

    /**
     * Set the migration mode for a specific table.
     * This overrides the default mode for this table only.
     */
    public fun setTableMode(tableName: String, mode: MigrationMode) {
        logger.info { "Setting migration mode for table $tableName to $mode" }
        tableModes[tableName] = mode
    }

    /**
     * Get the current migration mode for a specific table.
     */
    public fun getTableMode(tableName: String): MigrationMode {
        return tableModes[tableName] ?: defaultMode
    }

    /**
     * Clear any table-specific mode override, reverting to the default mode.
     */
    public fun clearTableMode(tableName: String) {
        tableModes.remove(tableName)
    }

    // ===== Status =====

    /**
     * Get the migration status for all tables.
     */
    public fun getStatus(): Map<String, MigrationTableStatus> {
        val allTableNames = tables.keys.map { it.second }.toSet() + backfillStatuses.keys
        return allTableNames.associateWith { tableName ->
            MigrationTableStatus(
                tableName = tableName,
                mode = getTableMode(tableName),
                backfillStatus = backfillStatuses[tableName]
            )
        }
    }

    /**
     * Get the migration status for a specific table.
     */
    public fun getTableStatus(tableName: String): MigrationTableStatus {
        return MigrationTableStatus(
            tableName = tableName,
            mode = getTableMode(tableName),
            backfillStatus = backfillStatuses[tableName]
        )
    }

    // ===== Backfill =====

    /**
     * Start a backfill operation for a table.
     *
     * The backfill copies all records from source to target using upsert,
     * which safely handles concurrent modifications during the backfill.
     *
     * @param tableName Name of the table to backfill
     * @param serializer Serializer for the model type
     * @param idPath Path to the ID field for ordering and lookup
     * @param idSerializer Serializer for the ID type (for checkpoint persistence)
     * @param config Configuration for batch size, error handling, etc.
     * @return BackfillJob that can be used to monitor and control the backfill
     */
    public fun <T : HasId<ID>, ID : Comparable<ID>> startBackfill(
        tableName: String,
        serializer: KSerializer<T>,
        idPath: DataClassPath<T, ID>,
        idSerializer: KSerializer<ID>,
        config: BackfillConfig = BackfillConfig()
    ): BackfillJob<T, ID> {
        val sourceTable = source.table(serializer, tableName)
        val targetTable = target.table(serializer, tableName)

        val job = BackfillJob(
            tableName = tableName,
            sourceTable = sourceTable,
            targetTable = targetTable,
            serializer = serializer,
            idPath = idPath,
            idSerializer = idSerializer,
            config = config,
            clock = context.clock
        ) { status ->
            backfillStatuses[tableName] = status
        }

        return job
    }

    // ===== Verification =====

    /**
     * Verify that source and target databases are in sync for a table.
     *
     * Performs:
     * 1. Count comparison
     * 2. Random sample comparison
     *
     * @param tableName Name of the table to verify
     * @param serializer Serializer for the model type
     * @param idPath Path to the ID field for record lookup
     * @param sampleSize Number of records to sample for comparison
     * @return Verification result with counts and match statistics
     */
    public suspend fun <T : HasId<ID>, ID> verifySync(
        tableName: String,
        serializer: KSerializer<T>,
        idPath: DataClassPath<T, ID>,
        sampleSize: Int = 100
    ): SyncVerificationResult {
        val sourceTable = source.table(serializer, tableName)
        val targetTable = target.table(serializer, tableName)

        val sourceCount = sourceTable.count()
        val targetCount = targetTable.count()

        // Sample records from source
        val sourceRecords = sourceTable.find(
            condition = Condition.Always,
            limit = sampleSize
        ).toList()

        var matching = 0
        var missing = 0
        var different = 0

        for (sourceRecord in sourceRecords) {
            val id = idPath.get(sourceRecord) ?: continue // Skip records with null IDs
            val targetRecord = targetTable.find(idPath eq id).firstOrNull()

            when {
                targetRecord == null -> missing++
                targetRecord != sourceRecord -> different++
                else -> matching++
            }
        }

        return SyncVerificationResult(
            tableName = tableName,
            sourceCount = sourceCount,
            targetCount = targetCount,
            countMatches = sourceCount == targetCount,
            sampledRecords = sourceRecords.size,
            matchingRecords = matching,
            missingInTarget = missing,
            differentInTarget = different,
            verifiedAt = context.clock.now()
        )
    }

    // ===== Service Lifecycle =====

    override val healthCheckFrequency: Duration
        get() = minOf(source.healthCheckFrequency, target.healthCheckFrequency)

    override suspend fun healthCheck(): HealthStatus {
        val sourceHealth = source.healthCheck()
        val targetHealth = target.healthCheck()

        // Combine health statuses
        val combinedLevel = when {
            sourceHealth.level == HealthStatus.Level.ERROR || targetHealth.level == HealthStatus.Level.ERROR ->
                HealthStatus.Level.ERROR
            sourceHealth.level == HealthStatus.Level.URGENT || targetHealth.level == HealthStatus.Level.URGENT ->
                HealthStatus.Level.URGENT
            sourceHealth.level == HealthStatus.Level.WARNING || targetHealth.level == HealthStatus.Level.WARNING ->
                HealthStatus.Level.WARNING
            else -> HealthStatus.Level.OK
        }

        val message = buildString {
            append("Mode: $defaultMode")
            if (sourceHealth.additionalMessage != null || targetHealth.additionalMessage != null) {
                append(" | Source: ${sourceHealth.additionalMessage ?: "OK"}")
                append(" | Target: ${targetHealth.additionalMessage ?: "OK"}")
            }
        }

        return HealthStatus(combinedLevel, additionalMessage = message)
    }

    override suspend fun connect() {
        source.connect()
        target.connect()
    }

    override suspend fun disconnect() {
        // Stop retry queues
        retryQueues.values.forEach { it.stop() }
        retryQueues.clear()

        source.disconnect()
        target.disconnect()
    }

    public companion object {
        init {
            Database.Settings.register("migration") { name, url, context ->
                val params = parseQueryParams(url.substringAfter("://"))

                val sourceUrl = params["source"]
                    ?: throw IllegalArgumentException("migration:// requires 'source' parameter")
                val targetUrl = params["target"]
                    ?: throw IllegalArgumentException("migration:// requires 'target' parameter")
                val mode = params["mode"]?.let { modeStr ->
                    MigrationMode.entries.find { it.name.equals(modeStr, ignoreCase = true) }
                        ?: throw IllegalArgumentException("Invalid migration mode: $modeStr")
                } ?: MigrationMode.SOURCE_ONLY

                MigrationDatabase(
                    name = name,
                    source = Database.Settings(sourceUrl).invoke("$name-source", context),
                    target = Database.Settings(targetUrl).invoke("$name-target", context),
                    context = context,
                    defaultMode = mode
                )
            }
        }

        private fun parseQueryParams(query: String): Map<String, String> {
            if (query.isBlank() || !query.contains("=")) return emptyMap()

            // Handle the case where there's a ? prefix
            val params = if (query.startsWith("?")) query.substring(1) else query

            return params.split("&")
                .filter { it.contains("=") }
                .associate { param ->
                    val (key, value) = param.split("=", limit = 2)
                    key to decodeUrlComponent(value)
                }
        }

        private fun decodeUrlComponent(value: String): String {
            return value
                .replace("%3A", ":")
                .replace("%2F", "/")
                .replace("%3F", "?")
                .replace("%3D", "=")
                .replace("%26", "&")
                .replace("%25", "%")
                .replace("+", " ")
        }
    }
}

/**
 * Settings for configuring a MigrationDatabase via URL.
 *
 * URL format: `migration://?source=<source-url>&target=<target-url>&mode=<mode>`
 *
 * Example:
 * ```
 * migration://?source=mongodb://old-server/db&target=postgresql://new-server/db&mode=dual_write_read_source
 * ```
 */
@Serializable
@JvmInline
public value class MigrationDatabaseSettings(
    public val url: String
) : Setting<Database> {
    override fun invoke(name: String, context: SettingContext): Database {
        return Database.Settings(url).invoke(name, context)
    }
}
