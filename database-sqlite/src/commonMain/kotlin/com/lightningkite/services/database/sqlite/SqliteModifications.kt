package com.lightningkite.services.database.sqlite

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.mapformat.ChildRow
import com.lightningkite.services.database.mapformat.MapFormatConfig
import com.lightningkite.services.database.optics.Field
import com.lightningkite.services.database.nullElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialKind

internal sealed interface Step {
    /** Statements for the rows `targets` (an expression over the main table) selects. */
    class Statements(val build: (targets: Sql) -> List<Sql>) : Step

    /** A modification SQL can't express: applied to the decoded rows, which are then rewritten. */
    class Rewrite(val modification: Modification<*>) : Step
}

/**
 * Translates a [Modification] into steps run in order against a fixed set of rows.  Running them
 * in order is what makes a [Modification.Chain] correct: each step, and each [Modification.IfNotNull]
 * guard, sees the row as the earlier steps left it.
 */
@Suppress("UNCHECKED_CAST")
internal class SqliteModifications(private val schema: SqliteSchema, private val config: MapFormatConfig) {
    private val table = q(schema.tableName)
    private val main = Scope(table, schema.columns, schema.childTables)
    private val ids = schema.idColumns.joinToString { q(it.name) }
    private val owners = schema.idColumns.joinToString { q(schema.ownerColumn(it)) }

    fun <T> steps(modification: Modification<T>, serializer: KSerializer<T>): List<Step> =
        // The steps find their rows by id, so changing the id has to happen in one rewrite.
        if (modification.changesId()) listOf(Step.Rewrite(modification))
        else steps(modification as Modification<Any?>, "", serializer as KSerializer<Any?>, listOf()) { it }

    private fun Modification<*>.changesId(): Boolean = when (this) {
        is Modification.Chain -> modifications.any { it.changesId() }
        is Modification.Assign -> true
        is Modification.OnField<*, *> -> key.name == "_id" && !modification.isNothing
        else -> false
    }

    private fun where(targets: Sql, guards: List<Sql>) = (listOf(targets) + guards).joinSql(" AND ", "1")

    private fun ownersIn(where: Sql) = sql("($owners) IN (SELECT $ids FROM $table WHERE ", where, ")")

    private fun steps(
        modification: Modification<Any?>,
        path: String,
        serializer: KSerializer<Any?>,
        guards: List<Sql>,
        wrap: (Modification<Any?>) -> Modification<Any?>,
    ): List<Step> {
        val rewrite = listOf(Step.Rewrite(wrap(modification)))
        val single = main.columns[path]?.takeIf { !it.json }
        fun update(expression: (column: String) -> Sql): List<Step> {
            // MIN and MAX would compare an enum by its stored name rather than its declaration order.
            if (single == null || serializer.descriptor.kind == SerialKind.ENUM) return rewrite
            val column = q(single.name)
            return listOf(Step.Statements { targets ->
                listOf(sql("UPDATE $table SET $column = ", expression(column), " WHERE ", where(targets, guards)))
            })
        }
        fun stored(value: Any?) = config.encodeAt(path, serializer, value).mainRecord[path]
        return when (modification) {
            is Modification.Nothing -> listOf()
            is Modification.Chain -> modification.modifications.flatMap { steps(it, path, serializer, guards, wrap) }
            is Modification.IfNotNull<*> -> {
                val exists = main.exists(path) ?: return rewrite
                steps(modification.modification as Modification<Any?>, path, serializer.nullElement() as KSerializer<Any?>, guards + exists) {
                    wrap(Modification.IfNotNull(it) as Modification<Any?>)
                }
            }
            is Modification.OnField<*, *> -> {
                val key = modification.key as Field<Any?, Any?>
                val fieldPath = fieldPath(path, key)
                val inner = modification.modification as Modification<Any?>
                val innerWrap = { it: Modification<Any?> -> wrap(Modification.OnField(key, it)) }
                val child = schema.childTables[fieldPath]
                if (child != null) collectionSteps(inner, child, key.serializer, guards, innerWrap)
                else steps(inner, fieldPath, key.serializer, guards, innerWrap)
            }
            is Modification.Assign -> assign(path, serializer, modification.value, guards) ?: rewrite
            is Modification.Increment -> update { sql("$it + ", arg(modification.by)) }
            is Modification.Multiply -> update { sql("$it * ", arg(modification.by)) }
            is Modification.CoerceAtMost -> update { Sql("MIN($it, ?)", listOf(stored(modification.value))) }
            is Modification.CoerceAtLeast -> update { Sql("MAX($it, ?)", listOf(stored(modification.value))) }
            // Sealed-type projections, and collections stored as JSON, are applied in Kotlin.
            else -> rewrite
        }
    }

    /** Replaces everything stored at [path]: its columns and its collections' child rows. */
    private fun assign(path: String, serializer: KSerializer<Any?>, value: Any?, guards: List<Sql>): List<Step>? {
        val columns = main.columnsAt(path).values
        val children = main.childTablesAt(path)
        if (columns.isEmpty() && children.isEmpty()) return null
        val encoded = config.encodeAt(path, serializer, value)
        return listOf(Step.Statements { targets ->
            val where = where(targets, guards)
            children.flatMap { child ->
                listOf(sql("DELETE FROM ${q(child.name)} WHERE ", ownersIn(where))) +
                    encoded.children[child.path].orEmpty().map { row ->
                        insert(child, row, where, index = if (child.ordered) arg(row.index) else null)
                    }
            } + listOfNotNull(columns.takeIf { it.isNotEmpty() }?.let {
                val set = Sql(it.joinToString { c -> "${q(c.name)} = ?" }, it.map { c -> encoded.mainRecord[c.name] })
                sql("UPDATE $table SET ", set, " WHERE ", where)
            })
        })
    }

    /** Inserts [row] as an element of every selected owner; [index] is required exactly when the child table is ordered. */
    private fun insert(child: ChildTable, row: ChildRow, where: Sql, index: Sql?, unless: (owner: String) -> Sql? = { null }): Sql {
        check((index != null) == child.ordered) { "${child.name} ${if (child.ordered) "needs" else "has no"} idx" }
        val names = listOf(owners) + listOfNotNull(index?.let { "\"idx\"" }) + child.storedColumns.map { q(it.name) }
        val selected = listOfNotNull(Sql(ids), index) + child.storedValues(row).map { arg(it) }
        val condition = listOfNotNull(where, unless("x")?.let { sql("NOT EXISTS (SELECT 1 FROM ${q(child.name)} AS x WHERE ${schema.ownerMatch("x")} AND ", it, ")") })
        return sql(
            "INSERT INTO ${q(child.name)} (${names.joinToString()}) SELECT ", selected.join(", "),
            " FROM $table WHERE ", condition.joinSql(" AND ", "1"),
        )
    }

    /** Null-safe equality of a child row's element (or key) columns with [values]. */
    private fun sameAs(qualifier: String, columns: Collection<Column>, values: (Column) -> Any?): Sql =
        columns.map { Sql("$qualifier.${q(it.name)} IS ?", listOf(values(it))) }.joinSql(" AND ", "1")

    private fun collectionSteps(
        modification: Modification<Any?>,
        child: ChildTable,
        serializer: KSerializer<*>,
        guards: List<Sql>,
        wrap: (Modification<Any?>) -> Modification<Any?>,
    ): List<Step> {
        val rewrite = listOf(Step.Rewrite(wrap(modification)))
        val nonNull = (if (serializer.descriptor.isNullable) serializer.nullElement() else serializer) as KSerializer<Any?>
        val elementSerializer = child.elementSerializer(serializer)
        val childTable = q(child.name)
        // JSON text can spell equal values differently, so comparing whole elements there is left to Kotlin.
        val jsonElements = child.elementColumns.values.any { it.json }
        fun rows(collection: Any?): List<ChildRow> = config.encodeAt(child.path, nonNull, collection).children[child.path].orEmpty()
        fun statements(build: (where: Sql) -> List<Sql>) = listOf(Step.Statements { targets -> build(where(targets, guards)) })
        fun delete(where: Sql, condition: Sql) = sql("DELETE FROM $childTable WHERE ", ownersIn(where), " AND ", condition)
        val nextIndex = Sql("COALESCE((SELECT MAX(x.\"idx\") FROM $childTable AS x WHERE ${schema.ownerMatch("x")}), -1) + 1")
        fun elementCondition(condition: Condition<*>): Sql? {
            val translator = SqliteConditions(schema, config)
            val translated = translator.element(condition as Condition<Any?>, child, childTable, elementSerializer)
            return translated.takeIf { translator.exact }
        }
        return when (modification) {
            is Modification.Nothing -> listOf()
            is Modification.Chain -> modification.modifications.flatMap { collectionSteps(it, child, serializer, guards, wrap) }
            is Modification.IfNotNull<*> -> {
                val marker = schema.columns[child.path] ?: return rewrite
                collectionSteps(
                    modification.modification as Modification<Any?>, child, nonNull,
                    guards + Sql("$table.${q(marker.name)} IS NOT NULL"),
                ) { wrap(Modification.IfNotNull(it) as Modification<Any?>) }
            }
            is Modification.Assign -> assign(child.path, serializer as KSerializer<Any?>, modification.value, guards) ?: rewrite
            is Modification.ListAppend<*> -> rows(modification.items).let { added ->
                statements { where -> added.map { insert(child, it, where, nextIndex) } }
            }
            is Modification.SetAppend<*> -> if (jsonElements) rewrite else rows(modification.items).let { added ->
                statements { where ->
                    added.map { row -> insert(child, row, where, index = null) { x -> sameAs(x, child.elementColumns.values) { row.values[it.name] } } }
                }
            }
            is Modification.ListRemove<*> -> elementCondition(modification.condition)?.let { condition ->
                statements { where -> listOf(delete(where, condition)) }
            } ?: rewrite
            is Modification.SetRemove<*> -> elementCondition(modification.condition)?.let { condition ->
                statements { where -> listOf(delete(where, condition)) }
            } ?: rewrite
            is Modification.ListRemoveInstances<*>, is Modification.SetRemoveInstances<*> -> {
                if (jsonElements) return rewrite
                val removed = rows(if (modification is Modification.ListRemoveInstances<*>) modification.items else (modification as Modification.SetRemoveInstances<*>).items)
                val matches = removed.map { row -> sameAs(childTable, child.elementColumns.values) { row.values[it.name] } }.joinSql(" OR ", "0")
                statements { where -> listOf(delete(where, matches)) }
            }
            is Modification.ListPerElement<*>, is Modification.SetPerElement<*> -> {
                val (condition, elementModification) = when (modification) {
                    is Modification.ListPerElement<*> -> modification.condition to modification.modification
                    else -> (modification as Modification.SetPerElement<*>).condition to modification.modification
                }
                if (!child.ordered && jsonElements) return rewrite
                val matching = elementCondition(condition) ?: return rewrite
                val set = elementAssignments(elementModification as Modification<Any?>, child, elementSerializer) ?: return rewrite
                if (set.text.isEmpty()) return listOf()
                statements { where ->
                    listOfNotNull(
                        sql("UPDATE $childTable SET ", set, " WHERE ", ownersIn(where), " AND ", matching),
                        // A set can't hold the same element twice, so changed elements that now match another merge into it.
                        if (child.ordered) null else sql(
                            "DELETE FROM $childTable WHERE ", ownersIn(where), " AND rowid NOT IN (SELECT MIN(rowid) FROM $childTable WHERE ",
                            ownersIn(where), " GROUP BY $owners, ", child.elementColumns.values.joinToString { q(it.name) }, ")",
                        ),
                    )
                }
            }
            else -> rewrite
        }
    }

    /**
     * One `SET` list applying [modification] to an element.  Every expression reads the element as it
     * was before the update, so a chain composes its steps into each column's expression.
     */
    private fun elementAssignments(modification: Modification<Any?>, child: ChildTable, serializer: KSerializer<Any?>): Sql? {
        val scope = child.elementScope(q(child.name))
        val assigned = LinkedHashMap<String, Sql>()
        fun current(column: Column) = assigned[column.name] ?: Sql(q(column.name))

        fun visit(modification: Modification<Any?>, path: String, serializer: KSerializer<Any?>, guard: Sql?): Boolean {
            fun set(column: Column, value: Sql) {
                assigned[column.name] = if (guard == null) value else sql("CASE WHEN ", guard, " THEN ", value, " ELSE ", current(column), " END")
            }
            val single = scope.columns[path]?.takeIf { !it.json }
            fun update(expression: (Sql) -> Sql): Boolean {
                if (single == null || serializer.descriptor.kind == SerialKind.ENUM) return false
                set(single, expression(current(single)))
                return true
            }
            fun stored(value: Any?) = config.encodeAt(path, serializer, value).mainRecord[path]
            return when (modification) {
                is Modification.Nothing -> true
                is Modification.Chain -> modification.modifications.all { visit(it, path, serializer, guard) }
                is Modification.IfNotNull<*> -> {
                    val exists = scope.columns["${path}__exists"]?.let { sql(current(it), " = 1") }
                        ?: scope.columns[path]?.let { sql(current(it), " IS NOT NULL") }
                        ?: return false
                    visit(
                        modification.modification as Modification<Any?>, path, serializer.nullElement() as KSerializer<Any?>,
                        if (guard == null) exists else sql(guard, " AND ", exists),
                    )
                }
                is Modification.OnField<*, *> -> {
                    val key = modification.key as Field<Any?, Any?>
                    val fieldPath = fieldPath(path, key)
                    visit(modification.modification as Modification<Any?>, fieldPath, key.serializer, guard)
                }
                is Modification.Assign -> {
                    val columns = scope.columnsAt(path)
                    if (columns.isEmpty()) return false
                    val encoded = config.encodeAt(path, serializer, modification.value).mainRecord
                    for ((key, column) in columns) set(column, arg(encoded[key]))
                    true
                }
                is Modification.Increment -> update { sql("(", it, " + ", arg(modification.by), ")") }
                is Modification.Multiply -> update { sql("(", it, " * ", arg(modification.by), ")") }
                is Modification.CoerceAtMost -> update { sql("MIN(", it, ", ", arg(stored(modification.value)), ")") }
                is Modification.CoerceAtLeast -> update { sql("MAX(", it, ", ", arg(stored(modification.value)), ")") }
                else -> false
            }
        }

        if (!visit(modification, "", serializer, null)) return null
        return Sql(assigned.entries.joinToString { (name, value) -> "${q(name)} = ${value.text}" }, assigned.values.flatMap { it.args })
    }
}
