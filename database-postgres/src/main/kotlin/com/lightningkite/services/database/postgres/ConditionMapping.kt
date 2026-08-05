@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.ops.SingleValueInListOp
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

// The backslash used to escape literal `%`/`_` in `StringContains`/`RawStringContains` LIKE
// patterns (see [escapeLikeValue]). Backslash is not a special character in Postgres's LIKE syntax
// by default, so it's safe to reserve as the escape char here.
private const val LIKE_ESCAPE_CHAR = '\\'

/**
 * Escapes a literal search value so it can be embedded inside a `%...%` LIKE pattern without its
 * own `%`/`_` characters being interpreted as SQL wildcards. The in-memory reference for
 * `StringContains`/`RawStringContains` is `on.contains(value)` — a literal substring search where
 * every character of [value] is significant — so this (together with passing [LIKE_ESCAPE_CHAR] as
 * the op's `escapeChar`, which emits `ESCAPE '\'`) is required to match that semantics.
 */
private fun escapeLikeValue(value: String): String = buildString(value.length) {
    for (c in value) {
        if (c == LIKE_ESCAPE_CHAR || c == '%' || c == '_') append(LIKE_ESCAPE_CHAR)
        append(c)
    }
}

internal data class FieldSet2<V>(
    val serializer: KSerializer<V>,
    val fields: Map<String, ExpressionWithColumnType<Any?>>,
    val format: DbMapLikeFormat,
) {
    constructor(serializer: KSerializer<V>, table: SerialDescriptorTable, format: DbMapLikeFormat) : this(
        serializer = serializer,
        fields = table.col.mapValues {
            @Suppress("UNCHECKED_CAST")
            it.value as ExpressionWithColumnType<Any?>
        },
        format = format
    )

    val single: ExpressionWithColumnType<Any?>
        get() = fields[""] ?: throw IllegalStateException("No column found for ${serializer.descriptor.serialName}")

    fun single(value: V): Pair<ExpressionWithColumnType<Any?>, Expression<Any?>> =
        single to sqlLiteralOfSomeKind(single.columnType, formatSingle(value))

    @Suppress("UNCHECKED_CAST")
    fun sub(property: SerializableProperty<V, *>): FieldSet2<Any?> {
        // TODO: This is fugly, fix. Inline check can probably be made before matching filters.
        val matched = fields.filter { it.key == property.name || it.key.startsWith(property.name + "__") }
            .mapKeys { it.key.substringAfter(property.name).removePrefix("__") }
        // by Claude - SerialDescriptorTable flattens inline/value classes (e.g. `value class IntWrapper(val
        // int: Int)`) into the SAME column as their one wrapped member, rather than nesting it under the
        // member's name. So a fieldset that already IS such a wrapped value (single "" key) has no literal
        // "<memberName>" key to match above. When the property being navigated to is exactly that value's
        // sole wrapped member, the wrapped value and its member are the same column - pass fields through.
        val resolved = matched.ifEmpty {
            val d = serializer.descriptor
            if (fields.keys == setOf("") && d.isInline && d.getElementName(0) == property.name) fields
            else matched
        }
        return FieldSet2(
            serializer = property.serializer as KSerializer<Any?>,
            fields = resolved,
            format = format,
        )
    }

    @Suppress("UNCHECKED_CAST")
    val exists: Expression<Boolean>
        get() = fields["exists"]?.let {
            it as Expression<Boolean>
        } ?: IsNotNullOp(fields.values.first())

    @Suppress("UNCHECKED_CAST")
    val notExists: Expression<Boolean>
        get() = fields["exists"]?.let {
            NotOp(it as Expression<Boolean>)
        } ?: IsNullOp(fields.values.first())

    fun format(value: V): Map<ExpressionWithColumnType<Any?>, Expression<Any?>> {
        return format.encode(serializer, value)
            .mapKeys {
                fields[it.key]!!
            }.mapValues { sqlLiteralOfSomeKind(it.key.columnType, it.value) }
    }

    fun formatSingle(value: V): Any? {
        return format.encode(
            serializer,
            value
        )[""]
    }

    fun formatSingleExpression(value: V): Expression<Any?> {
        return sqlLiteralOfSomeKind(
            fields[""]!!.columnType,
            format.encode(
                serializer,
                value
            )[""])
    }
}

internal fun <T> condition(
    condition: Condition<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable,
    format: DbMapLikeFormat,
): Expression<Boolean> = condition(condition, FieldSet2(serializer, table, format))

@Suppress("UNCHECKED_CAST")
private fun <T> condition(
    condition: Condition<T>,
    fieldSet: FieldSet2<T>,
): Expression<Boolean> {
    fun op(value: T, make: (Expression<*>, Expression<*>) -> Op<Boolean>): Op<Boolean> {
        val (col, v) = fieldSet.single(value)
        return make(col, v)
    }
    return when (condition) {
        is Condition.Always -> Op.TRUE
        is Condition.Never -> Op.FALSE
        is Condition.And -> AndOp(condition.conditions.map { condition(it, fieldSet) })
        is Condition.Or -> OrOp(condition.conditions.map { condition(it, fieldSet) })
        is Condition.Equal -> {
            if (condition.value == null) {
                fieldSet.notExists
            } else {
                AndOp(fieldSet.format(condition.value).entries.map { EqOp(it.key, it.value) })
            }
        }

        is Condition.NotEqual -> {
            if (condition.value == null) {
                fieldSet.exists
            } else {
                OrOp(fieldSet.format(condition.value).entries.map { NeqOp(it.key, it.value) })
            }
        }

        is Condition.SetAllElements<*> -> {
            AllIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
        }

        is Condition.ListAllElements<*> -> {
            AllIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
        }

        is Condition.SetAnyElements<*> -> {
            AnyIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
        }

        is Condition.ListAnyElements<*> -> {
            AnyIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
        }

        is Condition.Exists<*> -> {
            val keyValue =
                fieldSet.format.encode(fieldSet.serializer.mapKeyElement()!! as KSerializer<Any?>, condition.key)[""]
            ContainsOp(fieldSet.single, sqlLiteralOfSomeKind(fieldSet.single.columnType, listOf(keyValue)))
        }

        is Condition.OnKey<*> -> {
            val keyValue =
                fieldSet.format.encode(fieldSet.serializer.mapKeyElement()!! as KSerializer<Any?>, condition.key)[""]
            condition(
                condition = condition.condition as Condition<Any?>,
                fieldSet = FieldSet2<Any?>(
                    serializer = fieldSet.serializer.mapValueElement()!! as KSerializer<Any?>,
                    fields = fieldSet.fields.entries.asSequence()
                        .filter { it.key.startsWith("value") }
                        .associate {
                            it.key.removePrefix("value").removePrefix("__") to object :
                                ExpressionWithColumnType<Any?>() {
                                override val columnType: IColumnType<Any>
                                    get() = (it.value.columnType as ArrayColumnType<Any>).type

                                override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                                    GetOp(
                                        it.value as Expression<List<Any?>>,
                                        CustomFunction<Int>(
                                            "array_position",
                                            IntegerColumnType(),
                                            fieldSet.single,
                                            sqlLiteralOfSomeKind(
                                                (fieldSet.fields[""]!!.columnType as ArrayColumnType<Any>).type,
                                                keyValue
                                            )
                                        )
                                    ).toQueryBuilder(queryBuilder)
                                }
                            }
                        },
                    format = fieldSet.format,
                )
            )
        }
//        is Condition.FullTextSearch -> throw IllegalArgumentException()

        is Condition.GreaterThan -> op(condition.value, ::GreaterOp)
        is Condition.LessThan -> op(condition.value, ::LessOp)
        is Condition.GreaterThanOrEqual -> op(condition.value, ::GreaterEqOp)
        is Condition.LessThanOrEqual -> op(condition.value, ::LessEqOp)
        is Condition.IfNotNull<*> -> {
            AndOp(
                listOf(
                    fieldSet.exists,
                    condition<Any?>(
                        condition.condition as Condition<Any?>,
                        fieldSet as FieldSet2<Any?>
                    )
                )
            )
        }

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
                // `NOT (col IN (...))` is NULL (excluded by WHERE) when col is NULL, under SQL's
                // three-valued logic — but the in-memory reference (!values.contains(on)) treats a
                // null field as vacuously "not in the list" and includes it. OR in the null check to
                // match; harmless on a NOT NULL column, where it's always false.
                OrOp(listOf(
                    fieldSet.notExists,
                    NotOp(SingleValueInListOp(fieldSet.single, condition.values.map { fieldSet.formatSingle(it) })),
                ))
            else
                AndOp(condition.values.map { value ->
                    OrOp(fieldSet.format(value).entries.map { NeqOp(it.key, it.value) })
                })
        }

        is Condition.IntBitsAnyClear -> {
            val col = fieldSet.single(condition.mask as T)
            return LessOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                col.first
            )
        }

        is Condition.IntBitsAnySet -> {
            val col = fieldSet.single(condition.mask as T)
            return GreaterOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                sqlLiteralOfSomeKind(IntegerColumnType(), 0)
            )
        }

        is Condition.IntBitsClear -> {
            val col = fieldSet.single(condition.mask as T)
            return EqOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                sqlLiteralOfSomeKind(IntegerColumnType(), 0)
            )
        }

        is Condition.IntBitsSet -> {
            val col = fieldSet.single(condition.mask as T)
            return EqOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                col.first
            )
        }

        is Condition.Not -> NotOp(condition(condition.condition, fieldSet))
        is Condition.GeoDistance -> TODO()
        // Wrap value with % for substring matching. The value itself is escaped (see
        // [escapeLikeValue]) so a literal '%'/'_' in the search value can't act as a SQL wildcard.
        is Condition.StringContains -> {
            val col = fieldSet.single
            val pattern = "%${escapeLikeValue(condition.value)}%"
            if (condition.ignoreCase)
                InsensitiveLikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, LIKE_ESCAPE_CHAR)
            else
                LikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, LIKE_ESCAPE_CHAR)
        }

        is Condition.RawStringContains -> {
            val col = fieldSet.single
            val pattern = "%${escapeLikeValue(condition.value)}%"
            if (condition.ignoreCase)
                InsensitiveLikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, LIKE_ESCAPE_CHAR)
            else
                LikeEscapeOp(col, sqlLiteralOfSomeKind(TextColumnType(), pattern), true, LIKE_ESCAPE_CHAR)
        }

        is Condition.RegexMatches -> {
            val col = fieldSet.single
            RegexpOp(col as Column<String>, sqlLiteralOfSomeKind(TextColumnType(), condition.pattern), true)
        }

        is Condition.SetSizesEquals<*> -> {
            val col = fieldSet.single
            EqOp(
                ArrayLengthOp(col as Column<List<Any?>>),
                sqlLiteralOfSomeKind(IntegerColumnType(), condition.count)
            )
        }

        is Condition.ListSizesEquals<*> -> {
            val col = fieldSet.single
            EqOp(
                ArrayLengthOp(col as Column<List<Any?>>),
                sqlLiteralOfSomeKind(IntegerColumnType(), condition.count)
            )
        }

        is Condition.OnField<*, *> -> condition<Any?>(
            condition.condition as Condition<Any?>,
            fieldSet.sub(condition.key as SerializableProperty<T, Any?>)
        )

        else -> throw IllegalArgumentException()
    }
}

internal interface FieldModifier {
    fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>)
}

internal fun FieldModifier.sub(subKey: String): FieldModifier {
    return object : FieldModifier {
        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
            if (key.isEmpty()) this@sub.modify(subKey, modify)
            else this@sub.modify(subKey + "__" + key, modify)
        }
    }
}

internal inline fun <T> FieldModifier.modifySingle(
    set: FieldSet2<T>,
    crossinline action: (type: IColumnType<Any>, old: Expression<Any?>) -> Expression<Any?>,
) {
    modify("") { action(set.single.columnType, it) }
}

internal inline fun <T> FieldModifier.modifyEach(
    set: FieldSet2<T>,
    value: T,
    crossinline action: (type: IColumnType<Any>, value: Expression<Any?>, old: Expression<Any?>) -> Expression<Any?>,
) {
    set.format.encode(set.serializer, value).forEach {
        modify(it.key) { old ->
            val t = set.fields[it.key]!!.columnType
            action(t, sqlLiteralOfSomeKind(t, it.value), old)
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> UpdateBuilder<*>.modification(
    modification: Modification<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable,
    format: DbMapLikeFormat,
) {
    val map = HashMap<String, Expression<Any?>>()
    object : FieldModifier {
        fun default(key: String) = table.col[key]!! as Expression<Any?>
        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
            map[key] = modify(map[key] ?: default(key))
        }
    }.modification(modification, serializer, table, format)
    for (entry in map) {
        this.update(table.col[entry.key]!! as Column<Any?>, entry.value)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> UpdateReturningOldStatement.modification(
    modification: Modification<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable,
    format: DbMapLikeFormat,
) {
    val map = HashMap<String, Expression<Any?>>()
    object : FieldModifier {
        fun default(key: String) = table.col[key]!! as Expression<Any?>
        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
            map[key] = modify(map[key] ?: default(key))
        }
    }.modification(modification, serializer, table, format)
    for (entry in map) {
        this.update(table.col[entry.key]!! as Column<Any?>, entry.value)
    }
}

internal fun <T> FieldModifier.modification(
    modification: Modification<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable,
    format: DbMapLikeFormat,
): Unit = modification(modification, FieldSet2(serializer, table, format))

@Suppress("UNCHECKED_CAST")
private fun <T> FieldModifier.modification(
    modification: Modification<T>,
    fieldSet: FieldSet2<T>,
): Unit {
    when (modification) {
        is Modification.Nothing -> {}
        is Modification.Chain -> modification.modifications.forEach { modification(it, fieldSet) }
        is Modification.Assign -> modifyEach(fieldSet, modification.value) { type, it, old -> it }
        is Modification.IfNotNull<*> -> modification<Any?>(
            modification.modification as Modification<Any?>,
            fieldSet as FieldSet2<Any?>
        )

        is Modification.CoerceAtMost -> modifySingle(fieldSet) { type, old ->
            CustomFunction(
                "LEAST",
                type,
                fieldSet.formatSingleExpression(modification.value),
                old
            )
        }

        is Modification.CoerceAtLeast -> modifySingle(fieldSet) { type, old ->
            CustomFunction(
                "GREATEST",
                type,
                fieldSet.formatSingleExpression(modification.value),
                old
            )
        }

        is Modification.Increment -> modifySingle(fieldSet) { type, old ->
            PlusOp(
                fieldSet.formatSingleExpression(
                    modification.by
                ), old, type
            )
        }

        is Modification.Multiply -> modifySingle(fieldSet) { type, old ->
            TimesOp(
                fieldSet.formatSingleExpression(
                    modification.by
                ), old, type
            )
        }

        is Modification.AppendString -> modifySingle(fieldSet) { type, old ->
            Concat(
                "",
                old,
                fieldSet.formatSingleExpression(modification.value as T)
            ) as Expression<Any?>
        }

        is Modification.AppendRawString -> modifySingle(fieldSet) { type, old ->
            Concat(
                "",
                old,
                fieldSet.formatSingleExpression(modification.value as T)
            ) as Expression<Any?>
        }

        is Modification.ListAppend<*> -> modifyEach(fieldSet, modification.items as T) { type, it, old ->
            ConcatOp(
                old,
                it
            )
        }

        is Modification.ListRemove<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                MapOp(
                    fieldSet as FieldSet2<List<Any?>>,
                    { f -> f.fields[it.key]!! },
                    {
                        NotOp(
                            condition(
                                modification.condition as Condition<Any?>,
                                it
                            )
                        )
                    }) as Expression<Any?>
            }
        }

        is Modification.ListRemoveInstances<*> -> modification(
            Modification.ListRemove(Condition.Inside(modification.items)) as Modification<T>,
            fieldSet
        )

        is Modification.ListPerElement<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                MapOp(
                    sources = fieldSet as FieldSet2<List<Any?>>,
                    mapper = { f ->
                        lateinit var result: Expression<Any?>
                        object : FieldModifier {
                            override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
                                result = modify(f.fields[it.key]!!)
                            }
                        }.modification(modification.modification as Modification<Any?>, f)
                        if (modification.condition is Condition.Always) result
                        else run {
                            case()
                                .When(condition(modification.condition as Condition<Any?>, f), result)
                                .Else(f.fields[it.key]!!)
                        }
                    }
                ) as Expression<Any?>
            }
        }

        is Modification.ListDropFirst<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                SliceOp(
                    old as Expression<List<Any?>>,
                    from = sqlLiteralOfSomeKind(IntegerColumnType(), 2)
                ) as Expression<Any?>
            }
        }

        is Modification.ListDropLast<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                SliceOp(
                    old as Expression<List<Any?>>,
                    to = MinusOp(
                        ArrayLengthOp(old as Expression<List<Any?>>),
                        sqlLiteralOfSomeKind(IntegerColumnType(), 1),
                        IntegerColumnType()
                    )
                ) as Expression<Any?>
            }
        }

        is Modification.SetDropFirst<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                SliceOp(
                    old as Expression<List<Any?>>,
                    from = sqlLiteralOfSomeKind(IntegerColumnType(), 2)
                ) as Expression<Any?>
            }
        }

        is Modification.SetDropLast<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                SliceOp(
                    old as Expression<List<Any?>>,
                    to = MinusOp(
                        ArrayLengthOp(old as Expression<List<Any?>>),
                        sqlLiteralOfSomeKind(IntegerColumnType(), 1),
                        IntegerColumnType()
                    )
                ) as Expression<Any?>
            }
        }

        is Modification.SetAppend<*> -> {
            if (fieldSet.fields.size == 1) {
                modifySingle(fieldSet) { type, old ->
                    object : Op<Any?>() {
                        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                            queryBuilder.append("ARRAY(SELECT DISTINCT UNNEST(")
                            queryBuilder.append(old)
                            queryBuilder.append(" || ")
                            queryBuilder.append(fieldSet.formatSingleExpression(modification.items as T))
                            queryBuilder.append("))")
                        }
                    }
                }
            } else TODO()
        }

        is Modification.SetRemove<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                MapOp(
                    fieldSet as FieldSet2<List<Any?>>,
                    { f -> f.fields[it.key]!! },
                    {
                        run {
                            NotOp(
                                condition(
                                    modification.condition as Condition<Any?>,
                                    it
                                )
                            )
                        }
                    }) as Expression<Any?>
            }
        }

        is Modification.SetRemoveInstances<*> -> modification(
            Modification.SetRemove(Condition.Inside(modification.items.toList())) as Modification<T>,
            fieldSet
        )

        is Modification.SetPerElement<*> -> fieldSet.fields.forEach {
            modify(it.key) { old ->
                MapOp(
                    sources = fieldSet as FieldSet2<List<Any?>>,
                    mapper = { f ->
                        lateinit var result: Expression<Any?>
                        object : FieldModifier {
                            override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
                                result = modify(f.fields[it.key]!!)
                            }
                        }.modification(modification.modification as Modification<Any?>, f)
                        if (modification.condition is Condition.Always) result
                        else run {
                            case()
                                .When(condition(modification.condition as Condition<Any?>, f), result)
                                .Else(f.fields[it.key]!!)
                        }
                    }
                ) as Expression<Any?>
            }
        }

        is Modification.Combine<*> -> {
            // Maps are stored as parallel arrays -- one per key-path segment of the key type, one
            // (suffixed "value") per key-path segment of the value type; see
            // SerialDescriptorTable.columnType's StructureKind.MAP branch. `on + map` means: entries
            // whose key is being replaced are dropped from the old arrays (via the same key-array
            // filter [MapOp] uses for RemoveKeys below), then the new entries are appended -- to
            // every column in lockstep, so the key/value arrays stay aligned by position.
            val newEntries = modification.map
            val newColumnLiterals = fieldSet.format(modification.map as T)
            fieldSet.fields.forEach { (colKey, colExpr) ->
                modify(colKey) {
                    @Suppress("UNCHECKED_CAST")
                    ConcatOp(
                        MapOp(
                            fieldSet as FieldSet2<List<Any?>>,
                            mapper = { fs -> fs.fields[colKey]!! },
                            filter = { fs -> NotOp(SingleValueInListOp(fs.fields[""]!!, newEntries.keys.toList())) },
                        ),
                        newColumnLiterals[colExpr]!! as Expression<List<Any?>>,
                    ) as Expression<Any?>
                }
            }
        }

        is Modification.RemoveKeys<*> -> {
            // Same array-filter shape as the Combine branch above, minus the append: keep only the
            // entries whose key isn't being removed, applied to every column (key + value arrays) in
            // lockstep so they stay aligned by position.
            val keysToRemove = modification.fields.toList()
            fieldSet.fields.forEach { (colKey, _) ->
                modify(colKey) {
                    MapOp(
                        fieldSet as FieldSet2<List<Any?>>,
                        mapper = { fs -> fs.fields[colKey]!! },
                        filter = { fs -> NotOp(SingleValueInListOp(fs.fields[""]!!, keysToRemove)) },
                    ) as Expression<Any?>
                }
            }
        }
        is Modification.OnField<*, *> -> {
            val key = modification.key as SerializableProperty<T, Any?>
            val d = fieldSet.serializer.descriptor
            // by Claude - mirrors FieldSet2.sub: navigating into an inline/value class's sole wrapped
            // member targets the SAME physical column as the value class itself, so the accumulated
            // output key must not gain an extra "__member" suffix that no column was ever registered
            // under (see SerialDescriptorTable's isInline flattening).
            val nextModifier = if (d.isInline && d.getElementName(0) == key.name) this else sub(key.name)
            nextModifier.modification(
                modification.modification as Modification<Any?>,
                fieldSet.sub(key)
            )
        }
    }
}