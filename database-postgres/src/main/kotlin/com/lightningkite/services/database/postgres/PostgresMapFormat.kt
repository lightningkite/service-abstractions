// by Claude
package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.mapformat.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.statements.UpdateBuilder

/**
 * MapFormat configured for PostgreSQL.
 *
 * Handles serialization/deserialization of Kotlin objects to/from PostgreSQL rows
 * using the MapFormat abstraction with Postgres-specific collection handling (SOA).
 */
@OptIn(ExperimentalSerializationApi::class)
internal class PostgresMapFormat(val serializersModule: SerializersModule) {

    private val config: MapFormatConfig
    private val format: MapFormat

    init {
        // Use lazy config provider to allow self-referential config for nested collections - by Claude
        lateinit var lazyConfig: MapFormatConfig

        val collectionHandler = PostgresCollectionHandler({ lazyConfig }) { descriptor ->
            resolveLeafPaths(descriptor)
        }

        lazyConfig = MapFormatConfig(
            fieldSeparator = "__",
            converters = postgresConverterRegistry,
            collectionHandler = collectionHandler,
            serializersModule = serializersModule
        )

        config = lazyConfig
        format = MapFormat(config)
    }

    /**
     * Resolves all leaf field paths for a descriptor.
     * For primitives, returns [""] (single empty path).
     * For classes, returns all flattened field paths.
     */
    private fun resolveLeafPaths(descriptor: SerialDescriptor): List<String> {
        // Check for converter - converted types are leaf nodes
        if (postgresConverterRegistry.get(descriptor) != null) {
            return listOf("")
        }

        return when (descriptor.kind) {
            StructureKind.CLASS -> {
                // Value classes (inline classes) should be treated as their underlying type - by Claude
                if (descriptor.isInline) {
                    return resolveLeafPaths(descriptor.getElementDescriptor(0))
                }
                // Recursively collect paths from all elements
                val paths = mutableListOf<String>()
                for (i in 0 until descriptor.elementsCount) {
                    val elementName = descriptor.getElementName(i)
                    val elementDesc = descriptor.getElementDescriptor(i)
                    val childPaths = resolveLeafPaths(elementDesc)
                    for (childPath in childPaths) {
                        val fullPath = if (childPath.isEmpty()) elementName else "${elementName}__$childPath"
                        paths.add(fullPath)
                    }
                }
                paths
            }
            else -> listOf("") // Primitive or other leaf type
        }
    }

    /**
     * Encode a value directly to an UpdateBuilder (for INSERT/UPDATE operations).
     */
    fun <T> encode(serializer: KSerializer<T>, value: T, builder: UpdateBuilder<*>, path: List<String> = listOf("")) {
        // First encode to a map
        val map = encode(serializer, value, path = path)

        // Then write to the builder
        val columns = builder.targets.flatMap { it.columns }.map {
            @Suppress("UNCHECKED_CAST")
            it as Column<Any?>
        }.associateBy { it.name }

        for ((key, v) in map) {
            val column = columns[key] ?: continue
            builder[column] = v
        }
    }

    /**
     * Encode a value to a Map<String, Any?>.
     */
    fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
        out: MutableMap<String, Any?> = LinkedHashMap(),
        path: List<String> = listOf(""),
    ): Map<String, Any?> {
        // Handle path prefix
        val prefix = path.filter { it.isNotEmpty() }.joinToString("__")

        // Handle null nullable map/list - write null to all SOA columns - by Claude
        // The actual descriptor for nullable types ends with "?" in serial name
        val isNullable = serializer.descriptor.isNullable
        if (value == null && isNullable) {
            val innerDesc = serializer.descriptor
            when (innerDesc.kind) {
                StructureKind.MAP -> {
                    // For null map, write null to keys and values columns
                    val keyField = if (prefix.isEmpty()) "" else prefix
                    val valueField = if (prefix.isEmpty()) "value" else "${prefix}__value"
                    out[keyField] = null
                    out[valueField] = null
                    return out
                }
                else -> {
                    // Fall through to normal encoding
                }
            }
        }

        val result = format.encode(serializer, value)

        // Add prefix to all keys if needed
        if (prefix.isEmpty()) {
            out.putAll(result.mainRecord)
        } else {
            for ((key, v) in result.mainRecord) {
                val prefixedKey = if (key.isEmpty()) prefix else "${prefix}__$key"
                out[prefixedKey] = v
            }
        }

        return out
    }

    /**
     * Decode a value from a Map<String, Any?>.
     */
    fun <T> decode(serializer: KSerializer<T>, map: Map<String, Any?>, path: List<String> = listOf("")): T {
        val prefix = path.filter { it.isNotEmpty() }.joinToString("__")

        // If there's a prefix, filter and strip it from keys
        val effectiveMap = if (prefix.isEmpty()) {
            map
        } else {
            map.entries
                .filter { (key, _) -> key == prefix || key.startsWith("${prefix}__") }
                .associate { (key, value) ->
                    val stripped = if (key == prefix) "" else key.removePrefix("${prefix}__")
                    stripped to value
                }
        }

        return format.decode(serializer, effectiveMap)
    }

    /**
     * Decode a value from a ResultRow.
     */
    fun <T> decode(serializer: KSerializer<T>, row: ResultRow, path: List<String> = listOf("")): T {
        val columns = row.fieldIndex.keys.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            it as? Column<Any?>
        }.associateBy { it.name }

        val map = columns.mapValues { (name, column) ->
            try {
                row[column]
            } catch (_: Exception) {
                null
            }
        }

        return decode(serializer, map, path)
    }

    /**
     * Get the column types for a descriptor.
     * Used for schema generation in SerialDescriptorTable.
     */
    fun columnTypes(descriptor: SerialDescriptor): List<ColumnTypeInfo> {
        return descriptor.columnType(serializersModule)
    }
}
