package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.cassandra.serialization.shouldFlatten
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Quotes a CQL identifier to handle reserved words and special characters.
 * Identifiers starting with underscore or containing special chars need quoting.
 * Double quotes within the identifier are escaped by doubling them per CQL spec.
 */
public fun String.quoteCql(): String = "\"${this.replace("\"", "\"\"")}\""

/**
 * Converts Kotlin types to Java types suitable for Cassandra query parameters.
 * Complex types are serialized to JSON strings.
 */
public fun convertToCassandraType(value: Any?): Any? {
    if (value == null) return null
    return when (value) {
        // Primitive types - pass through
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is String -> value
        // Java types that Cassandra supports natively
        is java.util.UUID, is java.time.Instant, is java.time.LocalDate, is java.time.LocalTime -> value
        is java.math.BigDecimal, is java.math.BigInteger -> value
        is java.nio.ByteBuffer, is java.net.InetAddress -> value
        // Kotlin types that need conversion to Java types
        is kotlin.uuid.Uuid -> java.util.UUID.fromString(value.toString())
        is kotlinx.datetime.Instant -> java.time.Instant.ofEpochMilli(value.toEpochMilliseconds())
        is kotlinx.datetime.LocalDate -> java.time.LocalDate.of(value.year, value.monthNumber, value.dayOfMonth)
        is kotlinx.datetime.LocalDateTime -> java.time.LocalDateTime.of(
            value.year, value.monthNumber, value.dayOfMonth,
            value.hour, value.minute, value.second, value.nanosecond
        )
        // Collections - recursively convert
        is List<*> -> value.map { convertToCassandraType(it) }
        is Set<*> -> value.map { convertToCassandraType(it) }.toSet()
        is Map<*, *> -> value.mapValues { convertToCassandraType(it.value) }
        // Complex types (data classes, etc.) - serialize to JSON string
        else -> {
            try {
                val serializer = serializer(value::class.java)
                @Suppress("UNCHECKED_CAST")
                Json.encodeToString(serializer as kotlinx.serialization.KSerializer<Any>, value)
            } catch (e: Exception) {
                // Fallback to toString if serialization fails
                value.toString()
            }
        }
    }
}

/**
 * A generated CQL query with its bound parameters.
 */
public data class CqlQuery(
    val cql: String,
    val parameters: List<Any?>
)

/**
 * Generates CQL queries from conditions and other query components.
 */
@OptIn(ExperimentalSerializationApi::class)
public class CqlGenerator<T : Any>(
    private val schema: CassandraSchema<T>,
    private val tableName: String = schema.tableName,
    // by Claude - SerializersModule for encoding embedded types
    private val serializersModule: kotlinx.serialization.modules.SerializersModule = kotlinx.serialization.modules.EmptySerializersModule()
) {
    // by Claude - lazy MapFormat for encoding embedded types to flattened fields
    private val mapFormat by lazy { CassandraMapFormat(serializersModule) }

    /**
     * by Claude - Find a field descriptor by path (e.g., "_id" or "_id__first").
     * Returns null if the path doesn't exist.
     */
    private fun findFieldDescriptor(path: String): SerialDescriptor? {
        val parts = path.split("__")
        var current = schema.serializer.descriptor
        for (part in parts) {
            val index = (0 until current.elementsCount).firstOrNull { current.getElementName(it) == part }
                ?: return null
            current = current.getElementDescriptor(index)
        }
        return current
    }

    /**
     * by Claude - Expand an embedded type value to individual field conditions.
     * Returns a list of (columnName, value) pairs for each flattened field.
     */
    private fun expandEmbeddedValue(path: String, value: Any): List<Pair<String, Any?>> {
        // Use reflection to get the serializer for the value's type
        val valueSerializer = serializer(value::class.java)
        @Suppress("UNCHECKED_CAST")
        val encoded = mapFormat.encode(valueSerializer as kotlinx.serialization.KSerializer<Any>, value)

        // Prefix each key with the path
        return encoded.map { (key, v) ->
            val fullKey = if (key.isEmpty()) path else "$path${"__"}$key"
            fullKey to v
        }
    }

    /**
     * Generates a SELECT statement from a condition.
     */
    public fun generateSelect(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        limit: Int?
    ): CqlQuery {
        val builder = StringBuilder("SELECT * FROM $tableName")
        val params = mutableListOf<Any?>()

        // WHERE clause
        val whereClause = buildWhereClause(condition, params)
        if (whereClause.isNotEmpty()) {
            builder.append(" WHERE ").append(whereClause)
        }

        // ORDER BY (only if matches clustering order)
        if (orderBy.isNotEmpty() && canPushSort(orderBy)) {
            builder.append(" ORDER BY ")
            builder.append(orderBy.joinToString(", ") { part ->
                val dir = if (part.ascending) "ASC" else "DESC"
                "${part.field.properties.last().name.quoteCql()} $dir"
            })
        }

        // LIMIT
        limit?.let { builder.append(" LIMIT $it") }

        return CqlQuery(builder.toString(), params)
    }

    /**
     * Generates a SELECT COUNT(*) statement.
     */
    public fun generateCount(condition: Condition<T>): CqlQuery {
        val builder = StringBuilder("SELECT COUNT(*) FROM $tableName")
        val params = mutableListOf<Any?>()

        val whereClause = buildWhereClause(condition, params)
        if (whereClause.isNotEmpty()) {
            builder.append(" WHERE ").append(whereClause)
        }

        return CqlQuery(builder.toString(), params)
    }

    /**
     * Generates an INSERT statement.
     *
     * @param columns List of column names to insert
     * @param ifNotExists If true, adds IF NOT EXISTS clause for atomic duplicate check
     */
    public fun generateInsert(columns: List<String>, ifNotExists: Boolean = false): String {
        val columnList = columns.joinToString(", ") { it.quoteCql() }
        val placeholders = columns.joinToString(", ") { "?" }
        val suffix = if (ifNotExists) " IF NOT EXISTS" else ""
        return "INSERT INTO $tableName ($columnList) VALUES ($placeholders)$suffix"
    }

    /**
     * Generates a DELETE statement for a single row.
     */
    public fun generateDelete(pkConditions: List<String>, ckConditions: List<String>): String {
        val allConditions = (pkConditions + ckConditions).joinToString(" AND ") { "${it.quoteCql()} = ?" }
        return "DELETE FROM $tableName WHERE $allConditions"
    }

    /**
     * Generates an UPDATE statement with IF EXISTS for optimistic locking.
     */
    public fun generateUpdateWithLwt(
        setColumns: List<String>,
        pkColumns: List<String>,
        ckColumns: List<String>
    ): String {
        val setClauses = setColumns.joinToString(", ") { "${it.quoteCql()} = ?" }
        val pkConditions = pkColumns.joinToString(" AND ") { "${it.quoteCql()} = ?" }
        val ckConditions = if (ckColumns.isNotEmpty()) {
            " AND " + ckColumns.joinToString(" AND ") { "${it.quoteCql()} = ?" }
        } else ""

        return "UPDATE $tableName SET $setClauses WHERE $pkConditions$ckConditions IF EXISTS"
    }

    /**
     * Builds a WHERE clause from a condition.
     */
    public fun buildWhereClause(
        condition: Condition<T>,
        params: MutableList<Any?>
    ): String {
        return when (condition) {
            is Condition.Always -> ""
            is Condition.Never -> "FALSE"

            is Condition.And -> {
                condition.conditions
                    .map { buildWhereClause(it, params) }
                    .filter { it.isNotEmpty() }
                    .joinToString(" AND ")
            }

            is Condition.Or -> {
                val clauses = condition.conditions
                    .map { buildWhereClause(it, params) }

                // If any clause is empty (Condition.Always), the entire OR matches everything
                if (clauses.any { it.isEmpty() }) {
                    ""
                } else {
                    val nonEmpty = clauses.filter { it.isNotEmpty() }
                    if (nonEmpty.isEmpty()) ""
                    else "(${nonEmpty.joinToString(" OR ")})"
                }
            }

            // by Claude - handle nested OnField for flattened embedded objects
            is Condition.OnField<*, *> -> buildFieldCondition(
                condition.key.name,
                condition.condition,
                params,
                pathPrefix = ""
            )

            else -> throw IllegalArgumentException("Condition type not supported in CQL: ${condition::class.simpleName}")
        }
    }

    // by Claude - added pathPrefix parameter for flattened embedded objects
    private fun buildFieldCondition(
        column: String,
        condition: Condition<*>,
        params: MutableList<Any?>,
        pathPrefix: String = ""
    ): String {
        // Build full column path with __ separator for flattened embedded objects
        val fullPath = if (pathPrefix.isEmpty()) column else "${pathPrefix}__${column}"
        val quotedColumn = fullPath.quoteCql()
        return when (condition) {
            is Condition.Equal<*> -> {
                // by Claude - check if the field is an embedded type that needs expansion
                val fieldDescriptor = findFieldDescriptor(fullPath)
                if (fieldDescriptor != null && fieldDescriptor.shouldFlatten() && condition.value != null) {
                    // Expand embedded type to individual field conditions
                    val expandedFields = expandEmbeddedValue(fullPath, condition.value!!)
                    if (expandedFields.isEmpty()) {
                        params.add(convertToCassandraType(condition.value))
                        "$quotedColumn = ?"
                    } else {
                        expandedFields.joinToString(" AND ") { (colName, value) ->
                            params.add(convertToCassandraType(value))
                            "${colName.quoteCql()} = ?"
                        }
                    }
                } else {
                    params.add(convertToCassandraType(condition.value))
                    "$quotedColumn = ?"
                }
            }

            is Condition.NotEqual<*> -> {
                params.add(convertToCassandraType(condition.value))
                "$quotedColumn != ?"
            }

            is Condition.Inside<*> -> {
                params.add(convertToCassandraType(condition.values.toList()))
                "$quotedColumn IN ?"
            }

            is Condition.NotInside<*> -> {
                // CQL doesn't directly support NOT IN, but we can use != with AND
                // However, this is typically not efficient in Cassandra
                // For now, we'll throw an error - the analyzer should have caught this
                throw IllegalArgumentException("NOT IN is not supported in CQL")
            }

            is Condition.GreaterThan<*> -> {
                params.add(convertToCassandraType(condition.value))
                "$quotedColumn > ?"
            }

            is Condition.LessThan<*> -> {
                params.add(convertToCassandraType(condition.value))
                "$quotedColumn < ?"
            }

            is Condition.GreaterThanOrEqual<*> -> {
                params.add(convertToCassandraType(condition.value))
                "$quotedColumn >= ?"
            }

            is Condition.LessThanOrEqual<*> -> {
                params.add(convertToCassandraType(condition.value))
                "$quotedColumn <= ?"
            }

            is Condition.StringContains -> {
                // SASI LIKE query
                val pattern = "%${escapeLike(condition.value)}%"
                params.add(pattern)
                "$quotedColumn LIKE ?"
            }

            is Condition.ListAnyElements<*>, is Condition.SetAnyElements<*> -> {
                // SAI CONTAINS for collections
                val value = when (condition) {
                    is Condition.ListAnyElements<*> -> {
                        // Extract the value from Equal condition inside
                        (condition.condition as? Condition.Equal<*>)?.value
                            ?: throw IllegalArgumentException("ListAnyElements requires Equal condition for CQL CONTAINS")
                    }
                    is Condition.SetAnyElements<*> -> {
                        (condition.condition as? Condition.Equal<*>)?.value
                            ?: throw IllegalArgumentException("SetAnyElements requires Equal condition for CQL CONTAINS")
                    }
                    else -> throw IllegalStateException()
                }
                params.add(convertToCassandraType(value))
                "$quotedColumn CONTAINS ?"
            }

            is Condition.IfNotNull<*> -> {
                // Unwrap IfNotNull and process the inner condition, preserving path
                // by Claude - fixed to pass pathPrefix through
                buildFieldCondition(column, condition.condition, params, pathPrefix)
            }

            // by Claude - handle nested OnField for path traversal into embedded objects
            is Condition.OnField<*, *> -> {
                // Nested field access - accumulate the path
                buildFieldCondition(condition.key.name, condition.condition, params, fullPath)
            }

            else -> throw IllegalArgumentException("Condition not translatable to CQL: ${condition::class.simpleName}")
        }
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    /**
     * Checks if the given sort order can be pushed to Cassandra.
     */
    public fun canPushSort(orderBy: List<SortPart<T>>): Boolean {
        // Can only sort by clustering columns in their defined order
        val clusteringNames = schema.clusteringColumns.map { it.name }
        val requestedNames = orderBy.map { it.field.properties.lastOrNull()?.name }

        // Must be a prefix of clustering columns
        if (requestedNames.size > clusteringNames.size) return false
        return requestedNames.zip(clusteringNames).all { (req, cluster) -> req == cluster }
    }
}
