package com.lightningkite.services.database.sql

import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.DataClassPathPartial
import org.jetbrains.exposed.sql.*

// Extension to get flattened column name from a DataClassPath (joins property names with "__")
internal val DataClassPath<*, *>.colName: String
    get() = properties.joinToString("__") { it.name }

// Extension to get flattened column name from a DataClassPathPartial (joins property names with "__")
internal val DataClassPathPartial<*>.colName: String
    get() = properties.joinToString("__") { it.name }

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
