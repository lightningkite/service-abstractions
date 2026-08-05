package com.lightningkite.services.database.postgres

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.statements.api.ResultApi
import org.jetbrains.exposed.v1.jdbc.statements.BlockingExecutable
import org.jetbrains.exposed.v1.jdbc.statements.api.JdbcPreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcResult
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Since Exposed 1.x, describing a statement's SQL ([Statement]) and executing it ([BlockingExecutable]) are
 * separate concerns. This class plays both roles at once (the same trick Exposed itself uses for
 * `JdbcTransaction.exec(sql: String)`): it is the [Statement] passed to [TransactionManager]/[JdbcTransaction],
 * and its own [BlockingExecutable], so callers can keep treating it as a single self-executing, iterable query.
 */
internal abstract class ReturningStatement(type: StatementType, targets: List<Table>) :
    Statement<ResultApi>(type, targets), BlockingExecutable<ResultApi, ReturningStatement>, Iterable<ResultRow> {
    protected val transaction get() = TransactionManager.current()

    abstract val set: FieldSet

    override val statement: ReturningStatement get() = this

    override fun JdbcPreparedStatementApi.executeInternal(transaction: JdbcTransaction): JdbcResult =
        executeQuery()

    private var iterator: Iterator<ResultRow>? = null

    fun exec() {
        require(iterator == null) { "already executed" }

        // transaction.exec(BlockingExecutable) always hands back the same JdbcResult that
        // executeInternal produced above; the cast mirrors Exposed's own RowApi.origin helper.
        val resultIterator = ResultIterator(transaction.exec(this)!! as JdbcResult)
        iterator = if (transaction.db.supportsMultipleResultSets) resultIterator
        else Iterable { resultIterator }.toList().iterator()
    }

    override fun iterator(): Iterator<ResultRow> =
        iterator ?: throw IllegalStateException("must call exec() first")

    protected inner class ResultIterator(val rs: JdbcResult) : Iterator<ResultRow> {
        private var hasNext: Boolean? = null

        private val fieldsIndex = set.realFields.toSet().mapIndexed { index, expression -> expression to index }.toMap()

        override operator fun next(): ResultRow {
            if (hasNext == null) hasNext()
            if (hasNext == false) throw NoSuchElementException()
            hasNext = null
            return ResultRow.create(rs, fieldsIndex)
        }

        override fun hasNext(): Boolean {
            if (hasNext == null) hasNext = rs.next()
            if (hasNext == false) rs.close()
            return hasNext!!
        }
    }
}

internal class UpdateReturningOldStatement(
    private val table: Table,
    private val where: Op<Boolean>? = null,
    private val orderBy: List<Pair<Expression<*>, SortOrder>> = emptyList(),
    private val limit: Int? = null,
) : ReturningStatement(StatementType.UPDATE, listOf(table)) {
    val readAlias = table.alias("old")
    override val set: FieldSet = readAlias
//    override val set: FieldSet = readAlias.slice(readAlias.columns.map { it.alias("old__" + it.name) } + writeAlias.columns.map { it.alias("new__" + it.name) })

    private val firstDataSet: List<Pair<Column<*>, Any?>>
        get() = values.toList()

    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String =
        with(QueryBuilder(true)) {
            +"UPDATE "
            table.describe(transaction, this)

            firstDataSet.appendTo(this, prefix = " SET ") { (col, value) ->
                append("${transaction.identity(col)}=")
                registerArgument(col, value)
            }

            // The row to update is picked here, inside the locked subquery, rather than by the outer
            // WHERE (which only matches back by primary key). That keeps ORDER BY/LIMIT meaningful and
            // ensures the row is locked - and re-checked against [where] - before its old values are
            // read, so a concurrent winner can't leave a stale `old` for a loser to observe.
            +" FROM (SELECT * FROM "
            table.describe(transaction, this)
            where?.let {
                +" WHERE "
                +it
            }
            if (orderBy.isNotEmpty()) {
                orderBy.appendTo(this, prefix = " ORDER BY ") { (expression, sortOrder) ->
                    append(expression, " ", sortOrder.code)
                }
            }
            limit?.let {
                +" LIMIT "
                +it.toString()
            }
            +" FOR UPDATE) old"

            // Primary-key match-back is compound-key-safe: [Table.primaryKey] lists every column that
            // makes up `_id`, one or many.
            +" WHERE "
            +AndOp(table.primaryKey!!.columns.map { EqOp(it, readAlias[it]) })

            +" RETURNING "
            var first = true
            readAlias.columns.forEach {
                if (first) first = false
                else +","
                +it
                +" AS "
                +transaction.identity(it)
            }

            toString()
        }

    override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> =
        QueryBuilder(true).run {
            for ((key, value) in values) {
                registerArgument(key, value)
            }
            where?.toQueryBuilder(this)
            listOf(args)
        }

    // region UpdateBuilder
    private val values: MutableMap<Column<*>, Any?> = LinkedHashMap()

    operator fun <S> set(column: Column<S>, value: S) {
        when {
            values.containsKey(column) -> error("$column is already initialized")
            !column.columnType.nullable && value == null -> error("Trying to set null to not nullable column $column")
            else -> values[column] = value
        }
    }

    @JvmName("setWithEntityIdExpression")
    operator fun <S : Any, ID : EntityID<S>, E : Expression<S>> set(
        column: Column<ID>,
        value: E,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    @JvmName("setWithEntityIdValue")
    operator fun <S : Comparable<S>, ID : EntityID<S>, E : S?> set(
        column: Column<ID>,
        value: E,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    operator fun <T, S : T, E : Expression<S>> set(column: Column<T>, value: E) =
        update(column, value)

    operator fun <S> set(column: CompositeColumn<S>, value: S) {
        @Suppress("UNCHECKED_CAST")
        column.getRealColumnsWithValues(value).forEach { (realColumn, itsValue) ->
            set(
                realColumn as Column<Any?>,
                itsValue
            )
        }
    }

    fun <T, S : T?> update(column: Column<T>, value: Expression<S>) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    fun <T, S : T?> update(
        column: Column<T>,
        value: () -> Expression<S>,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value()
    }
    // endregion
}

internal fun <T : Table> T.updateReturningOld(
    where: () -> Op<Boolean>,
    orderBy: List<Pair<Expression<*>, SortOrder>> = emptyList(),
    limit: Int? = null,
    body: T.(UpdateReturningOldStatement) -> Unit,
): UpdateReturningOldStatement = UpdateReturningOldStatement(
    this,
    where(),
    orderBy,
    limit,
).apply {
    this@updateReturningOld.body(this)
    exec()
}

/**
 * Deletes the single row [where] and [orderBy] pick out, returning its columns.
 *
 * Postgres allows neither ORDER BY nor LIMIT on a bare DELETE, and matching back by primary key (as
 * [UpdateReturningOldStatement] does) is awkward when the key is compound. Instead the target row is
 * chosen by an ordered, locked subquery over `ctid` - Postgres's physical row identifier, always a
 * single column regardless of the table's primary key - and the outer DELETE claims exactly that row.
 * `SKIP LOCKED` gives correct work-claim semantics: a caller racing another for the same row moves on
 * to the next candidate instead of blocking on it.
 */
internal class DeleteReturningOneStatement(
    private val table: Table,
    private val where: Op<Boolean>,
    private val orderBy: List<Pair<Expression<*>, SortOrder>> = emptyList(),
) : ReturningStatement(StatementType.DELETE, listOf(table)) {
    override val set: FieldSet = table

    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String =
        with(QueryBuilder(true)) {
            +"DELETE FROM "
            table.describe(transaction, this)

            +" WHERE ctid = (SELECT ctid FROM "
            table.describe(transaction, this)
            +" WHERE "
            +where
            if (orderBy.isNotEmpty()) {
                orderBy.appendTo(this, prefix = " ORDER BY ") { (expression, sortOrder) ->
                    append(expression, " ", sortOrder.code)
                }
            }
            +" LIMIT 1 FOR UPDATE SKIP LOCKED)"

            +" RETURNING "
            var first = true
            table.columns.forEach {
                if (first) first = false
                else +","
                +it
                +" AS "
                +transaction.identity(it)
            }

            toString()
        }

    override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> =
        QueryBuilder(true).run {
            where.toQueryBuilder(this)
            listOf(args)
        }
}

internal fun <T : Table> T.deleteReturningOne(
    where: () -> Op<Boolean>,
    orderBy: List<Pair<Expression<*>, SortOrder>> = emptyList(),
): DeleteReturningOneStatement = DeleteReturningOneStatement(
    this,
    where(),
    orderBy,
).apply { exec() }