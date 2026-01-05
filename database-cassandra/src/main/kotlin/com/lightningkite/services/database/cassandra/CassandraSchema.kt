package com.lightningkite.services.database.cassandra

import com.lightningkite.services.data.Index
import com.lightningkite.services.database.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/**
 * Metadata about a Cassandra table schema, extracted from serializer annotations.
 */
public data class CassandraSchema<T : Any>(
    public val tableName: String,
    public val serializer: KSerializer<T>,
    public val partitionKeys: List<PropertyInfo<T, *>>,
    public val clusteringColumns: List<ClusteringColumnInfo<T, *>>,
    public val sasiIndexes: Map<String, SasiIndexInfo>,
    public val saiIndexes: Set<String>,
    public val computedColumns: Map<String, ComputedColumnInfo<T>>,
    public val materializedViews: List<MaterializedViewInfo>
) {
    public companion object {
        /**
         * Extracts schema metadata from a KSerializer using reflection on annotations.
         */
        @OptIn(ExperimentalSerializationApi::class)
        public fun <T : Any> fromSerializer(
            serializer: KSerializer<T>,
            tableName: String
        ): CassandraSchema<T> {
            val properties = serializer.serializableProperties
                ?: throw IllegalArgumentException("Serializer ${serializer.descriptor.serialName} has no serializable properties")

            val partitionKeys = mutableListOf<PropertyInfo<T, *>>()
            val clusteringColumns = mutableListOf<ClusteringColumnInfo<T, *>>()
            val sasiIndexes = mutableMapOf<String, SasiIndexInfo>()
            val saiIndexes = mutableSetOf<String>()
            val computedColumns = mutableMapOf<String, ComputedColumnInfo<T>>()

            for (index in 0 until serializer.descriptor.elementsCount) {
                val name = serializer.descriptor.getElementName(index)
                val annotations = serializer.descriptor.getElementAnnotations(index)
                val prop = properties[index]

                for (annotation in annotations) {
                    when (annotation) {
                        is PartitionKey -> {
                            @Suppress("UNCHECKED_CAST")
                            partitionKeys.add(
                                PropertyInfo(
                                    name = name,
                                    property = prop as SerializableProperty<T, Any?>,
                                    order = annotation.order
                                )
                            )
                        }
                        is ClusteringColumn -> {
                            @Suppress("UNCHECKED_CAST")
                            clusteringColumns.add(
                                ClusteringColumnInfo(
                                    name = name,
                                    property = prop as SerializableProperty<T, Any?>,
                                    order = annotation.order,
                                    descending = annotation.descending
                                )
                            )
                        }
                        is SasiIndex -> {
                            sasiIndexes[name] = SasiIndexInfo(
                                mode = annotation.mode,
                                caseSensitive = annotation.caseSensitive,
                                analyzer = annotation.analyzer
                            )
                        }
                        is SaiIndex -> {
                            saiIndexes.add(name)
                        }
                        is Index -> {
                            // Standard @Index annotation is treated as SAI index
                            // This allows the same model to work across MongoDB, Postgres, and Cassandra
                            saiIndexes.add(name)
                        }
                        is ComputedColumn -> {
                            @Suppress("UNCHECKED_CAST")
                            computedColumns[name] = ComputedColumnInfo(
                                transform = annotation.transform,
                                sourceProperties = annotation.sourceProperties.toList(),
                                property = prop as SerializableProperty<T, Any?>
                            )
                        }
                    }
                }
            }

            // Sort partition keys and clustering columns by order
            partitionKeys.sortBy { it.order }
            clusteringColumns.sortBy { it.order }

            // If no partition key specified, use _id as default (following HasId convention)
            if (partitionKeys.isEmpty()) {
                val idIndex = (0 until serializer.descriptor.elementsCount)
                    .firstOrNull { serializer.descriptor.getElementName(it) == "_id" }
                if (idIndex != null) {
                    @Suppress("UNCHECKED_CAST")
                    partitionKeys.add(
                        PropertyInfo(
                            name = "_id",
                            property = properties[idIndex] as SerializableProperty<T, Any?>,
                            order = 0
                        )
                    )
                }
            }

            // Extract class-level annotations for materialized views
            val materializedViews = serializer.descriptor.annotations
                .filterIsInstance<MaterializedView>()
                .map { mv ->
                    MaterializedViewInfo(
                        name = mv.name,
                        partitionKey = mv.partitionKey.toList(),
                        clusteringColumns = mv.clusteringColumns.toList(),
                        clusteringOrder = mv.clusteringOrder.map { order ->
                            val parts = order.split(" ")
                            val colName = parts[0]
                            val desc = parts.getOrNull(1)?.uppercase() == "DESC"
                            colName to desc
                        }
                    )
                }

            return CassandraSchema(
                tableName = tableName,
                serializer = serializer,
                partitionKeys = partitionKeys,
                clusteringColumns = clusteringColumns,
                sasiIndexes = sasiIndexes,
                saiIndexes = saiIndexes,
                computedColumns = computedColumns,
                materializedViews = materializedViews
            )
        }
    }

    /**
     * Returns the best table/view to query for the given sort order.
     * Returns null if no table supports the requested sort natively.
     */
    public fun findTableForSort(orderBy: List<SortPart<T>>): String? {
        if (orderBy.isEmpty()) return tableName

        // Check if orderBy matches clustering columns of main table
        if (matchesClusteringOrder(orderBy, clusteringColumns)) {
            return tableName
        }

        // Check materialized views
        for (mv in materializedViews) {
            if (orderBy.size <= mv.clusteringColumns.size) {
                val matches = orderBy.zip(mv.clusteringColumns).all { (sort, col) ->
                    sort.field.properties.lastOrNull()?.name == col
                }
                if (matches) return mv.name
            }
        }

        return null
    }

    private fun matchesClusteringOrder(orderBy: List<SortPart<T>>, clusteringCols: List<ClusteringColumnInfo<T, *>>): Boolean {
        if (orderBy.size > clusteringCols.size) return false
        return orderBy.zip(clusteringCols).all { (sort, col) ->
            sort.field.properties.lastOrNull()?.name == col.name
        }
    }

    /**
     * Checks if a column is a partition key.
     */
    public fun isPartitionKey(column: String): Boolean = partitionKeys.any { it.name == column }

    /**
     * Checks if a column is a clustering column.
     */
    public fun isClusteringColumn(column: String): Boolean = clusteringColumns.any { it.name == column }

    /**
     * Checks if a column has a SASI index.
     */
    public fun hasSasiIndex(column: String): Boolean = column in sasiIndexes

    /**
     * Checks if a column has a SAI index.
     */
    public fun hasSaiIndex(column: String): Boolean = column in saiIndexes

    /**
     * Checks if a column is computed from another column.
     * Returns the source column name if it is, null otherwise.
     */
    public fun isComputedFrom(column: String): String? {
        for ((computedName, info) in computedColumns) {
            if (info.sourceProperties.contains(column)) {
                return computedName
            }
        }
        return null
    }

    /**
     * Checks if the schema supports SAI OR queries (Cassandra 5.0+).
     * This is a configuration flag that should be set based on the Cassandra version.
     */
    public var supportsSaiOr: Boolean = false

    /**
     * Tracks which SAI indexes were actually created successfully.
     * This is populated at runtime after index creation attempts.
     */
    public val actualSaiIndexes: MutableSet<String> = mutableSetOf()

    /**
     * Flag indicating whether SAI index verification has been performed.
     * Set to true after createIndexes() completes.
     */
    public var saiIndexesVerified: Boolean = false

    /**
     * Checks if a column has a working SAI index (either declared and created, or verified at runtime).
     */
    public fun hasWorkingSaiIndex(column: String): Boolean {
        // If we've verified indexes, only use the ones that actually got created
        // Otherwise (before table preparation), assume declared indexes will work
        return if (saiIndexesVerified) {
            column in actualSaiIndexes
        } else {
            column in saiIndexes
        }
    }

    /**
     * Returns all indexed columns (partition keys, clustering columns, SASI, working SAI).
     */
    public val indexedColumns: Set<String>
        get() = buildSet {
            addAll(partitionKeys.map { it.name })
            addAll(clusteringColumns.map { it.name })
            addAll(sasiIndexes.keys)
            // Use actual SAI indexes if verified, otherwise fall back to declared
            if (saiIndexesVerified) {
                addAll(actualSaiIndexes)
            } else {
                addAll(saiIndexes)
            }
        }
}

/**
 * Information about a property in the schema.
 */
public data class PropertyInfo<T, V>(
    public val name: String,
    public val property: SerializableProperty<T, V>,
    public val order: Int
) {
    public fun get(obj: T): V = property.get(obj)
}

/**
 * Information about a clustering column.
 */
public data class ClusteringColumnInfo<T, V>(
    public val name: String,
    public val property: SerializableProperty<T, V>,
    public val order: Int,
    public val descending: Boolean
) {
    public fun get(obj: T): V = property.get(obj)
}

/**
 * Information about a SASI index.
 */
public data class SasiIndexInfo(
    public val mode: SasiMode,
    public val caseSensitive: Boolean,
    public val analyzer: SasiAnalyzer
)

/**
 * Information about a computed column.
 */
public data class ComputedColumnInfo<T>(
    public val transform: ComputedTransform,
    public val sourceProperties: List<String>,
    public val property: SerializableProperty<T, Any?>
)

/**
 * Information about a materialized view.
 */
public data class MaterializedViewInfo(
    public val name: String,
    public val partitionKey: List<String>,
    public val clusteringColumns: List<String>,
    public val clusteringOrder: List<Pair<String, Boolean>> // name to descending
)
