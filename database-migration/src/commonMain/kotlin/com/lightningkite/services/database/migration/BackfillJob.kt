package com.lightningkite.services.database.migration

import com.lightningkite.services.database.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Manages a backfill operation to copy data from source to target database.
 *
 * BackfillJob provides:
 * - Resumable batch processing
 * - Progress tracking
 * - Error handling with configurable thresholds
 * - Pause/resume capability
 *
 * ## Usage
 *
 * ```kotlin
 * val job = migrationDb.startBackfill<User, UUID>(
 *     tableName = "User",
 *     serializer = User.serializer(),
 *     idPath = User.path._id,
 *     idSerializer = UUIDSerializer,
 *     config = BackfillConfig(batchSize = 1000)
 * )
 *
 * // Start the backfill
 * job.start()
 *
 * // Monitor progress
 * println("Progress: ${job.currentStatus.progressPercent}%")
 *
 * // Wait for completion
 * val finalStatus = job.awaitCompletion()
 * ```
 *
 * ## Concurrent Modifications
 *
 * The backfill uses upsert operations, which safely handle records that are
 * modified concurrently during the backfill. Records modified via dual-write
 * will have the latest version in the target database.
 *
 * @param T The model type
 * @param ID The ID type (must be Comparable for ordering)
 */
public class BackfillJob<T : HasId<ID>, ID : Comparable<ID>>(
    public val tableName: String,
    public val sourceTable: Table<T>,
    public val targetTable: Table<T>,
    public val serializer: KSerializer<T>,
    public val idPath: DataClassPath<T, ID>,
    public val idSerializer: KSerializer<ID>,
    public val config: BackfillConfig,
    private val clock: Clock,
    private val statusCallback: (BackfillStatus) -> Unit = {}
) {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = atomic(
        BackfillStatus(
            state = BackfillState.NOT_STARTED,
            startedAt = clock.now(),
            updatedAt = clock.now()
        )
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    /** Current backfill status */
    public val currentStatus: BackfillStatus get() = _status.value

    /** Whether the backfill is currently running */
    public val isRunning: Boolean get() = job?.isActive == true

    /** Whether the backfill has completed (successfully or with errors) */
    public val isComplete: Boolean get() = currentStatus.state in setOf(
        BackfillState.COMPLETED,
        BackfillState.FAILED
    )

    /**
     * Start or resume the backfill operation.
     *
     * If the backfill was previously paused, it will resume from the last
     * successfully processed record.
     *
     * @throws IllegalStateException if backfill is already running
     */
    public fun start() {
        require(job == null || !job!!.isActive) { "Backfill already running" }

        job = scope.launch {
            runBackfill()
        }
    }

    /**
     * Pause the backfill operation.
     *
     * The current batch will complete, then the backfill will pause.
     * Progress is saved and can be resumed with [start].
     */
    public fun pause() {
        job?.cancel()
        updateStatus { copy(state = BackfillState.PAUSED, updatedAt = clock.now()) }
    }

    /**
     * Resume a paused backfill.
     *
     * Equivalent to calling [start] on a paused backfill.
     */
    public fun resume() {
        start()
    }

    /**
     * Wait for the backfill to complete.
     *
     * @return The final backfill status
     */
    public suspend fun awaitCompletion(): BackfillStatus {
        job?.join()
        return currentStatus
    }

    /**
     * Cancel the backfill operation.
     *
     * Unlike [pause], this marks the backfill as failed and it cannot be resumed.
     */
    public fun cancel() {
        job?.cancel()
        updateStatus {
            copy(
                state = BackfillState.FAILED,
                updatedAt = clock.now(),
                errors = errors + BackfillError(
                    recordId = "N/A",
                    message = "Backfill cancelled by user",
                    timestamp = clock.now()
                )
            )
        }
    }

    private suspend fun runBackfill() {
        updateStatus {
            copy(
                state = BackfillState.IN_PROGRESS,
                startedAt = if (state == BackfillState.NOT_STARTED) clock.now() else startedAt,
                updatedAt = clock.now()
            )
        }

        // Get initial count estimate
        try {
            val totalEstimate = sourceTable.count().toLong()
            updateStatus { copy(totalEstimate = totalEstimate) }
            logger.info { "Starting backfill for $tableName: estimated $totalEstimate records" }
        } catch (e: Exception) {
            logger.warn(e) { "Could not get initial count estimate for $tableName" }
        }

        var lastId: ID? = _status.value.lastProcessedId?.let {
            try {
                json.decodeFromString(idSerializer, it)
            } catch (e: Exception) {
                logger.warn(e) { "Could not deserialize last processed ID, starting from beginning" }
                null
            }
        }

        var processedCount = _status.value.processedCount
        var errorCount = _status.value.errorCount
        val errors = _status.value.errors.toMutableList()

        try {
            while (scope.isActive) {
                // Build condition for next batch
                val condition: Condition<T> = lastId?.let { idPath gt it } ?: Condition.Always

                val batch = sourceTable.find(
                    condition = condition,
                    orderBy = listOf(SortPart(idPath, ascending = true)),
                    limit = config.batchSize
                ).toList()

                if (batch.isEmpty()) {
                    // Backfill complete!
                    updateStatus {
                        copy(
                            state = BackfillState.COMPLETED,
                            processedCount = processedCount,
                            errorCount = errorCount,
                            completedAt = clock.now(),
                            updatedAt = clock.now()
                        )
                    }
                    logger.info { "Backfill completed for $tableName: $processedCount records processed, $errorCount errors" }
                    return
                }

                // Process batch
                for (record in batch) {
                    if (!scope.isActive) {
                        // Job was cancelled, save progress
                        updateStatus {
                            copy(
                                state = BackfillState.PAUSED,
                                processedCount = processedCount,
                                errorCount = errorCount,
                                lastProcessedId = lastId?.let { id -> json.encodeToString(idSerializer, id) },
                                updatedAt = clock.now(),
                                errors = errors.takeLast(config.maxErrorsToRetain)
                            )
                        }
                        return
                    }

                    try {
                        val id = idPath.get(record)
                            ?: throw IllegalStateException("Record has null ID")

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
                        val idStr = try {
                            idPath.get(record).toString()
                        } catch (_: Exception) {
                            "unknown"
                        }

                        errors.add(BackfillError(idStr, e.message ?: "Unknown error", clock.now()))
                        logger.error(e) { "Failed to migrate record $idStr in $tableName" }

                        if (!config.continueOnError) {
                            updateStatus {
                                copy(
                                    state = BackfillState.FAILED,
                                    processedCount = processedCount,
                                    errorCount = errorCount,
                                    lastProcessedId = lastId?.let { id -> json.encodeToString(idSerializer, id) },
                                    updatedAt = clock.now(),
                                    errors = errors.takeLast(config.maxErrorsToRetain)
                                )
                            }
                            throw e
                        }

                        if (errorCount >= config.maxErrorsBeforePause) {
                            updateStatus {
                                copy(
                                    state = BackfillState.PAUSED,
                                    processedCount = processedCount,
                                    errorCount = errorCount,
                                    lastProcessedId = lastId?.let { id -> json.encodeToString(idSerializer, id) },
                                    updatedAt = clock.now(),
                                    errors = errors.takeLast(config.maxErrorsToRetain)
                                )
                            }
                            logger.warn { "Backfill paused for $tableName: max errors reached ($errorCount)" }
                            return
                        }
                    }
                }

                // Update status after each batch
                updateStatus {
                    copy(
                        processedCount = processedCount,
                        errorCount = errorCount,
                        lastProcessedId = lastId?.let { json.encodeToString(idSerializer, it) },
                        updatedAt = clock.now(),
                        errors = errors.takeLast(config.maxErrorsToRetain)
                    )
                }

                logger.debug { "Backfill progress for $tableName: $processedCount records" }

                // Optional delay for rate limiting
                if (config.delayBetweenBatchesMs > 0) {
                    delay(config.delayBetweenBatchesMs)
                }
            }
        } catch (e: CancellationException) {
            // Normal cancellation, status already updated
            throw e
        } catch (e: Exception) {
            updateStatus {
                copy(
                    state = BackfillState.FAILED,
                    processedCount = processedCount,
                    errorCount = errorCount,
                    lastProcessedId = lastId?.let { json.encodeToString(idSerializer, it) },
                    updatedAt = clock.now(),
                    errors = (errors + BackfillError(
                        "batch",
                        e.message ?: "Unknown error",
                        clock.now()
                    )).takeLast(config.maxErrorsToRetain)
                )
            }
            logger.error(e) { "Backfill failed for $tableName" }
        }
    }

    private fun updateStatus(block: BackfillStatus.() -> BackfillStatus) {
        _status.update(block)
        statusCallback(_status.value)
    }
}
