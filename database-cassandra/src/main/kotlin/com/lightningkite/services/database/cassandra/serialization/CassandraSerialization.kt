package com.lightningkite.services.database.cassandra.serialization

import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.data.UdtValue
import com.datastax.oss.driver.api.core.type.DataType
import com.datastax.oss.driver.api.core.type.DataTypes
import com.datastax.oss.driver.api.core.type.ListType
import com.datastax.oss.driver.api.core.type.MapType
import com.datastax.oss.driver.api.core.type.SetType
import com.datastax.oss.driver.api.core.type.UserDefinedType
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Serializes/deserializes Kotlin objects to/from Cassandra rows using JSON as intermediate.
 */
public class CassandraSerialization(
    public val serializersModule: SerializersModule
) {
    private val json = Json {
        serializersModule = this@CassandraSerialization.serializersModule
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Deserializes a Cassandra row to a Kotlin object.
     */
    @OptIn(ExperimentalSerializationApi::class)
    public fun <T : Any> deserializeRow(row: Row, serializer: KSerializer<T>): T {
        // Build a map of field name to descriptor for type-aware deserialization
        val fieldDescriptors = buildMap<String, SerialDescriptor> {
            for (i in 0 until serializer.descriptor.elementsCount) {
                val fieldName = serializer.descriptor.getElementName(i)
                val fieldDescriptor = serializer.descriptor.getElementDescriptor(i)
                put(fieldName, fieldDescriptor)
            }
        }

        val jsonObj = buildJsonObject {
            for (i in 0 until row.columnDefinitions.size()) {
                // Use asInternal() to get the unquoted identifier
                val columnName = row.columnDefinitions[i].name.asInternal()
                if (!row.isNull(i)) {
                    val fieldDescriptor = fieldDescriptors[columnName]
                    val value = rowValueToJson(row, i, row.columnDefinitions[i].type, fieldDescriptor)
                    if (value != null) {
                        put(columnName, value)
                    }
                }
            }
        }
        return json.decodeFromJsonElement(serializer, jsonObj)
    }

    /**
     * Serializes a Kotlin object to a map suitable for Cassandra insertion.
     * Uses the serializer's descriptor to convert to correct CQL types.
     */
    @OptIn(ExperimentalSerializationApi::class)
    public fun <T : Any> serializeToMap(value: T, serializer: KSerializer<T>): Map<String, Any?> {
        val jsonElement = json.encodeToJsonElement(serializer, value)
        if (jsonElement !is JsonObject) return emptyMap()

        return buildMap {
            for (i in 0 until serializer.descriptor.elementsCount) {
                val fieldName = serializer.descriptor.getElementName(i)
                val fieldDescriptor = serializer.descriptor.getElementDescriptor(i)
                val jsonValue = jsonElement[fieldName]
                if (jsonValue != null && jsonValue !is JsonNull) {
                    put(fieldName, jsonElementToTypedValue(jsonValue, fieldDescriptor))
                }
            }
        }
    }

    /**
     * Gets all column names from a serializer.
     */
    @OptIn(ExperimentalSerializationApi::class)
    public fun <T : Any> getColumnNames(serializer: KSerializer<T>): List<String> {
        return (0 until serializer.descriptor.elementsCount).map {
            serializer.descriptor.getElementName(it)
        }
    }

    /**
     * Gets a specific field value from a Kotlin object.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> getFieldValue(obj: T, fieldName: String, serializer: KSerializer<T>): Any? {
        val props = serializer.serializableProperties ?: return null
        val prop = props.find { it.name == fieldName } ?: return null
        return prop.get(obj)
    }

    private fun rowValueToJson(row: Row, index: Int, dataType: DataType, fieldDescriptor: SerialDescriptor? = null): JsonElement? {
        return when {
            row.isNull(index) -> JsonNull
            dataType == DataTypes.ASCII || dataType == DataTypes.TEXT -> {
                val stringValue = row.getString(index) ?: return JsonNull
                // If the field descriptor indicates a complex type (CLASS, OBJECT), parse the JSON string
                if (fieldDescriptor != null &&
                    (fieldDescriptor.kind == StructureKind.CLASS || fieldDescriptor.kind == StructureKind.OBJECT) &&
                    (stringValue.startsWith("{") || stringValue.startsWith("["))) {
                    try {
                        Json.parseToJsonElement(stringValue)
                    } catch (e: Exception) {
                        JsonPrimitive(stringValue)
                    }
                } else {
                    JsonPrimitive(stringValue)
                }
            }
            dataType == DataTypes.BIGINT -> JsonPrimitive(row.getLong(index))
            dataType == DataTypes.BLOB -> JsonPrimitive(row.getByteBuffer(index)?.let { encodeBase64(it) })
            dataType == DataTypes.BOOLEAN -> JsonPrimitive(row.getBoolean(index))
            dataType == DataTypes.COUNTER -> JsonPrimitive(row.getLong(index))
            dataType == DataTypes.DECIMAL -> JsonPrimitive(row.getBigDecimal(index))
            dataType == DataTypes.DOUBLE -> JsonPrimitive(row.getDouble(index))
            dataType == DataTypes.FLOAT -> JsonPrimitive(row.getFloat(index))
            dataType == DataTypes.INT -> JsonPrimitive(row.getInt(index))
            dataType == DataTypes.TIMESTAMP -> {
                val instant = row.getInstant(index)
                if (instant != null) JsonPrimitive(instant.toString()) else JsonNull
            }
            dataType == DataTypes.UUID || dataType == DataTypes.TIMEUUID -> {
                val uuid = row.getUuid(index)
                if (uuid != null) JsonPrimitive(uuid.toString()) else JsonNull
            }
            dataType == DataTypes.VARINT -> JsonPrimitive(row.getBigInteger(index))
            dataType == DataTypes.SMALLINT -> JsonPrimitive(row.getShort(index))
            dataType == DataTypes.TINYINT -> JsonPrimitive(row.getByte(index))
            dataType == DataTypes.DATE -> {
                val date = row.getLocalDate(index)
                if (date != null) JsonPrimitive(date.toString()) else JsonNull
            }
            dataType == DataTypes.TIME -> {
                val time = row.getLocalTime(index)
                if (time != null) JsonPrimitive(time.toString()) else JsonNull
            }
            dataType == DataTypes.DURATION -> {
                val duration = row.getCqlDuration(index)
                if (duration != null) JsonPrimitive(duration.toString()) else JsonNull
            }
            dataType == DataTypes.INET -> {
                val inet = row.getInetAddress(index)
                if (inet != null) JsonPrimitive(inet.hostAddress) else JsonNull
            }
            dataType is ListType -> {
                // Use getObject to get the list as a native Java type, avoiding codec issues
                @Suppress("UNCHECKED_CAST")
                val list = row.getObject(index) as? List<*> ?: return JsonNull
                buildJsonArray {
                    list.forEach { item -> add(anyToJson(item)) }
                }
            }
            dataType is SetType -> {
                // Use getObject to get the set as a native Java type, avoiding codec issues
                @Suppress("UNCHECKED_CAST")
                val set = row.getObject(index) as? Set<*> ?: return JsonNull
                buildJsonArray {
                    set.forEach { item -> add(anyToJson(item)) }
                }
            }
            dataType is MapType -> {
                // Use getObject to get the map as a native Java type, avoiding codec issues
                @Suppress("UNCHECKED_CAST")
                val map = row.getObject(index) as? Map<*, *> ?: return JsonNull
                buildJsonObject {
                    map.forEach { (k, v) -> put(k.toString(), anyToJson(v)) }
                }
            }
            dataType is UserDefinedType -> {
                val udt = row.getUdtValue(index) ?: return JsonNull
                udtToJson(udt)
            }
            else -> JsonPrimitive(row.getObject(index)?.toString())
        }
    }

    private fun anyToJson(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> {
                // Check if the string is actually a JSON object/array (embedded objects stored as TEXT)
                if ((value.startsWith("{") && value.endsWith("}")) ||
                    (value.startsWith("[") && value.endsWith("]"))) {
                    try {
                        Json.parseToJsonElement(value)
                    } catch (e: Exception) {
                        JsonPrimitive(value)
                    }
                } else {
                    JsonPrimitive(value)
                }
            }
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is UUID -> JsonPrimitive(value.toString())
            is Instant -> JsonPrimitive(value.toString())
            is LocalDate -> JsonPrimitive(value.toString())
            is LocalTime -> JsonPrimitive(value.toString())
            is ByteBuffer -> JsonPrimitive(encodeBase64(value))
            is InetAddress -> JsonPrimitive(value.hostAddress)
            is List<*> -> buildJsonArray { value.forEach { add(anyToJson(it)) } }
            is Set<*> -> buildJsonArray { value.forEach { add(anyToJson(it)) } }
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) -> put(k.toString(), anyToJson(v)) }
            }
            is UdtValue -> udtToJson(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun udtToJson(udt: UdtValue): JsonObject {
        return buildJsonObject {
            val type = udt.type
            for (i in 0 until type.fieldNames.size) {
                val fieldName = type.fieldNames[i].asCql(false)
                if (!udt.isNull(i)) {
                    put(fieldName, anyToJson(udt.getObject(i)))
                }
            }
        }
    }

    /**
     * Converts a JSON element to the correct type based on the SerialDescriptor.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun jsonElementToTypedValue(element: JsonElement, descriptor: SerialDescriptor): Any? {
        if (element is JsonNull) return null

        return when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> (element as? JsonPrimitive)?.booleanOrNull
            PrimitiveKind.BYTE -> (element as? JsonPrimitive)?.intOrNull?.toByte()
            PrimitiveKind.SHORT -> (element as? JsonPrimitive)?.intOrNull?.toShort()
            PrimitiveKind.INT -> (element as? JsonPrimitive)?.intOrNull
            PrimitiveKind.LONG -> (element as? JsonPrimitive)?.longOrNull
            PrimitiveKind.FLOAT -> (element as? JsonPrimitive)?.floatOrNull
            PrimitiveKind.DOUBLE -> (element as? JsonPrimitive)?.doubleOrNull
            PrimitiveKind.CHAR -> (element as? JsonPrimitive)?.contentOrNull?.firstOrNull()?.toString()
            PrimitiveKind.STRING -> {
                val content = (element as? JsonPrimitive)?.contentOrNull
                // Check for special types that serialize as STRING but need Java type conversion
                when (descriptor.serialName) {
                    "kotlin.uuid.Uuid", "com.lightningkite.UUID" -> {
                        content?.let { java.util.UUID.fromString(it) }
                    }
                    "kotlinx.datetime.Instant", "kotlin.time.Instant" -> {
                        content?.let { java.time.Instant.parse(it) }
                    }
                    else -> content
                }
            }

            SerialKind.ENUM -> (element as? JsonPrimitive)?.contentOrNull

            StructureKind.LIST -> {
                val elementDescriptor = descriptor.getElementDescriptor(0)
                val list = (element as? JsonArray)?.map { jsonElementToTypedValue(it, elementDescriptor) }
                // Check if the target type is actually a Set based on serial name
                // Use LinkedHashSet to avoid Kotlin's EmptySet which Cassandra driver doesn't recognize
                if (descriptor.serialName.contains("Set") || descriptor.serialName.contains("kotlin.collections.LinkedHashSet")) {
                    list?.toCollection(LinkedHashSet())
                } else {
                    // Use ArrayList to avoid Kotlin's EmptyList which might cause issues
                    list?.toCollection(ArrayList())
                }
            }

            StructureKind.MAP -> {
                val keyDescriptor = descriptor.getElementDescriptor(0)
                val valueDescriptor = descriptor.getElementDescriptor(1)
                (element as? JsonObject)?.mapValues { (_, v) ->
                    jsonElementToTypedValue(v, valueDescriptor)
                }
            }

            else -> {
                // For complex types (CLASS, OBJECT, etc.), check if we should store as JSON text
                when (descriptor.serialName) {
                    "kotlin.uuid.Uuid", "com.lightningkite.UUID" -> {
                        val uuidStr = (element as? JsonPrimitive)?.contentOrNull
                        uuidStr?.let { java.util.UUID.fromString(it) }
                    }
                    "kotlinx.datetime.Instant", "kotlin.time.Instant" -> {
                        val instant = (element as? JsonPrimitive)?.contentOrNull
                        instant?.let { java.time.Instant.parse(it) }
                    }
                    else -> {
                        // Store complex types as JSON text
                        element.toString()
                    }
                }
            }
        }
    }

    private fun jsonToMap(element: JsonElement): Map<String, Any?> {
        return when (element) {
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToValue(v) }
            else -> emptyMap()
        }
    }

    private fun jsonElementToValue(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.boolean
                    element.longOrNull != null -> {
                        // Use Int if it fits, otherwise Long
                        // This is needed because Cassandra driver is strict about types
                        val longVal = element.long
                        if (longVal >= Int.MIN_VALUE && longVal <= Int.MAX_VALUE) {
                            longVal.toInt()
                        } else {
                            longVal
                        }
                    }
                    element.doubleOrNull != null -> element.double
                    else -> element.content
                }
            }
            is JsonArray -> element.map { jsonElementToValue(it) }
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToValue(v) }
        }
    }

    private fun encodeBase64(buffer: ByteBuffer): String {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
}

/**
 * Maps a KotlinX Serialization descriptor to a Cassandra CQL type.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun SerialDescriptor.toCqlType(): String {
    return when (kind) {
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.BYTE -> "tinyint"
        PrimitiveKind.SHORT -> "smallint"
        PrimitiveKind.INT -> "int"
        PrimitiveKind.LONG -> "bigint"
        PrimitiveKind.FLOAT -> "float"
        PrimitiveKind.DOUBLE -> "double"
        PrimitiveKind.CHAR -> "text"
        PrimitiveKind.STRING -> {
            // Check for special types that serialize as STRING but need specific CQL types
            when (serialName) {
                "kotlin.uuid.Uuid", "com.lightningkite.UUID" -> "uuid"
                "kotlinx.datetime.Instant", "kotlin.time.Instant" -> "timestamp"
                else -> "text"
            }
        }
        SerialKind.ENUM -> "text"
        StructureKind.LIST -> {
            val elementType = getElementDescriptor(0).toCqlType()
            // Check if this is actually a Set based on serial name
            if (serialName.contains("Set") || serialName.contains("kotlin.collections.LinkedHashSet")) {
                "set<$elementType>"
            } else {
                "list<$elementType>"
            }
        }
        StructureKind.MAP -> {
            val keyType = getElementDescriptor(0).toCqlType()
            val valueType = getElementDescriptor(1).toCqlType()
            "map<$keyType, $valueType>"
        }
        else -> {
            // Check for known types by serial name
            when (serialName) {
                "kotlin.uuid.Uuid", "com.lightningkite.UUID" -> "uuid"
                "kotlinx.datetime.Instant", "kotlin.time.Instant" -> "timestamp"
                "kotlinx.datetime.LocalDate" -> "date"
                "kotlinx.datetime.LocalTime" -> "time"
                else -> "text" // Fallback to JSON-encoded text
            }
        }
    }
}
