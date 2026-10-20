package com.lightningkite.services.database.sqlite

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.mapValueElement
import com.lightningkite.services.database.mapformat.MapEncoder
import com.lightningkite.services.database.mapformat.MapFormatConfig
import com.lightningkite.services.database.mapformat.SimpleWriteTarget
import com.lightningkite.services.database.mapformat.WriteResult
import com.lightningkite.services.database.nullElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor

internal class Sql(val text: String, val args: List<Any?> = listOf())

/** Concatenates text and [Sql] parts in order, keeping each part's arguments in place. */
internal fun sql(vararg parts: Any): Sql =
    Sql(parts.joinToString("") { if (it is Sql) it.text else it.toString() }, parts.flatMap { (it as? Sql)?.args ?: listOf() })

internal fun arg(value: Any?): Sql = Sql("?", listOf(value))

internal fun List<Sql>.join(separator: String): Sql = Sql(joinToString(separator) { it.text }, flatMap { it.args })

/** Joins boolean expressions, parenthesized; [ifEmpty] is the value of an empty AND or OR. */
internal fun List<Sql>.joinSql(separator: String, ifEmpty: String): Sql =
    if (isEmpty()) Sql(ifEmpty) else join(separator).let { Sql("(${it.text})", it.args) }

/**
 * Where a condition's fields live: the main table, or a child table row holding one collection element.
 * [columns] and [childTables] are keyed by path relative to the scope.
 */
internal class Scope(
    val qualifier: String,
    val columns: Map<String, Column>,
    val childTables: Map<String, ChildTable> = mapOf(),
) {
    fun ref(column: Column): String = "$qualifier.${q(column.name)}"
    fun columnsAt(path: String): Map<String, Column> =
        columns.filterKeys { path.isEmpty() || it == path || it.startsWith("${path}__") }
    fun childTablesAt(path: String): List<ChildTable> =
        childTables.filterKeys { path.isEmpty() || it == path || it.startsWith("${path}__") }.values.toList()

    /** True when the value at [path] is not null; the field must be nullable. */
    fun exists(path: String): Sql? =
        columns["${path}__exists"]?.let { Sql("${ref(it)} = 1") }
            ?: columns[path]?.let { Sql("${ref(it)} IS NOT NULL") }
}

internal fun ChildTable.elementScope(qualifier: String): Scope = Scope(qualifier, elementColumns)

/** Matches child rows to their owner in the main table, by every id column. */
internal fun SqliteSchema.ownerMatch(child: String): String =
    idColumns.joinToString(" AND ") { "$child.${q(ownerColumn(it))} = ${q(tableName)}.${q(it.name)}" }

/** Encodes [value] as it would be stored at [path] inside its containing class. */
internal fun <T> MapFormatConfig.encodeAt(path: String, serializer: KSerializer<T>, value: T): WriteResult {
    val target = SimpleWriteTarget()
    val holder = holderDescriptor(path, serializer.descriptor)
    val encoder = MapEncoder(this, target)
    @Suppress("UNCHECKED_CAST")
    if (serializer.descriptor.isNullable) encoder.encodeNullableSerializableElement(holder, 0, serializer.nullElement() as KSerializer<Any>, value)
    else encoder.encodeSerializableElement(holder, 0, serializer, value)
    return target.result()
}

internal fun holderDescriptor(path: String, descriptor: SerialDescriptor): SerialDescriptor =
    buildClassSerialDescriptor("SqliteValueAt") { element(path, descriptor) }

/**
 * Translates a [Condition] to a SQL boolean expression.  Parts SQL can't express exactly become a
 * condition matching everything that might match, and [exact] turns false: the caller must then
 * filter the decoded rows with the condition itself.
 */
internal class SqliteConditions(private val schema: SqliteSchema, private val config: MapFormatConfig) {
    var exact: Boolean = true
        private set

    fun <T> main(condition: Condition<T>, serializer: KSerializer<T>): Sql =
        translate(condition, Scope(q(schema.tableName), schema.columns, schema.childTables), "", serializer, negated = false)

    fun <T> element(condition: Condition<T>, child: ChildTable, qualifier: String, serializer: KSerializer<T>): Sql =
        translate(condition, child.elementScope(qualifier), "", serializer, negated = false)

    // Under an odd number of NOTs, "everything that might match" is the false branch.
    private fun inexact(negated: Boolean): Sql {
        exact = false
        return Sql(if (negated) "0" else "1")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> translate(condition: Condition<T>, scope: Scope, path: String, serializer: KSerializer<T>, negated: Boolean): Sql {
        val single = scope.columns[path]?.takeIf { !it.json }
        fun compare(operator: String, value: T): Sql {
            single ?: return inexact(negated)
            return Sql("${scope.ref(single)} $operator ?", listOf(config.encodeAt(path, serializer, value).mainRecord[path]))
        }
        return when (condition) {
            is Condition.Always -> Sql("1")
            is Condition.Never -> Sql("0")
            is Condition.And -> condition.conditions.map { translate(it, scope, path, serializer, negated) }.joinSql(" AND ", "1")
            is Condition.Or -> condition.conditions.map { translate(it, scope, path, serializer, negated) }.joinSql(" OR ", "0")
            is Condition.Not -> translate(condition.condition, scope, path, serializer, !negated).let { Sql("(NOT ${it.text})", it.args) }
            is Condition.Equal -> equal(scope, path, serializer, condition.value) ?: inexact(negated)
            is Condition.NotEqual -> equal(scope, path, serializer, condition.value)?.let { Sql("(NOT ${it.text})", it.args) }
                ?: inexact(negated)
            is Condition.Inside -> inside(scope, path, serializer, condition.values) ?: inexact(negated)
            is Condition.NotInside -> inside(scope, path, serializer, condition.values)?.let { Sql("(NOT ${it.text})", it.args) }
                ?: inexact(negated)
            is Condition.GreaterThan -> compare(">", condition.value)
            is Condition.LessThan -> compare("<", condition.value)
            is Condition.GreaterThanOrEqual -> compare(">=", condition.value)
            is Condition.LessThanOrEqual -> compare("<=", condition.value)
            is Condition.StringContains -> contains(scope, single, condition.value, condition.ignoreCase) ?: inexact(negated)
            is Condition.RawStringContains -> contains(scope, single, condition.value, condition.ignoreCase) ?: inexact(negated)
            is Condition.IntBitsClear -> single?.let { Sql("(${scope.ref(it)} & ?) = 0", listOf(condition.mask)) } ?: inexact(negated)
            is Condition.IntBitsSet -> single?.let { Sql("(${scope.ref(it)} & ?) = ?", listOf(condition.mask, condition.mask)) } ?: inexact(negated)
            is Condition.IntBitsAnyClear -> single?.let { Sql("(${scope.ref(it)} & ?) <> ?", listOf(condition.mask, condition.mask)) } ?: inexact(negated)
            is Condition.IntBitsAnySet -> single?.let { Sql("(${scope.ref(it)} & ?) <> 0", listOf(condition.mask)) } ?: inexact(negated)
            is Condition.IfNotNull<*> -> {
                val exists = scope.exists(path) ?: return inexact(negated)
                val inner = translate(condition.condition as Condition<Any?>, scope, path, serializer.nullElement() as KSerializer<Any?>, negated)
                listOf(exists, inner).joinSql(" AND ", "1")
            }
            is Condition.OnField<*, *> -> {
                val key = condition.key as SerializableProperty<T, Any?>
                val fieldPath = if (key.inline) path else if (path.isEmpty()) key.name else "${path}__${key.name}"
                val child = scope.childTables[fieldPath]
                if (child != null) collection(condition.condition as Condition<Any?>, child, key.serializer, negated)
                else translate(condition.condition as Condition<Any?>, scope, fieldPath, key.serializer, negated)
            }
            // Regex, full text, distance, and sealed-type checks have no SQL form here, nor do
            // collection checks on collections stored as JSON inside a collection element.
            else -> inexact(negated)
        }
    }

    /** Null-safe equality of every column [value] occupies; null if SQL can't compare it exactly. */
    private fun <T> equal(scope: Scope, path: String, serializer: KSerializer<T>, value: T): Sql? {
        if (value == null) return scope.exists(path)?.let { Sql("(NOT ${it.text})", it.args) }
        val columns = scope.columnsAt(path)
        if (columns.values.any { it.json } || scope.childTablesAt(path).isNotEmpty()) return null
        val encoded = config.encodeAt(path, serializer, value).mainRecord
        if (encoded.keys.any { it !in scope.columns }) return null
        return encoded.map { (key, v) -> Sql("${scope.ref(scope.columns.getValue(key))} IS ?", listOf(v)) }.joinSql(" AND ", "1")
    }

    private fun <T> inside(scope: Scope, path: String, serializer: KSerializer<T>, values: Collection<T>): Sql? {
        val single = scope.columns[path]?.takeIf { !it.json && scope.columnsAt(path).size == 1 }
        if (single == null) {
            return values.map { equal(scope, path, serializer, it) ?: return null }.joinSql(" OR ", "0")
        }
        val stored = values.filterNotNull().map { config.encodeAt(path, serializer, it).mainRecord[path] }
        // IN is null rather than false for a null column, which NOT would keep null; COALESCE makes it false.
        val parts = listOfNotNull(
            if (stored.isEmpty()) null else Sql("COALESCE(${scope.ref(single)} IN (${stored.joinToString { "?" }}), 0)", stored),
            if (values.any { it == null }) Sql("${scope.ref(single)} IS NULL") else null,
        )
        return parts.joinSql(" OR ", "0")
    }

    private fun contains(scope: Scope, single: Column?, value: String, ignoreCase: Boolean): Sql? {
        single ?: return null
        if (!ignoreCase) return Sql("instr(${scope.ref(single)}, ?) > 0", listOf(value))
        // SQLite's lower() only folds ASCII, so other letters are left to the in-memory check.
        if (value.any { it.code > 127 }) return null
        return Sql("instr(lower(${scope.ref(single)}), ?) > 0", listOf(value.lowercase()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun collection(condition: Condition<Any?>, child: ChildTable, serializer: KSerializer<*>, negated: Boolean): Sql {
        val marker = schema.columns[child.path]
        val markerRef = marker?.let { "${q(schema.tableName)}.${q(it.name)}" }
        val nonNull = (if (serializer.descriptor.isNullable) serializer.nullElement() else serializer) as KSerializer<Any?>
        val elementSerializer = (if (child.isMap) nonNull.mapValueElement() else nonNull.listElement()) as KSerializer<Any?>
        val rows = "FROM ${q(child.name)} AS c WHERE ${schema.ownerMatch("c")}"
        fun element(condition: Condition<*>, negated: Boolean): Sql =
            translate(condition as Condition<Any?>, child.elementScope("c"), "", elementSerializer, negated)
        return when (condition) {
            is Condition.Always -> Sql("1")
            is Condition.Never -> Sql("0")
            is Condition.And -> condition.conditions.map { collection(it, child, serializer, negated) }.joinSql(" AND ", "1")
            is Condition.Or -> condition.conditions.map { collection(it, child, serializer, negated) }.joinSql(" OR ", "0")
            is Condition.Not -> collection(condition.condition, child, serializer, !negated).let { Sql("(NOT ${it.text})", it.args) }
            is Condition.Equal -> if (condition.value == null) Sql(if (markerRef == null) "0" else "$markerRef IS NULL") else inexact(negated)
            is Condition.NotEqual -> if (condition.value == null) Sql(if (markerRef == null) "1" else "$markerRef IS NOT NULL") else inexact(negated)
            is Condition.IfNotNull<*> -> listOf(
                Sql(if (markerRef == null) "1" else "$markerRef IS NOT NULL"),
                collection(condition.condition as Condition<Any?>, child, nonNull, negated),
            ).joinSql(" AND ", "1")
            is Condition.ListAnyElements<*> -> element(condition.condition, negated).let { Sql("EXISTS (SELECT 1 $rows AND ${it.text})", it.args) }
            is Condition.SetAnyElements<*> -> element(condition.condition, negated).let { Sql("EXISTS (SELECT 1 $rows AND ${it.text})", it.args) }
            is Condition.ListAllElements<*> -> element(condition.condition, negated).let { Sql("NOT EXISTS (SELECT 1 $rows AND NOT ${it.text})", it.args) }
            is Condition.SetAllElements<*> -> element(condition.condition, negated).let { Sql("NOT EXISTS (SELECT 1 $rows AND NOT ${it.text})", it.args) }
            is Condition.ListSizesEquals<*> -> Sql("(SELECT COUNT(*) $rows) = ?", listOf(condition.count))
            is Condition.SetSizesEquals<*> -> Sql("(SELECT COUNT(*) $rows) = ?", listOf(condition.count))
            is Condition.Exists<*> -> keyColumn(child)?.let { Sql("EXISTS (SELECT 1 $rows AND c.${q(it.name)} = ?)", listOf(condition.key)) }
                ?: inexact(negated)
            is Condition.OnKey<*> -> keyColumn(child)?.let { key ->
                element(condition.condition, negated).let { Sql("EXISTS (SELECT 1 $rows AND c.${q(key.name)} = ? AND ${it.text})", listOf(condition.key) + it.args) }
            } ?: inexact(negated)
            else -> inexact(negated)
        }
    }

    private fun keyColumn(child: ChildTable): Column? =
        child.keyColumns[""]?.takeIf { child.isMap && it.type == ColumnType.Text && !it.json }
}
