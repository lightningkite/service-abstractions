package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.DataClassPathPartial
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.QueryParameter

internal val DataClassPathPartial<*>.colName: String get() = properties.joinToString("__") { it.name }
internal fun <T> sqlLiteralOfSomeKind(type: IColumnType<T & Any>, value: T) = QueryParameter(value, type)
internal fun sqlLiteralOfSomeKindUntyped(type: IColumnType<*>, value: Any?) = QueryParameter(value, type as IColumnType<Any>)