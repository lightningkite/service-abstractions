package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.listElement
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager

internal fun <T> Table.array(name: String, columnType: ColumnType<T>): Column<List<T>> = registerColumn(name, ArrayColumnType(columnType))

@Suppress("Unchecked_cast")
internal fun Table.arrayTypeless(name: String, columnType: ColumnType<*>): Column<List<*>> =
    registerColumn(name, ArrayColumnType(columnType)) as Column<List<*>>

internal class ArrayColumnType<T>(val type: ColumnType<T>) : ColumnType<List<T>>() {
    override fun sqlType(): String = buildString {
        append(type.sqlType())
        append(" ARRAY")
    }
    override fun valueToDB(value: List<T>?): Any? {
        if(value == null) return null
        val columnType = type.sqlType().split("(")[0]
        val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
        return jdbcConnection.createArrayOf(columnType, value.map { type.valueToDB(it) }.toTypedArray())
    }

    @Suppress("Unchecked_cast")
    override fun valueFromDB(value: Any): List<T> {
        return when (value) {
            is java.sql.Array -> (value.array as Array<*>).toList()
            is Array<*> -> value.toList()
            is List<*> -> value
            else -> error("Not sure how to parse ${value} (${value::class.qualifiedName}) from the database!")
        }.map { (if(it == null) null else type.valueFromDB(it)) as T }
    }

    override fun valueToString(value: List<T>?): String {
        if(value == null) return "NULL"
        return value.joinToString(",", "ARRAY[", "]::${type.sqlType()}[]") { type.valueToString(it) }
    }

    override fun notNullValueToDB(value: List<T>): Any {
        if (value.isEmpty())
            return "'{}'"

        val columnType = type.sqlType().split("(")[0]
        val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
        return jdbcConnection.createArrayOf(columnType, value.map { type.valueToDB(it) }.toTypedArray()) ?: error("Can't create non null array for $value")
    }
}

//class ArrayOp<T>(val expr1: Expression<List<T>>, val keyword: String = "ANY") : Op<T>() {
//    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
//        if (expr2 is OrOp) {
//            queryBuilder.append("(").append(expr2).append(")")
//        } else {
//            queryBuilder.append(expr2)
//        }
//        queryBuilder.append(" = $keyword (")
//        if (expr1 is OrOp) {
//            queryBuilder.append("(").append(expr1).append(")")
//        } else {
//            queryBuilder.append(expr1)
//        }
//        queryBuilder.append(")")
//    }
//}

internal fun <T> ArrayLengthOp(array: Expression<List<T>>) = CustomFunction<Int>("array_length", IntegerColumnType(), array, intLiteral(1))
internal fun <T> ArrayIndexOfOp(array: Expression<List<T>>, value: Expression<T>) = CustomFunction<Int>("array_position", IntegerColumnType(), array, value)
internal fun AsciiValue(value: Expression<String>) = object: ExpressionWithColumnType<ByteArray>() {
    override val columnType: IColumnType<ByteArray> = BinaryColumnType(100)
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(value)
        queryBuilder.append("::bytea")
    }
}

internal class SliceOp<T>(
    val source: Expression<List<T>>,
    val from: Expression<Int> = LiteralOp(IntegerColumnType(), 1),
    val to: Expression<Int> = ArrayLengthOp(source),
): Op<List<T>>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(source)
        queryBuilder.append('[')
        queryBuilder.append(from)
        queryBuilder.append(':')
        queryBuilder.append(to)
        queryBuilder.append(']')
    }
}

internal class ConcatOp<T>(vararg val sources: Expression<T>): Op<T>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        var first = true
        for(entry in sources) {
            if(first) first = false
            else queryBuilder.append(" || ")
            queryBuilder.append(entry)
        }
    }
}

internal class GetOp<T>(val source: Expression<List<T>>, val index: Expression<Int>): Op<T>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(source)
        queryBuilder.append("[")
        queryBuilder.append(index)
        queryBuilder.append("]")
    }
}

internal class AllIsTrueOp(val source: Expression<List<Boolean>>): Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("TRUE = ALL (")
        queryBuilder.append(source)
        queryBuilder.append(")")
    }
}

internal class AnyIsTrueOp(val source: Expression<List<Boolean>>): Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("TRUE = ANY (")
        queryBuilder.append(source)
        queryBuilder.append(")")
    }
}

internal class MapOp<A, B>(
    val sources: FieldSet2<List<A>>,
    val mapper: (FieldSet2<A>) -> Expression<B>,
    val filter: (FieldSet2<A>) -> Expression<Boolean> = { Op.TRUE },
): Op<List<B>>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        @Suppress("UNCHECKED_CAST")
        val fs = FieldSet2<A>(
            sources.serializer.listElement()!! as KSerializer<A>,
            fields = sources.fields.mapValues {
                object: ExpressionWithColumnType<Any?>() {
                    override val columnType: IColumnType<Any>
                        get() = (it.value.columnType as ArrayColumnType<Any>).type
                    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                        queryBuilder.append(it.key.takeUnless { it.isEmpty() } ?: "it")
                    }
                }
            },
            format = sources.format
        )
        queryBuilder.append("ARRAY(SELECT ")
        queryBuilder.append(mapper(fs))
        queryBuilder.append(" FROM unnest(")
        var first = true
        for(f in sources.fields) {
            if(first) first = false
            else queryBuilder.append(", ")
            queryBuilder.append(f.value)
        }
        queryBuilder.append(") x(")
        first = true
        for(s in sources.fields) {
            if(first) first = false
            else queryBuilder.append(", ")
            queryBuilder.append(s.key.takeUnless { it.isEmpty() } ?: "it")
        }
        queryBuilder.append(") WHERE ")
        queryBuilder.append(filter(fs))
        queryBuilder.append(")")
    }
}

internal class ContainsOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "@>")

internal infix fun<T> ExpressionWithColumnType<T>.contains(arry: List<T>) : Op<Boolean> = ContainsOp(this, QueryParameter(arry,
    ArrayColumnType(columnType)))

internal class InsensitiveLikeEscapeOp(expr1: Expression<*>, expr2: Expression<*>, like: Boolean, val escapeChar: Char?) : ComparisonOp(expr1, expr2, if (like) "ILIKE" else "NOT ILIKE") {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        super.toQueryBuilder(queryBuilder)
        if (escapeChar != null){
            with(queryBuilder){
                +" ESCAPE "
                +stringParam(escapeChar.toString())
            }
        }
    }
}

internal fun Expression<Boolean>.asOp(): Op<Boolean> {
    return when (this) {
        is Op -> this
        else -> this.eq(LiteralOp(BooleanColumnType(), true))
    }
}
