// by Claude
package com.lightningkite.services.database.cassandra

import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.data.UdtValue
import com.datastax.oss.driver.api.core.type.*
import com.lightningkite.services.database.mapformat.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * MapFormat configured for Cassandra.
 *
 * Handles serialization/deserialization of Kotlin objects to/from Cassandra rows
 * using the MapFormat abstraction with Cassandra-specific collection handling.
 *
 * Cassandra stores collections natively (list, set, map types), so we use
 * ArrayListCollectionHandler which stores them as List/Set/Map<Any?>.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class CassandraMapFormat(val serializersModule: SerializersModule) {

    private val config: MapFormatConfig
    private val format: MapFormat

    init {
        // Cassandra stores collections natively, so use ArrayListCollectionHandler
        // which produces List<Any?> and Map<Any?, Any?> that we then pass directly
        // to the Cassandra driver
        lateinit var lazyConfig: MapFormatConfig

        val collectionHandler = ArrayListCollectionHandler(
            MapFormatConfig(
                serializersModule = serializersModule,
                converters = cassandraConverterRegistry,
                // by Claude - serialize embedded objects inside collections as JSON strings
                // because Cassandra uses list<text> for embedded objects in collections
                serializeCollectionEmbeddedAsJson = true,
                collectionHandler = object : CollectionHandler {
                    // Nested collections - Cassandra supports frozen collections
                    override fun createListEncoder(
                        fieldPath: String,
                        elementDescriptor: kotlinx.serialization.descriptors.SerialDescriptor,
                        output: WriteTarget
                    ) = ArrayListCollectionHandler(lazyConfig).createListEncoder(fieldPath, elementDescriptor, output)

                    override fun createListDecoder(
                        fieldPath: String,
                        elementDescriptor: kotlinx.serialization.descriptors.SerialDescriptor,
                        input: ReadSource
                    ) = ArrayListCollectionHandler(lazyConfig).createListDecoder(fieldPath, elementDescriptor, input)

                    override fun createMapEncoder(
                        fieldPath: String,
                        keyDescriptor: kotlinx.serialization.descriptors.SerialDescriptor,
                        valueDescriptor: kotlinx.serialization.descriptors.SerialDescriptor,
                        output: WriteTarget
                    ) = ArrayListCollectionHandler(lazyConfig).createMapEncoder(fieldPath, keyDescriptor, valueDescriptor, output)

                    override fun createMapDecoder(
                        fieldPath: String,
                        keyDescriptor: kotlinx.serialization.descriptors.SerialDescriptor,
                        valueDescriptor: kotlinx.serialization.descriptors.SerialDescriptor,
                        input: ReadSource
                    ) = ArrayListCollectionHandler(lazyConfig).createMapDecoder(fieldPath, keyDescriptor, valueDescriptor, input)
                }
            )
        )

        lazyConfig = MapFormatConfig(
            fieldSeparator = "__", // Not really used for Cassandra since we don't flatten embedded objects
            converters = cassandraConverterRegistry,
            collectionHandler = collectionHandler,
            serializersModule = serializersModule,
            // by Claude - serialize embedded objects inside collections as JSON strings
            serializeCollectionEmbeddedAsJson = true,
        )

        config = lazyConfig
        format = MapFormat(config)
    }

    /**
     * Encode a value to a Map<String, Any?> suitable for Cassandra INSERT/UPDATE.
     * The values are already converted to Cassandra-native types.
     *
     * Filters out SOA (Structure of Arrays) columns that MapFormat generates for
     * PostgreSQL-style map storage. Cassandra uses native map types instead.
     * by Claude
     */
    fun <T> encode(serializer: KSerializer<T>, value: T): Map<String, Any?> {
        val result = format.encode(serializer, value)
        // Filter out map SOA columns that end with __value (PostgreSQL pattern)
        // Cassandra uses native map types so these aren't needed
        // Keep __exists markers for nullable embedded classes
        return result.mainRecord.filterKeys { key ->
            !key.endsWith("__value")
        }
    }

    /**
     * Decode a value from a Cassandra Row.
     */
    fun <T> decode(serializer: KSerializer<T>, row: Row): T {
        val source = CassandraRowReadSource(row)
        return format.decode(serializer, source)
    }

    /**
     * Decode a value from a Map<String, Any?>.
     * Useful for testing and internal operations.
     */
    fun <T> decode(serializer: KSerializer<T>, map: Map<String, Any?>): T {
        return format.decode(serializer, map)
    }
}

/**
 * ReadSource implementation that reads from a Cassandra Row.
 *
 * This handles the conversion from Cassandra-native types back to the Map format
 * that MapDecoder expects.
 */
internal class CassandraRowReadSource(
    private val row: Row,
    private val fieldSeparator: String = "__"
) : ReadSource {

    // Cache the column values to avoid repeated reads
    private val columnValues: Map<String, Any?> by lazy {
        buildMap {
            for (i in 0 until row.columnDefinitions.size()) {
                val columnName = row.columnDefinitions[i].name.asInternal()
                if (!row.isNull(i)) {
                    val dataType = row.columnDefinitions[i].type
                    put(columnName, convertFromCassandra(row, i, dataType))
                }
            }
        }
    }

    override fun readField(path: String): Any? = columnValues[path]

    override fun hasField(path: String): Boolean =
        columnValues.containsKey(path) || columnValues.keys.any { it.startsWith("$path$fieldSeparator") }

    override fun hasNestedFields(path: String): Boolean =
        columnValues.entries.any { (k, v) -> k.startsWith("$path$fieldSeparator") && v != null }

    override fun readChildren(childName: String): List<ChildRow> = emptyList()

    /**
     * Convert a Cassandra value to a format MapDecoder understands.
     * Most values are already in the right format, but some need conversion.
     */
    private fun convertFromCassandra(row: Row, index: Int, dataType: DataType): Any? {
        return when {
            row.isNull(index) -> null
            dataType == DataTypes.ASCII || dataType == DataTypes.TEXT -> row.getString(index)
            dataType == DataTypes.BIGINT -> row.getLong(index)
            dataType == DataTypes.BLOB -> row.getByteBuffer(index)
            dataType == DataTypes.BOOLEAN -> row.getBoolean(index)
            dataType == DataTypes.COUNTER -> row.getLong(index)
            dataType == DataTypes.DECIMAL -> row.getBigDecimal(index)
            dataType == DataTypes.DOUBLE -> row.getDouble(index)
            dataType == DataTypes.FLOAT -> row.getFloat(index)
            dataType == DataTypes.INT -> row.getInt(index)
            dataType == DataTypes.TIMESTAMP -> row.getInstant(index)
            dataType == DataTypes.UUID || dataType == DataTypes.TIMEUUID -> row.getUuid(index)
            dataType == DataTypes.VARINT -> row.getBigInteger(index)
            dataType == DataTypes.SMALLINT -> row.getShort(index)
            dataType == DataTypes.TINYINT -> row.getByte(index)
            dataType == DataTypes.DATE -> row.getLocalDate(index)
            dataType == DataTypes.TIME -> row.getLocalTime(index)
            dataType == DataTypes.DURATION -> row.getCqlDuration(index)
            dataType == DataTypes.INET -> row.getInetAddress(index)
            dataType is ListType -> {
                // Get as list and recursively convert elements
                @Suppress("UNCHECKED_CAST")
                val list = row.getObject(index) as? List<*> ?: return null
                list.map { convertValue(it, (dataType as ListType).elementType) }
            }
            dataType is SetType -> {
                // Get as set and recursively convert elements
                @Suppress("UNCHECKED_CAST")
                val set = row.getObject(index) as? Set<*> ?: return null
                // Return as list since MapDecoder expects List for both List and Set
                set.map { convertValue(it, (dataType as SetType).elementType) }
            }
            dataType is MapType -> {
                // Get as map and recursively convert values
                @Suppress("UNCHECKED_CAST")
                val map = row.getObject(index) as? Map<*, *> ?: return null
                map.mapValues { (_, v) ->
                    convertValue(v, (dataType as MapType).valueType)
                }
            }
            dataType is UserDefinedType -> {
                val udt = row.getUdtValue(index) ?: return null
                udtToMap(udt)
            }
            else -> row.getObject(index)
        }
    }

    /**
     * Convert a single value from Cassandra types.
     */
    private fun convertValue(value: Any?, dataType: DataType): Any? {
        if (value == null) return null
        return when (dataType) {
            DataTypes.TIMESTAMP -> value // Already java.time.Instant
            DataTypes.UUID, DataTypes.TIMEUUID -> value // Already java.util.UUID
            DataTypes.DATE -> value // Already java.time.LocalDate
            DataTypes.TIME -> value // Already java.time.LocalTime
            DataTypes.BLOB -> value // Already ByteBuffer
            is ListType -> {
                @Suppress("UNCHECKED_CAST")
                (value as? List<*>)?.map { convertValue(it, dataType.elementType) }
            }
            is SetType -> {
                @Suppress("UNCHECKED_CAST")
                (value as? Set<*>)?.map { convertValue(it, dataType.elementType) }
            }
            is MapType -> {
                @Suppress("UNCHECKED_CAST")
                (value as? Map<*, *>)?.mapValues { (_, v) -> convertValue(v, dataType.valueType) }
            }
            is UserDefinedType -> {
                (value as? UdtValue)?.let { udtToMap(it) }
            }
            else -> value
        }
    }

    /**
     * Convert a UDT to a Map for embedded class deserialization.
     */
    private fun udtToMap(udt: UdtValue): Map<String, Any?> {
        val type = udt.type
        return buildMap {
            for (i in 0 until type.fieldNames.size) {
                val fieldName = type.fieldNames[i].asCql(false)
                if (!udt.isNull(i)) {
                    val fieldType = type.fieldTypes[i]
                    put(fieldName, convertValue(udt.getObject(i), fieldType))
                }
            }
        }
    }
}
