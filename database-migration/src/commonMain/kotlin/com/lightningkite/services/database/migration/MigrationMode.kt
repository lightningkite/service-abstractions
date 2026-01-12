package com.lightningkite.services.database.migration

import kotlinx.serialization.Serializable

/**
 * Represents the current phase of a database migration.
 *
 * Migrations typically progress through these phases:
 * 1. [SOURCE_ONLY] - Normal operation before migration starts
 * 2. [DUAL_WRITE_READ_SOURCE] - Enable dual-write, start backfill
 * 3. [DUAL_WRITE_READ_TARGET] - Backfill complete, verify by reading from target
 * 4. [TARGET_ONLY] - Migration complete, decommission source
 */
@Serializable
public enum class MigrationMode {
    /**
     * Normal operation - all reads and writes go to source database only.
     * Use this before starting migration or after rolling back.
     */
    SOURCE_ONLY,

    /**
     * Dual-write phase with reads from source.
     * - Writes go to both source and target (source is primary)
     * - Reads come from source
     * - Use during backfill phase
     */
    DUAL_WRITE_READ_SOURCE,

    /**
     * Dual-write phase with reads from target.
     * - Writes go to both source and target (target is primary)
     * - Reads come from target
     * - Use for validation after backfill completes
     */
    DUAL_WRITE_READ_TARGET,

    /**
     * Migration complete - all reads and writes go to target database only.
     * Source database can be decommissioned.
     */
    TARGET_ONLY
}
