package com.lightningkite.services.database.sql

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
 * MapFormat configured for generic SQL with child table collection handling.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class SqlMapFormat(val serializersModule: SerializersModule) {

    internal val config: MapFormatConfig
    internal val format: MapFormat

    init {
        lateinit var lazyConfig: MapFormatConfig

        val collectionHandler = ChildTableCollectionHandler { lazyConfig }

        lazyConfig = MapFormatConfig(
            fieldSeparator = "__",
            converters = sqlConverterRegistry,
            collectionHandler = collectionHandler,
            serializersModule = serializersModule,
        )

        config = lazyConfig
        format = MapFormat(config)
    }

    /**
     * Encode a value to WriteResult (mainRecord + children).
     */
    fun <T> encode(serializer: KSerializer<T>, value: T): WriteResult {
        return format.encode(serializer, value)
    }

    /**
     * Encode a value to a flat map (for main table fields only, ignoring children).
     * Used for condition value formatting.
     */
    fun <T> encodeToMap(
        serializer: KSerializer<T>,
        value: T,
        path: List<String> = listOf(""),
    ): Map<String, Any?> {
        val prefix = path.filter { it.isNotEmpty() }.joinToString("__")
        val result = format.encode(serializer, value)

        return if (prefix.isEmpty()) {
            result.mainRecord
        } else {
            result.mainRecord.entries.associate { (key, v) ->
                val prefixedKey = if (key.isEmpty()) prefix else "${prefix}__$key"
                prefixedKey to v
            }
        }
    }

    /**
     * Encode to an Exposed UpdateBuilder (for INSERT/UPDATE).
     * Only writes main record fields, not children.
     */
    fun <T> encodeToBuilder(
        serializer: KSerializer<T>,
        value: T,
        builder: UpdateBuilder<*>,
    ) {
        val result = format.encode(serializer, value)
        val columns = builder.targets.flatMap { it.columns }.map {
            @Suppress("UNCHECKED_CAST")
            it as Column<Any?>
        }.associateBy { it.name }

        for ((key, v) in result.mainRecord) {
            val column = columns[key] ?: continue
            builder[column] = v
        }
    }

    /**
     * Decode from a main row map + child rows.
     */
    fun <T> decode(
        serializer: KSerializer<T>,
        mainRow: Map<String, Any?>,
        children: Map<String, List<ChildRow>> = emptyMap(),
    ): T {
        val source = SimpleReadSource(mainRow, children, config.fieldSeparator)
        return format.decode(serializer, source)
    }

    /**
     * Decode from an Exposed ResultRow + child rows.
     */
    fun <T> decode(
        serializer: KSerializer<T>,
        row: ResultRow,
        children: Map<String, List<ChildRow>> = emptyMap(),
    ): T {
        val columns = row.fieldIndex.keys.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            it as? Column<Any?>
        }.associateBy { it.name }

        val map = columns.mapValues { (_, column) ->
            try {
                row[column]
            } catch (_: Exception) {
                null
            }
        }

        return decode(serializer, map, children)
    }
}
