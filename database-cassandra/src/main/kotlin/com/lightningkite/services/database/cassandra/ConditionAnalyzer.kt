package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.Condition

/**
 * Result of analyzing a condition for CQL translation.
 */
public data class ConditionAnalysis<T>(
    /** The part of the condition that can be pushed to Cassandra */
    val cqlCondition: Condition<T>?,
    /** The part of the condition that must be evaluated in the application */
    val appFilter: Condition<T>?,
    /** True if no useful CQL condition could be generated (requires full table scan) */
    val requiresFullScan: Boolean,
    /** Performance warnings about the query */
    val warnings: List<String>
)

/**
 * Analyzes conditions to determine which parts can be pushed to Cassandra (CQL)
 * and which must be filtered in the application.
 */
public class ConditionAnalyzer<T : Any>(
    private val schema: CassandraSchema<T>
) {
    /**
     * Analyzes a condition and splits it into CQL-pushable and app-side parts.
     */
    @Suppress("UNCHECKED_CAST")
    public fun analyze(condition: Condition<T>): ConditionAnalysis<T> {
        return when (condition) {
            is Condition.Always -> ConditionAnalysis(
                cqlCondition = Condition.Always as Condition<T>,
                appFilter = null,
                requiresFullScan = false,
                warnings = emptyList()
            )

            is Condition.Never -> ConditionAnalysis(
                cqlCondition = Condition.Never as Condition<T>,
                appFilter = null,
                requiresFullScan = false,
                warnings = emptyList()
            )

            is Condition.And -> analyzeAnd(condition)
            is Condition.Or -> analyzeOr(condition)
            is Condition.Not -> analyzeNot(condition)
            is Condition.OnField<*, *> -> analyzeOnField(condition as Condition.OnField<T, Any?>)

            // Direct value conditions (need field context from OnField)
            else -> ConditionAnalysis(
                cqlCondition = null,
                appFilter = condition,
                requiresFullScan = true,
                warnings = listOf("Condition type ${condition::class.simpleName} requires full scan")
            )
        }
    }

    private fun analyzeAnd(and: Condition.And<T>): ConditionAnalysis<T> {
        val subAnalyses = and.conditions.map { analyze(it) }

        // Combine pushable conditions with AND
        val pushable = subAnalyses.mapNotNull { it.cqlCondition }
            .filter { it !is Condition.Always }
        val appFilters = subAnalyses.mapNotNull { it.appFilter }
        val warnings = subAnalyses.flatMap { it.warnings }

        return ConditionAnalysis(
            cqlCondition = when {
                pushable.isEmpty() -> null
                pushable.size == 1 -> pushable.first()
                else -> Condition.And(pushable)
            },
            appFilter = when {
                appFilters.isEmpty() -> null
                appFilters.size == 1 -> appFilters.first()
                else -> Condition.And(appFilters)
            },
            requiresFullScan = pushable.isEmpty(),
            warnings = warnings
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun analyzeOr(or: Condition.Or<T>): ConditionAnalysis<T> {
        val subAnalyses = or.conditions.map { analyze(it) }

        // For OR: if ANY branch requires app filtering, we can't push ANY of them
        // (we'd miss records matching unpushable branches)
        val allFullyPushable = subAnalyses.all { it.appFilter == null }

        return if (allFullyPushable && schema.supportsSaiOr) {
            // SAI in Cassandra 5.0+ supports OR
            ConditionAnalysis(
                cqlCondition = or,
                appFilter = null,
                requiresFullScan = false,
                warnings = emptyList()
            )
        } else {
            // Can't push OR - must fetch all and filter
            ConditionAnalysis(
                cqlCondition = null,
                appFilter = or,
                requiresFullScan = true,
                warnings = listOf("OR condition requires full scan (not all branches pushable or SAI OR not available)")
            )
        }
    }

    private fun analyzeNot(not: Condition.Not<T>): ConditionAnalysis<T> {
        // CQL has very limited NOT support
        // For now, always fall back to app filtering
        return ConditionAnalysis(
            cqlCondition = null,
            appFilter = not,
            requiresFullScan = true,
            warnings = listOf("NOT conditions require full scan in Cassandra")
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> analyzeOnField(onField: Condition.OnField<T, V>): ConditionAnalysis<T> {
        val fieldName = onField.key.name
        val innerCondition = onField.condition

        return when {
            // Partition key - only equality and IN
            schema.isPartitionKey(fieldName) -> analyzePartitionKeyCondition(onField, innerCondition)

            // Clustering column - equality, IN, and range on last
            schema.isClusteringColumn(fieldName) -> analyzeClusteringCondition(onField, innerCondition)

            // SASI indexed - depends on mode
            schema.hasSasiIndex(fieldName) -> analyzeSasiCondition(onField, innerCondition, fieldName)

            // SAI indexed - equality, IN, range (only if index was actually created)
            schema.hasWorkingSaiIndex(fieldName) -> analyzeSaiCondition(onField, innerCondition)

            // Computed column - check if we should redirect to it
            schema.isComputedFrom(fieldName) != null -> {
                // User queried source column; redirect to computed column if beneficial
                analyzeComputedColumnRedirect(onField, innerCondition, fieldName)
            }

            // Non-indexed column - requires full scan with ALLOW FILTERING
            else -> ConditionAnalysis(
                cqlCondition = null,
                appFilter = onField as Condition<T>,
                requiresFullScan = true,
                warnings = listOf("Column '$fieldName' is not indexed; query requires full scan")
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> analyzePartitionKeyCondition(
        onField: Condition.OnField<T, V>,
        inner: Condition<V>
    ): ConditionAnalysis<T> {
        return when (inner) {
            is Condition.Equal<*>,
            is Condition.Inside<*> -> pushable(onField as Condition<T>)
            else -> appFallback(onField as Condition<T>, "Partition key only supports equality and IN queries")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> analyzeClusteringCondition(
        onField: Condition.OnField<T, V>,
        inner: Condition<V>
    ): ConditionAnalysis<T> {
        return when (inner) {
            is Condition.Equal<*>,
            is Condition.Inside<*>,
            is Condition.GreaterThan<*>,
            is Condition.LessThan<*>,
            is Condition.GreaterThanOrEqual<*>,
            is Condition.LessThanOrEqual<*> -> pushable(onField as Condition<T>)
            else -> appFallback(onField as Condition<T>, "Clustering column doesn't support this condition type")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> analyzeSasiCondition(
        onField: Condition.OnField<T, V>,
        inner: Condition<V>,
        fieldName: String
    ): ConditionAnalysis<T> {
        val sasiInfo = schema.sasiIndexes[fieldName]!!

        return when (inner) {
            is Condition.Equal<*> -> pushable(onField as Condition<T>)

            is Condition.StringContains -> {
                when (sasiInfo.mode) {
                    SasiMode.CONTAINS -> {
                        // SASI CONTAINS mode supports LIKE '%value%'
                        pushable(onField as Condition<T>)
                    }
                    SasiMode.PREFIX -> {
                        // Can only do prefix match, not contains
                        appFallback(
                            onField as Condition<T>,
                            "SASI PREFIX mode doesn't support contains; use CONTAINS mode"
                        )
                    }
                    SasiMode.SPARSE -> {
                        appFallback(onField as Condition<T>, "SASI SPARSE mode doesn't support string operations")
                    }
                }
            }

            is Condition.GreaterThan<*>, is Condition.LessThan<*>,
            is Condition.GreaterThanOrEqual<*>, is Condition.LessThanOrEqual<*> -> {
                // SASI supports range queries
                pushable(onField as Condition<T>)
            }

            is Condition.NotEqual<*> -> {
                // SASI supports != on indexed columns
                pushable(onField as Condition<T>)
            }

            else -> appFallback(onField as Condition<T>, "Condition not supported by SASI")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> analyzeSaiCondition(
        onField: Condition.OnField<T, V>,
        inner: Condition<V>
    ): ConditionAnalysis<T> {
        return when (inner) {
            // SAI supports: equality and range queries
            // SAI does NOT support: IN (Inside), != (NotEqual)
            is Condition.Equal<*>,
            is Condition.GreaterThan<*>,
            is Condition.LessThan<*>,
            is Condition.GreaterThanOrEqual<*>,
            is Condition.LessThanOrEqual<*> -> pushable(onField as Condition<T>)

            is Condition.NotEqual<*> -> appFallback(onField as Condition<T>, "SAI does not support != queries")
            is Condition.Inside<*> -> appFallback(onField as Condition<T>, "SAI does not support IN queries")

            is Condition.ListAnyElements<*> -> {
                // SAI supports CONTAINS for collections
                if (inner.condition is Condition.Equal<*>) {
                    pushable(onField as Condition<T>)
                } else {
                    appFallback(onField as Condition<T>, "SAI CONTAINS only supports equality check on element")
                }
            }

            is Condition.SetAnyElements<*> -> {
                // SAI supports CONTAINS for collections
                if (inner.condition is Condition.Equal<*>) {
                    pushable(onField as Condition<T>)
                } else {
                    appFallback(onField as Condition<T>, "SAI CONTAINS only supports equality check on element")
                }
            }

            else -> appFallback(onField as Condition<T>, "Condition not supported by SAI")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> analyzeComputedColumnRedirect(
        onField: Condition.OnField<T, V>,
        inner: Condition<V>,
        fieldName: String
    ): ConditionAnalysis<T> {
        val computedColumn = schema.isComputedFrom(fieldName)
        if (computedColumn == null) {
            return appFallback(onField as Condition<T>, "No computed column found for '$fieldName'")
        }

        val computedInfo = schema.computedColumns[computedColumn]
        if (computedInfo == null) {
            return appFallback(onField as Condition<T>, "Computed column info not found")
        }

        // Check if the computed column is indexed and could be used
        val canUseComputed = schema.hasSasiIndex(computedColumn) || schema.hasSaiIndex(computedColumn)

        if (!canUseComputed) {
            return appFallback(onField as Condition<T>, "Computed column '$computedColumn' is not indexed")
        }

        // For case-insensitive search using LOWERCASE computed column
        if (computedInfo.transform == ComputedTransform.LOWERCASE && inner is Condition.Equal<*>) {
            // Suggest using the lowercase computed column
            return ConditionAnalysis(
                cqlCondition = null,
                appFilter = onField as Condition<T>,
                requiresFullScan = true,
                warnings = listOf(
                    "Query on '$fieldName' could use computed column '$computedColumn' for case-insensitive search. " +
                    "Rewrite the query to target '$computedColumn' directly."
                )
            )
        }

        return appFallback(onField as Condition<T>, "Cannot automatically redirect to computed column")
    }

    // Helper methods
    private fun pushable(condition: Condition<T>): ConditionAnalysis<T> = ConditionAnalysis(
        cqlCondition = condition,
        appFilter = null,
        requiresFullScan = false,
        warnings = emptyList()
    )

    private fun appFallback(condition: Condition<T>, reason: String): ConditionAnalysis<T> = ConditionAnalysis(
        cqlCondition = null,
        appFilter = condition,
        requiresFullScan = true,
        warnings = listOf(reason)
    )
}
