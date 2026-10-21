package com.lightningkite.services.database.sql

import com.lightningkite.services.database.optics.*

import org.jetbrains.exposed.v1.core.*

// Flatten a path's properties into a "__"-joined column name, matching SqlSchema's registration.
// Properties whose owner is a Kotlin value/inline class (property.inline) don't get their own
// path segment: value classes are collapsed away when the schema registers columns (mirroring
// kotlinx serialization's transparent encodeInline), so they must be skipped here too.
internal val OpticOn<*>.colName: String
    get() = fields.filterNot { it.isInline }.joinToString("__") { it.name }

// Create a typed SQL literal from a column type and value
@Suppress("UNCHECKED_CAST")
internal fun sqlLiteralOfSomeKind(columnType: IColumnType<*>, value: Any?): Expression<Any?> {
    return QueryParameter(value, columnType as IColumnType<Any>) as Expression<Any?>
}

// Case-insensitive LIKE operator that works across SQL dialects
internal class InsensitiveLikeEscapeOp(
    val expr1: Expression<*>,
    val pattern: Expression<*>,
    val isLikeNotLike: Boolean,
    val escapeChar: Char?,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("LOWER(")
        queryBuilder.append(expr1)
        queryBuilder.append(")")
        if (!isLikeNotLike) queryBuilder.append(" NOT")
        queryBuilder.append(" LIKE LOWER(")
        queryBuilder.append(pattern)
        queryBuilder.append(")")
        if (escapeChar != null) {
            queryBuilder.append(" ESCAPE '")
            queryBuilder.append(escapeChar.toString())
            queryBuilder.append("'")
        }
    }
}
