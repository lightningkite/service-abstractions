package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.optics.*

import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.QueryParameter

// by Claude - SerialDescriptorTable flattens inline/value classes (e.g. `value class IntWrapper(val int:
// Int)`) into the SAME column as their sole wrapped member instead of nesting under the member's name (see
// SerialDescriptorTable.columnType's `isInline` branch). A property whose declaring class is inline
// (`Field.isInline`) therefore contributes no segment of its own to the physical column name -
// e.g. `path.wrappedInt.int` must resolve to column "wrappedInt", not "wrappedInt__int".
internal val OpticOn<*>.colName: String
    get() = fields.filterNot { it.isInline }.joinToString("__") { it.name }
internal fun <T> sqlLiteralOfSomeKind(type: IColumnType<T & Any>, value: T) = QueryParameter(value, type)

@Suppress("Unchecked_cast")
internal fun sqlLiteralOfSomeKindUntyped(type: IColumnType<*>, value: Any?) =
    QueryParameter(value, type as IColumnType<Any>)