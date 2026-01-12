package com.lightningkite.services.database.migration

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Status of a single table within a migration.
 */
@Serializable
public data class MigrationTableStatus(
    val tableName: String,
    val mode: MigrationMode,
    val backfillStatus: BackfillStatus? = null
)

/**
 * Status of an ongoing or completed backfill operation.
 */
@Serializable
public data class BackfillStatus(
    val state: BackfillState,
    /** Estimated total records (from initial count, may change during backfill) */
    val totalEstimate: Long? = null,
    /** Number of records successfully processed */
    val processedCount: Long = 0,
    /** Number of records that failed to process */
    val errorCount: Long = 0,
    /** Serialized ID of last successfully processed record (for resume) */
    val lastProcessedId: String? = null,
    /** When the backfill was started */
    val startedAt: Instant,
    /** When the status was last updated */
    val updatedAt: Instant,
    /** When the backfill completed (null if not yet complete) */
    val completedAt: Instant? = null,
    /** Recent errors (capped to prevent unbounded growth) */
    val errors: List<BackfillError> = emptyList()
) {
    public val progressPercent: Double?
        get() = totalEstimate?.let { total ->
            if (total == 0L) 100.0
            else (processedCount.toDouble() / total * 100).coerceIn(0.0, 100.0)
        }

    public val isComplete: Boolean
        get() = state == BackfillState.COMPLETED

    public val isFailed: Boolean
        get() = state == BackfillState.FAILED
}

/**
 * State of a backfill operation.
 */
@Serializable
public enum class BackfillState {
    /** Backfill has not been started yet */
    NOT_STARTED,
    /** Backfill is currently running */
    IN_PROGRESS,
    /** Backfill was paused (can be resumed) */
    PAUSED,
    /** Backfill completed successfully */
    COMPLETED,
    /** Backfill failed with unrecoverable error */
    FAILED
}

/**
 * Information about a single error during backfill.
 */
@Serializable
public data class BackfillError(
    /** ID of the record that failed (as string) */
    val recordId: String,
    /** Error message */
    val message: String,
    /** When the error occurred */
    val timestamp: Instant
)

/**
 * Result of verifying sync between source and target databases.
 */
@Serializable
public data class SyncVerificationResult(
    val tableName: String,
    /** Number of records in source database */
    val sourceCount: Int,
    /** Number of records in target database */
    val targetCount: Int,
    /** Whether counts match */
    val countMatches: Boolean,
    /** Number of records sampled for comparison */
    val sampledRecords: Int,
    /** Number of sampled records that match exactly */
    val matchingRecords: Int,
    /** Number of sampled records missing in target */
    val missingInTarget: Int,
    /** Number of sampled records that differ between source and target */
    val differentInTarget: Int,
    /** When verification was performed */
    val verifiedAt: Instant
) {
    /** Whether the sample indicates databases are in sync */
    public val inSync: Boolean
        get() = countMatches && missingInTarget == 0 && differentInTarget == 0

    /** Percentage of sampled records that match */
    public val matchPercent: Double
        get() = if (sampledRecords == 0) 100.0
        else (matchingRecords.toDouble() / sampledRecords * 100)
}

/**
 * Configuration for retry behavior on failed secondary writes.
 */
@Serializable
public data class RetryConfig(
    /** Maximum number of retry attempts */
    val maxRetries: Int = 3,
    /** Initial delay between retries in milliseconds */
    val initialDelayMs: Long = 100,
    /** Maximum delay between retries in milliseconds */
    val maxDelayMs: Long = 5000,
    /** Maximum number of items to hold in retry queue before dropping oldest */
    val maxQueueSize: Int = 10000
)

/**
 * Configuration for backfill operations.
 */
@Serializable
public data class BackfillConfig(
    /** Number of records to process in each batch */
    val batchSize: Int = 1000,
    /** Delay between batches in milliseconds (for rate limiting) */
    val delayBetweenBatchesMs: Long = 0,
    /** Maximum errors before automatically pausing */
    val maxErrorsBeforePause: Int = 100,
    /** Whether to continue processing after individual record errors */
    val continueOnError: Boolean = true,
    /** Maximum number of errors to retain in status */
    val maxErrorsToRetain: Int = 100
)
