package com.lightningkite.services.database.sql

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.mapKeyElement
import com.lightningkite.services.database.mapValueElement
import com.lightningkite.services.database.nullElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.ops.SingleValueInListOp
import org.jetbrains.exposed.sql.statements.UpdateBuilder

// ==================== Field Set ====================

internal data class SqlFieldSet<V>(
    val serializer: KSerializer<V>,
    val fields: Map<String, ExpressionWithColumnType<Any?>>,
    val format: SqlMapFormat,
    val schema: SqlSchema,
    val fieldPath: String = "",
) {
    val single: ExpressionWithColumnType<Any?>
        get() = fields[""] ?: throw IllegalStateException("No single column for ${serializer.descriptor.serialName} at path '$fieldPath', fields: ${fields.keys}")

    fun single(value: V): Pair<ExpressionWithColumnType<Any?>, Expression<Any?>> =
        single to sqlLiteralOfSomeKind(single.columnType, formatSingle(value))

    @Suppress("UNCHECKED_CAST")
    fun sub(property: SerializableProperty<V, *>): SqlFieldSet<Any?> = SqlFieldSet(
        serializer = property.serializer as KSerializer<Any?>,
        fields = fields.filter { it.key == property.name || it.key.startsWith(property.name + "__") }
            .mapKeys { it.key.substringAfter(property.name).removePrefix("__") },
        format = format,
        schema = schema,
        fieldPath = if (fieldPath.isEmpty()) property.name else "${fieldPath}__${property.name}",
    )

    @Suppress("UNCHECKED_CAST")
    val exists: Expression<Boolean>
        get() = fields["exists"]?.let { it as Expression<Boolean> } ?: IsNotNullOp(fields.values.first())

    @Suppress("UNCHECKED_CAST")
    val notExists: Expression<Boolean>
        get() = fields["exists"]?.let { NotOp(it as Expression<Boolean>) } ?: IsNullOp(fields.values.first())

    fun format(value: V): Map<ExpressionWithColumnType<Any?>, Expression<Any?>> {
        return format.encodeToMap(serializer, value).mapKeys { fields[it.key]!! }
            .mapValues { sqlLiteralOfSomeKind(it.key.columnType, it.value) }
    }

    fun formatSingle(value: V): Any? =
        format.encodeToMap(serializer, value)[""]

    fun formatSingleExpression(value: V): Expression<Any?> =
        sqlLiteralOfSomeKind(fields[""]!!.columnType, format.encodeToMap(serializer, value)[""])
}

// ==================== Condition Mapping ====================

/**
 * Maps Condition DSL to SQL expressions.
 * Tracks whether any unsupported conditions were encountered (for table-scan fallback).
 */
internal class SqlConditionContext(
    val schema: SqlSchema,
    val format: SqlMapFormat,
) {
    /** Set to false when an unsupported condition is mapped to Op.TRUE/FALSE for table scan */
    var isExact: Boolean = true
}

internal fun <T> ISqlExpressionBuilder.condition(
    condition: Condition<T>,
    serializer: KSerializer<T>,
    schema: SqlSchema,
    format: SqlMapFormat,
    ctx: SqlConditionContext,
): Expression<Boolean> {
    val fieldSet = SqlFieldSet(
        serializer = serializer,
        fields = schema.mainTable.col.mapValues {
            @Suppress("UNCHECKED_CAST")
            it.value as ExpressionWithColumnType<Any?>
        },
        format = format,
        schema = schema,
    )
    return condition(condition, fieldSet, ctx)
}

@Suppress("UNCHECKED_CAST")
private fun <T> ISqlExpressionBuilder.condition(
    condition: Condition<T>,
    fieldSet: SqlFieldSet<T>,
    ctx: SqlConditionContext,
    negated: Boolean = false,
): Expression<Boolean> {
    fun op(value: T, make: (Expression<*>, Expression<*>) -> Op<Boolean>): Op<Boolean> {
        val (col, v) = fieldSet.single(value)
        return make(col, v)
    }

    fun unsupported(): Expression<Boolean> {
        ctx.isExact = false
        return if (negated) Op.FALSE else Op.TRUE
    }

    return when (condition) {
        is Condition.Always -> Op.TRUE
        is Condition.Never -> Op.FALSE
        is Condition.And -> AndOp(condition.conditions.map { condition(it, fieldSet, ctx, negated) })
        is Condition.Or -> OrOp(condition.conditions.map { condition(it, fieldSet, ctx, negated) })
        is Condition.Not -> NotOp(condition(condition.condition, fieldSet, ctx, negated = !negated))

        is Condition.Equal -> {
            if (condition.value == null) fieldSet.notExists
            else AndOp(fieldSet.format(condition.value).entries.map { EqOp(it.key, it.value) })
        }

        is Condition.NotEqual -> {
            if (condition.value == null) fieldSet.exists
            else OrOp(fieldSet.format(condition.value).entries.map { NeqOp(it.key, it.value) })
        }

        is Condition.GreaterThan -> op(condition.value, ::GreaterOp)
        is Condition.LessThan -> op(condition.value, ::LessOp)
        is Condition.GreaterThanOrEqual -> op(condition.value, ::GreaterEqOp)
        is Condition.LessThanOrEqual -> op(condition.value, ::LessEqOp)

        is Condition.Inside -> {
            if (fieldSet.fields.size == 1)
                SingleValueInListOp(fieldSet.single, condition.values.map { fieldSet.formatSingle(it) })
            else
                OrOp(condition.values.map { value ->
                    AndOp(fieldSet.format(value).entries.map { EqOp(it.key, it.value) })
                })
        }

        is Condition.NotInside -> {
            if (fieldSet.fields.size == 1)
                NotOp(SingleValueInListOp(fieldSet.single, condition.values.map { fieldSet.formatSingle(it) }))
            else
                AndOp(condition.values.map { value ->
                    OrOp(fieldSet.format(value).entries.map { NeqOp(it.key, it.value) })
                })
        }

        is Condition.IfNotNull<*> -> {
            AndOp(listOf(
                fieldSet.exists,
                condition(condition.condition as Condition<Any?>, fieldSet as SqlFieldSet<Any?>, ctx, negated)
            ))
        }

        is Condition.OnField<*, *> -> {
            val subProperty = condition.key as SerializableProperty<T, Any?>
            val newPath = if (fieldSet.fieldPath.isEmpty()) subProperty.name else "${fieldSet.fieldPath}__${subProperty.name}"

            // Check if this navigates to a child table
            val childDef = fieldSet.schema.childTables[newPath]
            if (childDef != null) {
                conditionOnChildTable(
                    condition.condition as Condition<Any?>,
                    childDef,
                    fieldSet.schema,
                    fieldSet.format,
                    ctx,
                    negated,
                    collectionSerializer = subProperty.serializer,
                )
            } else {
                val subFieldSet = fieldSet.sub(subProperty)
                condition(condition.condition as Condition<Any?>, subFieldSet, ctx, negated)
            }
        }

        // String operations
        is Condition.StringContains -> {
            val col = fieldSet.single
            val pattern = "%${condition.value}%"
            if (condition.ignoreCase)
                InsensitiveLikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, null)
            else
                LikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, null)
        }

        is Condition.RawStringContains -> {
            val col = fieldSet.single
            val pattern = "%${condition.value}%"
            if (condition.ignoreCase)
                InsensitiveLikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, null)
            else
                LikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, null)
        }

        // Bitwise operations
        is Condition.IntBitsClear -> {
            val col = fieldSet.single(condition.mask as T)
            EqOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                sqlLiteralOfSomeKind(IntegerColumnType(), 0)
            )
        }

        is Condition.IntBitsSet -> {
            val col = fieldSet.single(condition.mask as T)
            EqOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                col.first
            )
        }

        is Condition.IntBitsAnyClear -> {
            val col = fieldSet.single(condition.mask as T)
            LessOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                col.first
            )
        }

        is Condition.IntBitsAnySet -> {
            val col = fieldSet.single(condition.mask as T)
            GreaterOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                sqlLiteralOfSomeKind(IntegerColumnType(), 0)
            )
        }

        // Collection conditions that should NOT be reached directly (handled via OnField → child table)
        // If they ARE reached, it means the condition is on a non-child-table field → unsupported
        is Condition.ListAllElements<*> -> unsupported()
        is Condition.ListAnyElements<*> -> unsupported()
        is Condition.SetAllElements<*> -> unsupported()
        is Condition.SetAnyElements<*> -> unsupported()
        is Condition.ListSizesEquals<*> -> unsupported()
        is Condition.SetSizesEquals<*> -> unsupported()
        is Condition.Exists<*> -> unsupported()
        is Condition.OnKey<*> -> unsupported()

        // Unsupported: table scan fallback
        is Condition.FullTextSearch -> unsupported()
        is Condition.GeoDistance -> unsupported()
        is Condition.RegexMatches -> {
            // Try standard SQL REGEXP (works on H2, SQLite; fails on some others)
            try {
                @Suppress("UNCHECKED_CAST")
                val col = fieldSet.single as Column<String>
                RegexpOp(col, stringLiteral(condition.pattern), true)
            } catch (_: Exception) {
                unsupported()
            }
        }

        else -> unsupported()
    }
}

/**
 * Handle conditions on collection fields stored in child tables.
 */
@Suppress("UNCHECKED_CAST")
private fun ISqlExpressionBuilder.conditionOnChildTable(
    condition: Condition<Any?>,
    childDef: SqlChildTableDef,
    schema: SqlSchema,
    format: SqlMapFormat,
    ctx: SqlConditionContext,
    negated: Boolean,
    collectionSerializer: KSerializer<*>? = null,
): Expression<Boolean> {
    val childTable = childDef.table
    val mainIdCol = schema.mainTable.idColumns.first()

    // Extract the element serializer from the collection serializer.
    // Unwrap nullable wrapper first (e.g., List<Int>? → List<Int>).
    val unwrappedCollectionSerializer = collectionSerializer?.let {
        it.nullElement() ?: it
    }
    val elementSerializer: KSerializer<Any?> = if (childDef.isMap) {
        unwrappedCollectionSerializer?.mapValueElement() as? KSerializer<Any?> ?: String.serializer() as KSerializer<Any?>
    } else {
        unwrappedCollectionSerializer?.listElement() as? KSerializer<Any?> ?: String.serializer() as KSerializer<Any?>
    }

    // Build element field set from child table columns
    fun elementFieldSet(): SqlFieldSet<Any?> {
        // Remap child table element columns for FieldSet convention:
        // Single "value" column → "" key; struct fields keep their names
        val remapped = if (childDef.table.elementColumns.size == 1 && childDef.table.elementColumns.containsKey("value")) {
            mapOf("" to childDef.table.elementColumns["value"]!! as ExpressionWithColumnType<Any?>)
        } else {
            childDef.table.elementColumns.mapValues { it.value as ExpressionWithColumnType<Any?> }
        }
        return SqlFieldSet(
            serializer = elementSerializer,
            fields = remapped,
            format = format,
            schema = schema,
            fieldPath = childDef.fieldPath,
        )
    }

    fun existsSubquery(innerCondition: Expression<Boolean>): Expression<Boolean> {
        return exists(
            childTable.select(intLiteral(1)).where {
                (childTable.ownerIdColumns[0] eq mainIdCol) and innerCondition
            }
        )
    }

    fun countSubquery(): Expression<Int> {
        @Suppress("UNCHECKED_CAST")
        return wrapAsExpression<Int>(
            childTable.select(Count(stringLiteral("*"))).where {
                childTable.ownerIdColumns[0] eq mainIdCol
            }
        ) as Expression<Int>
    }

    return when (condition) {
        is Condition.ListAnyElements<*>, is Condition.SetAnyElements<*> -> {
            val innerCond = when (condition) {
                is Condition.ListAnyElements<*> -> condition.condition as Condition<Any?>
                is Condition.SetAnyElements<*> -> condition.condition as Condition<Any?>
                else -> throw IllegalStateException()
            }
            val efs = elementFieldSet()
            existsSubquery(condition(innerCond, efs, ctx, negated))
        }

        is Condition.ListAllElements<*>, is Condition.SetAllElements<*> -> {
            val innerCond = when (condition) {
                is Condition.ListAllElements<*> -> condition.condition as Condition<Any?>
                is Condition.SetAllElements<*> -> condition.condition as Condition<Any?>
                else -> throw IllegalStateException()
            }
            val efs = elementFieldSet()
            // NOT EXISTS (... WHERE NOT <condition>)
            NotOp(existsSubquery(NotOp(condition(innerCond, efs, ctx, negated))))
        }

        is Condition.ListSizesEquals<*>, is Condition.SetSizesEquals<*> -> {
            val count = when (condition) {
                is Condition.ListSizesEquals<*> -> condition.count
                is Condition.SetSizesEquals<*> -> condition.count
                else -> throw IllegalStateException()
            }
            EqOp(countSubquery(), sqlLiteralOfSomeKind(IntegerColumnType(), count))
        }

        is Condition.Exists<*> -> {
            // Map: check if key exists
            if (!childDef.isMap) {
                ctx.isExact = false
                if (negated) Op.FALSE else Op.TRUE
            } else {
                val keyCol = childTable.keyColumns.values.firstOrNull() as? ExpressionWithColumnType<Any?>
                if (keyCol != null) {
                    existsSubquery(
                        EqOp(keyCol, sqlLiteralOfSomeKind(keyCol.columnType, condition.key))
                    )
                } else {
                    ctx.isExact = false
                    if (negated) Op.FALSE else Op.TRUE
                }
            }
        }

        is Condition.OnKey<*> -> {
            if (!childDef.isMap) {
                ctx.isExact = false
                if (negated) Op.FALSE else Op.TRUE
            } else {
                val keyCol = childTable.keyColumns.values.firstOrNull() as? ExpressionWithColumnType<Any?>
                if (keyCol != null) {
                    // Remap value columns (strip "value" prefix)
                    val valueFields = childTable.elementColumns
                        .filter { it.key.startsWith("value") }
                        .mapKeys { it.key.removePrefix("value").removePrefix("__") }
                        .mapValues { it.value as ExpressionWithColumnType<Any?> }
                    val valueFieldSet = SqlFieldSet<Any?>(
                        serializer = String.serializer() as KSerializer<Any?>,
                        fields = valueFields,
                        format = format,
                        schema = schema,
                        fieldPath = childDef.fieldPath,
                    )
                    existsSubquery(
                        AndOp(listOf(
                            EqOp(keyCol, sqlLiteralOfSomeKind(keyCol.columnType, condition.key)),
                            condition(condition.condition as Condition<Any?>, valueFieldSet, ctx, negated)
                        ))
                    )
                } else {
                    ctx.isExact = false
                    if (negated) Op.FALSE else Op.TRUE
                }
            }
        }

        // If we reach Equal/NotEqual/etc. on a whole collection, fall back to table scan
        is Condition.Equal -> {
            ctx.isExact = false
            if (negated) Op.FALSE else Op.TRUE
        }
        is Condition.NotEqual -> {
            ctx.isExact = false
            if (negated) Op.FALSE else Op.TRUE
        }

        else -> {
            ctx.isExact = false
            if (negated) Op.FALSE else Op.TRUE
        }
    }
}

// ==================== Modification Mapping (scalar only) ====================

/**
 * Returns true if this modification can be fully expressed as SQL UPDATE SET clauses
 * (no collection modifications).
 */
internal fun <T> Modification<T>.isScalarOnly(schema: SqlSchema, path: String = ""): Boolean = when (this) {
    is Modification.Nothing -> true
    is Modification.Chain -> modifications.all { it.isScalarOnly(schema, path) }
    is Modification.Assign -> !schema.childTables.keys.any { it == path || it.startsWith("${path}__") || path.isEmpty() && schema.childTables.isNotEmpty() }
    is Modification.IfNotNull<*> -> (modification as Modification<Any?>).isScalarOnly(schema, path)
    is Modification.Increment -> true
    is Modification.Multiply -> true
    is Modification.CoerceAtMost -> true
    is Modification.CoerceAtLeast -> true
    is Modification.AppendString -> true
    is Modification.AppendRawString -> true
    is Modification.OnField<*, *> -> {
        val newPath = if (path.isEmpty()) key.name else "${path}__${key.name}"
        (modification as Modification<Any?>).isScalarOnly(schema, newPath)
    }
    // All collection modifications
    is Modification.ListAppend<*> -> false
    is Modification.ListRemove<*> -> false
    is Modification.ListRemoveInstances<*> -> false
    is Modification.ListPerElement<*> -> false
    is Modification.ListDropFirst<*> -> false
    is Modification.ListDropLast<*> -> false
    is Modification.SetAppend<*> -> false
    is Modification.SetRemove<*> -> false
    is Modification.SetRemoveInstances<*> -> false
    is Modification.SetPerElement<*> -> false
    is Modification.SetDropFirst<*> -> false
    is Modification.SetDropLast<*> -> false
    is Modification.Combine<*> -> false
    is Modification.ModifyByKey<*> -> false
    is Modification.RemoveKeys<*> -> false
}

internal interface FieldModifier {
    fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>)
}

internal fun FieldModifier.sub(subKey: String): FieldModifier = object : FieldModifier {
    override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
        if (key.isEmpty()) this@sub.modify(subKey, modify)
        else this@sub.modify("${subKey}__$key", modify)
    }
}

internal inline fun <T> FieldModifier.modifySingle(
    set: SqlFieldSet<T>,
    crossinline action: (type: IColumnType<Any>, old: Expression<Any?>) -> Expression<Any?>,
) {
    modify("") { action(set.single.columnType, it) }
}

internal inline fun <T> FieldModifier.modifyEach(
    set: SqlFieldSet<T>,
    value: T,
    crossinline action: (type: IColumnType<Any>, value: Expression<Any?>, old: Expression<Any?>) -> Expression<Any?>,
) {
    set.format.encodeToMap(set.serializer, value).forEach {
        modify(it.key) { old ->
            val t = set.fields[it.key]!!.columnType
            action(t, sqlLiteralOfSomeKind(t, it.value), old)
        }
    }
}

/**
 * Apply scalar-only modifications to an UpdateBuilder.
 * Collection modifications are skipped (handled in-memory by SqlCollection).
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> UpdateBuilder<*>.modification(
    modification: Modification<T>,
    serializer: KSerializer<T>,
    schema: SqlSchema,
    format: SqlMapFormat,
) {
    val table = schema.mainTable
    val map = HashMap<String, Expression<Any?>>()
    object : FieldModifier {
        fun default(key: String) = table.col[key]!! as Expression<Any?>
        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
            map[key] = modify(map[key] ?: default(key))
        }
    }.scalarModification(
        modification,
        SqlFieldSet(serializer, table.col.mapValues { it.value as ExpressionWithColumnType<Any?> }, format, schema),
        schema,
    )
    for (entry in map) {
        val col = table.col[entry.key] ?: continue
        this.update(col as Column<Any?>, entry.value)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> FieldModifier.scalarModification(
    modification: Modification<T>,
    fieldSet: SqlFieldSet<T>,
    schema: SqlSchema,
    path: String = "",
) {
    when (modification) {
        is Modification.Nothing -> {}
        is Modification.Chain -> modification.modifications.forEach { scalarModification(it, fieldSet, schema, path) }
        is Modification.Assign -> {
            // Only assign if this doesn't touch collections
            if (schema.childTables.keys.none { it == path || it.startsWith("${path}__") || (path.isEmpty() && schema.childTables.isNotEmpty()) }) {
                modifyEach(fieldSet, modification.value) { _, it, _ -> it }
            } else if (path.isEmpty()) {
                // Full assign: write all main table fields
                val encoded = fieldSet.format.encodeToMap(fieldSet.serializer, modification.value)
                for ((key, value) in encoded) {
                    if (fieldSet.fields.containsKey(key)) {
                        modify(key) { sqlLiteralOfSomeKind(fieldSet.fields[key]!!.columnType, value) }
                    }
                }
            }
        }
        is Modification.IfNotNull<*> -> scalarModification(
            modification.modification as Modification<Any?>,
            fieldSet as SqlFieldSet<Any?>,
            schema,
            path,
        )
        is Modification.Increment -> modifySingle(fieldSet) { type, old ->
            PlusOp(fieldSet.formatSingleExpression(modification.by), old, type)
        }
        is Modification.Multiply -> modifySingle(fieldSet) { type, old ->
            TimesOp(fieldSet.formatSingleExpression(modification.by), old, type)
        }
        is Modification.CoerceAtMost -> modifySingle(fieldSet) { type, old ->
            CustomFunction("LEAST", type, fieldSet.formatSingleExpression(modification.value), old)
        }
        is Modification.CoerceAtLeast -> modifySingle(fieldSet) { type, old ->
            CustomFunction("GREATEST", type, fieldSet.formatSingleExpression(modification.value), old)
        }
        is Modification.AppendString -> modifySingle(fieldSet) { type, old ->
            Concat("", old, fieldSet.formatSingleExpression(modification.value as T)) as Expression<Any?>
        }
        is Modification.AppendRawString -> modifySingle(fieldSet) { type, old ->
            Concat("", old, fieldSet.formatSingleExpression(modification.value as T)) as Expression<Any?>
        }
        is Modification.OnField<*, *> -> {
            val newPath = if (path.isEmpty()) modification.key.name else "${path}__${modification.key.name}"
            // Skip if this navigates to a child table field
            if (!schema.childTables.containsKey(newPath)) {
                sub(modification.key.name).scalarModification(
                    modification.modification as Modification<Any?>,
                    fieldSet.sub(modification.key as SerializableProperty<T, Any?>),
                    schema,
                    newPath,
                )
            }
            // Child table modifications are handled in-memory by SqlCollection
        }
        // Collection modifications: skip (handled in-memory)
        else -> {}
    }
}
