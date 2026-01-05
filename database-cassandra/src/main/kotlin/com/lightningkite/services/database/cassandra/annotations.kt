package com.lightningkite.services.database.cassandra

// =============================================================================
// CASSANDRA-SPECIFIC ANNOTATIONS
// =============================================================================
// WARNING: Using these annotations ties your model to Cassandra and violates
// the database abstraction. Prefer using the standard @Index annotation from
// com.lightningkite.services.data.Index which works across all database backends.
//
// These annotations exist for advanced Cassandra optimization but should be
// avoided in most cases to maintain database portability.
// =============================================================================

/**
 * Marks a property as part of the partition key.
 * Multiple properties can be marked; order determines composite key order.
 *
 * **WARNING**: Using this annotation ties your model to Cassandra and breaks
 * database abstraction. By default, `_id` is used as the partition key, which
 * is compatible with all database backends.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class PartitionKey(val order: Int = 0)

/**
 * Marks a property as a clustering column.
 * Order determines clustering order; descending determines sort direction.
 *
 * **WARNING**: Using this annotation ties your model to Cassandra and breaks
 * database abstraction. Consider if you really need Cassandra-specific
 * clustering behavior.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ClusteringColumn(
    val order: Int = 0,
    val descending: Boolean = false
)

/**
 * Creates a SASI index on the property for text search.
 *
 * **WARNING**: Prefer using `@Index` from `com.lightningkite.services.data.Index` for
 * database-agnostic indexing. Only use this for SASI-specific features like
 * CONTAINS mode or text analyzers that aren't available with SAI.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SasiIndex(
    val mode: SasiMode = SasiMode.PREFIX,
    val caseSensitive: Boolean = true,
    val analyzer: SasiAnalyzer = SasiAnalyzer.NONE
)

/**
 * SASI index modes for different query patterns.
 */
public enum class SasiMode {
    /** Supports prefix matching (LIKE 'value%') */
    PREFIX,
    /** Supports contains matching (LIKE '%value%') - higher storage overhead */
    CONTAINS,
    /** Optimized for numeric range queries */
    SPARSE
}

/**
 * SASI analyzer options for text processing.
 */
public enum class SasiAnalyzer {
    /** No text analysis */
    NONE,
    /** Standard tokenizing analyzer with stemming */
    STANDARD,
    /** Non-tokenizing analyzer (treats entire value as single token) */
    NON_TOKENIZING
}

/**
 * Creates a SAI (Storage Attached Index) on the property.
 * SAI is available in Cassandra 5.0+ and provides better performance than SASI.
 *
 * @deprecated Use `@Index` from `com.lightningkite.services.data.Index` instead.
 * The standard `@Index` annotation is automatically treated as a SAI index in Cassandra,
 * and works across all database backends (MongoDB, PostgreSQL, etc.).
 */
@Deprecated(
    message = "Use @Index from com.lightningkite.services.data.Index instead for database-agnostic indexing",
    replaceWith = ReplaceWith("Index", "com.lightningkite.services.data.Index")
)
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SaiIndex

/**
 * Declares a computed column derived from other properties.
 * The driver auto-populates this on insert/update.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ComputedColumn(
    val transform: ComputedTransform,
    val sourceProperties: Array<String> = []
)

/**
 * Transformations available for computed columns.
 */
public enum class ComputedTransform {
    /** Convert source string to lowercase */
    LOWERCASE,
    /** Reverse the source string */
    REVERSED,
    /** Compute geohash from [latitude, longitude] source properties */
    GEOHASH,
    /** Lower precision geohash (precision 4) */
    GEOHASH_PRECISION_4,
    /** Higher precision geohash (precision 6) */
    GEOHASH_PRECISION_6
}

/**
 * Declares a materialized view for alternative query patterns.
 * Materialized views allow querying by different partition keys.
 *
 * **WARNING**: Using this annotation ties your model to Cassandra and breaks
 * database abstraction. Materialized views are a Cassandra-specific optimization.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
public annotation class MaterializedView(
    val name: String,
    val partitionKey: Array<String>,
    val clusteringColumns: Array<String> = [],
    val clusteringOrder: Array<String> = [] // e.g., ["score DESC", "_id ASC"]
)
