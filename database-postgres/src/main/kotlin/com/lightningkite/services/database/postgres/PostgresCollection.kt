package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryTrace
import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.database.*
import com.lightningkite.services.database.Table
import com.lightningkite.services.data.TextIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import java.sql.Connection.TRANSACTION_SERIALIZABLE

// Aggregates come back from SQL as NUMERIC with a fixed scale, and this abstraction hands callers a
// Double. A scale of 8 silently truncated every result far below what a Double can represent — the
// sample standard deviation of 0..9 came back as 3.02765035 instead of 3.0276503540974917. The scale
// now exceeds a Double's ~17 significant digits, so the only narrowing is the final toDouble().
private const val AGGREGATE_SCALE = 20

public class PostgresCollection<T : Any>(
    public val db: Database,
    override val name: String,
    override val serializer: KSerializer<T>,
    public val serializersModule: SerializersModule,
    override val context: SettingContext,
) : Table<T>, Namespaced {
    internal var format = DbMapLikeFormat(serializersModule)

    internal val table = SerialDescriptorTable(name, serializersModule, serializer.descriptor)

    /**
     * The Exposed table backing this collection. Exposed's schema tooling works on it directly —
     * see [migrationStatements].
     */
    public val exposedTables: List<org.jetbrains.exposed.v1.core.Table> get() = listOf(table)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Cancels the internal scope, releasing the prepare job and its resources. Called by [PostgresDatabase.disconnect]. */
    internal fun close() {
        scope.cancel()
    }

    internal suspend inline fun <T> t(noinline action: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            try {
                inTopLevelSuspendTransaction(db = db, transactionIsolation = TRANSACTION_READ_COMMITTED) {
                    action()
                }
            } catch (e: ExposedSQLException) {
                throw e.asUniqueViolationOrNull(name) ?: e
            }
        }

    // Per-collection default span attributes shared by every operation.
    private fun baseAttributes(operation: String): TelemetryAttributes = TelemetryAttributes {
        put(com.lightningkite.services.database.Database.TelemetryKeys.system, "postgresql")
        put(com.lightningkite.services.database.Database.TelemetryKeys.operation, operation)
        put(com.lightningkite.services.database.Database.TelemetryKeys.collection, name)
    }

    // Thin wrapper over telemetryTrace: opens the `<name>.<operation>` span (with the postgres-specific
    // default attributes plus any caller-supplied [extra]), records the RED metric inside it, and
    // hands the started span to [block] for dynamic per-result attributes.
    private suspend inline fun <R> traced(
        operation: String,
        noinline extraBlock: (TelemetryAttributes.Builder.() -> Unit)? = null,
        noinline block: suspend (TelemetryTrace) -> R,
    ): R {
        val attrs = if (extraBlock != null) TelemetryAttributes { putAll(baseAttributes(operation)); extraBlock() } else baseAttributes(operation)
        return telemetryTrace(operation, attributes = attrs, action = block)
    }

    private val textIndexColumns: List<Column<*>>? =
        serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields?.map { field ->
            val column = table.col[AnyOpticSerializer(serializer).fromString(field).colName]
            check(column != null && column.columnType is StringColumnType) {
                "@TextIndex field $field on ${serializer.descriptor.serialName} must be a String for Postgres"
            }
            column
        }

    // Claude: the query must repeat this expression exactly, or Postgres won't use the index.
    private fun JdbcTransaction.textSearchVector(columns: List<Column<*>>): String =
        columns.joinToString(" || ' ' || ", "to_tsvector('english'::regconfig, ", ")") { "coalesce(${identity(it)}, '')" }

    @OptIn(ExperimentalSerializationApi::class)
    internal val prepare = scope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        t {
            additiveSchemaStatements(exposedTables).forEach {
                exec(it)
            }
            if (textIndexColumns != null) {
                val indexName = db.identifierManager.quoteIfNecessary("${table.tableName}_text_search")
                exec("CREATE INDEX IF NOT EXISTS $indexName ON ${identity(table)} USING GIN ((${textSearchVector(textIndexColumns)}))")
            }
        }
    }

    /**
     * ORDER BY arguments for [orderBy]. Postgres defaults to NULLS LAST when ascending, but SortPart
     * requires nulls to sort below every non-null value, so null placement is always stated explicitly.
     */
    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalSerializationApi::class)
    private fun sortArguments(orderBy: List<SortPart<T>>): Array<Pair<Expression<*>, SortOrder>> =
        Array(orderBy.size) { index ->
            val part = orderBy[index]
            val column = table.col[part.field.colName]!!
            val expression: Expression<*> = if (
                part.field.coerceToOptic().serializer.descriptor.kind == PrimitiveKind.STRING &&
                serializationOverride(part.field.coerceToOptic().serializer.descriptor) == null
            ) {
                // TODO: Check database default collation to skip extra work
                if (part.ignoreCase) (column as Column<String>).lowerCase()
                else AsciiValue(column as Column<String>)
            } else column
            expression to if (part.ascending) SortOrder.ASC_NULLS_FIRST else SortOrder.DESC_NULLS_LAST
        }

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> = traced(
        operation = "find",
        extraBlock = {
            put(com.lightningkite.services.database.Database.TelemetryKeys.limit, limit.toLong())
            put(com.lightningkite.services.database.Database.TelemetryKeys.skip, skip.toLong())
        }
    ) { span ->
        
        val items = t {
            table
                .selectAll()
                .where { condition(condition, serializer, table, format).asOp() }
                .orderBy(*sortArguments(orderBy))
                .limit(limit).offset(skip.toLong())
                .toList()
//                .prep
                .map {
                    format.decode(serializer, it)
                }
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.resultCount, items.size.toLong()) })
        items.asFlow()
    }

    override suspend fun count(condition: Condition<T>): Int = traced(
        operation = "count"
    ) { span ->
        
        val result = t {
            table
                .selectAll().where { condition(condition, serializer, table, format).asOp() }
                .count().toInt()
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.count, result.toLong()) })
        result
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: Optic<T, Key>): Map<Key, Int> =
        traced(
            operation = "groupCount",
            extraBlock = { put(com.lightningkite.services.database.Database.TelemetryKeys.groupBy, groupBy.colName) }
        ) { span ->
            
            val result = t {
                @Suppress("UNCHECKED_CAST")
                val groupCol = table.col[groupBy.colName] as Column<Key>
                val count = Count(stringLiteral("*"))
                val groups = table.select(groupCol, count)
                    .where { condition(condition, serializer, table, format).asOp() }
                    .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[count].toInt() }
                if (groupBy.serializer.descriptor.isNullable) groups else groups.filterKeys { it != null }
            }
            span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.groups, result.size.toLong()) })
            result
        }

    override suspend fun aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: Optic<T, out Number?>,
    ): Double? = traced(
        operation = "aggregate",
        extraBlock = {
            put(com.lightningkite.services.database.Database.TelemetryKeys.aggregate, aggregate.toString())
            put(com.lightningkite.services.database.Database.TelemetryKeys.property, property.colName)
        }
    ) { span ->
        
        t {
            @Suppress("UNCHECKED_CAST")
            val valueCol = table.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, AGGREGATE_SCALE)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, AGGREGATE_SCALE)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, AGGREGATE_SCALE)
            }
            table.select(agg)
                .where { condition(condition, serializer, table, format).asOp() }
                .firstOrNull()?.get(agg)?.toDouble()
        }
    }

    override suspend fun <Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: Optic<T, Key>,
        property: Optic<T, out Number?>,
    ): Map<Key, Double?> = traced(
        operation = "groupAggregate",
        extraBlock = {
            put(com.lightningkite.services.database.Database.TelemetryKeys.aggregate, aggregate.toString())
            put(com.lightningkite.services.database.Database.TelemetryKeys.groupBy, groupBy.colName)
            put(com.lightningkite.services.database.Database.TelemetryKeys.property, property.colName)
        }
    ) { span ->
        
        val result = t {
            @Suppress("UNCHECKED_CAST")
            val groupCol = table.col[groupBy.colName] as Column<Key>

            @Suppress("UNCHECKED_CAST")
            val valueCol = table.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, AGGREGATE_SCALE)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, AGGREGATE_SCALE)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, AGGREGATE_SCALE)
            }
            val groups = table.select(groupCol, agg)
                .where { condition(condition, serializer, table, format).asOp() }
                .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[agg]?.toDouble() }
            if (groupBy.serializer.descriptor.isNullable) groups else groups.filterKeys { it != null }
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.groups, result.size.toLong()) })
        result
    }

    override suspend fun insert(models: Iterable<T>): List<T> = traced(
        operation = "insert"
    ) { span ->
        
        val modelsList = models.toList()
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.insertCount, modelsList.size.toLong()) })
        t {
            table.batchInsert(modelsList) {
                format.encode(serializer, it, this)
            }
        }
        modelsList
    }

    override suspend fun replaceOne(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> {
        return updateOne(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>,
    ): Boolean {
        return updateOneIgnoringResult(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun upsertOne(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): EntryChange<T> {
        return inTopLevelSuspendTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                EntryChange(null, insert(listOf(model)).first())
            } else if (modification.isNothing) {
                EntryChange(existing, existing)
            } else
                updateOne(condition, modification)
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        return inTopLevelSuspendTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                insert(listOf(model))
                false
            } else if (modification.isNothing) {
                true
            } else
                updateOneIgnoringResult(condition, modification, listOf())
        }
    }

    override suspend fun updateOne(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): EntryChange<T> = traced(
        operation = "updateOne"
    ) { span ->
        val result = t {
            val old = table.updateReturningOld(
                where = { condition(condition, serializer, table, format).asOp() },
                orderBy = sortArguments(orderBy).toList(),
                limit = 1,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
            old.map { format.decode(serializer, it) }.firstOrNull()?.let {
                EntryChange(it, modification(it))
            } ?: EntryChange()
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.updated, if (result.old != null) 1L else 0L) })
        result
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): Boolean = traced(
        operation = "updateOneIgnoringResult"
    ) { span ->
        // Routed through the same locked, ordered single-statement UPDATE as updateOne (rather than a
        // plain Exposed `update`) so the claimed row is chosen and locked atomically; the returned old
        // rows are only used to count matches, not decoded.
        val count = t {
            table.updateReturningOld(
                where = { condition(condition, serializer, table, format).asOp() },
                orderBy = sortArguments(orderBy).toList(),
                limit = 1,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            ).count()
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.updated, count.toLong()) })
        count > 0
    }

    override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> =
        traced(
            operation = "updateMany"
        ) { span ->
            val result = t {
                val old = table.updateReturningOld(
                    where = { condition(condition, serializer, table, format).asOp() },
                    body = {
                        it.modification(modification, serializer, table, format)
                    }
                )
                CollectionChanges(old.map { format.decode(serializer, it) }.map {
                    EntryChange(it, modification(it))
                })
            }
            span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.updated, result.changes.size.toLong()) })
            result
        }

    override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int = traced(
        operation = "updateManyIgnoringResult"
    ) { span ->
        val count = t {
            table.update(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.updated, count.toLong()) })
        count
    }

    /**
     * Picks its row with `FOR UPDATE SKIP LOCKED`, so concurrent callers claim *different* rows
     * instead of contending for the same one — which is what makes this usable as a work queue.
     *
     * The cost of that choice: a row locked by another in-flight transaction is passed over rather
     * than waited for. When several rows match, another is taken and nothing is lost. When the
     * condition targets one specific row and that row is momentarily locked, this reports no match
     * even though the row exists. [updateOne] blocks instead, so the two are deliberately asymmetric.
     */
    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? = traced(
        operation = "deleteOne"
    ) { span ->
        val result = t {
            table.deleteReturningOne(
                where = { condition(condition, serializer, table, format).asOp() },
                orderBy = sortArguments(orderBy).toList(),
            ).firstOrNull()?.let { format.decode(serializer, it) }
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.deleted, if (result != null) 1L else 0L) })
        result
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean = traced(
        operation = "deleteOneIgnoringOld"
    ) { span ->
        val count = t {
            table.deleteReturningOne(
                where = { condition(condition, serializer, table, format).asOp() },
                orderBy = sortArguments(orderBy).toList(),
            ).count()
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.deleted, count.toLong()) })
        count > 0
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> = traced(
        operation = "deleteMany"
    ) { span ->
        
        val result = t {
            table.deleteReturning(
                where = { condition(condition, serializer, table, format).asOp() }
            ).map { format.decode(serializer, it) }
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.deleted, result.size.toLong()) })
        result
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int = traced(
        operation = "deleteManyIgnoringOld"
    ) { span ->
        
        val count = t {
            table.deleteWhere(
                op = { condition(condition, serializer, table, format).asOp() }
            )
        }
        span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.deleted, count.toLong()) })
        count
    }

    override suspend fun findByTextIndex(
        query: String,
        condition: Condition<T>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> {
        val columns = checkNotNull(textIndexColumns) { "findByTextIndex needs a @TextIndex on ${serializer.descriptor.serialName}" }
        val textQuery = TextQuery.fromString(query)
        if (limit <= 0 || (textQuery.exact.isEmpty() && textQuery.fuzzy.isEmpty())) return emptyFlow()
        // Claude: quoting every term stops websearch_to_tsquery from reading a plain "or" as an operator.
        val search = (textQuery.fuzzy + textQuery.exact).map { "\"$it\"" }
            .plus(textQuery.reject.map { "-\"$it\"" })
            .joinToString(" ")
        return flow {
            val results = traced(
                operation = "findByTextIndex",
                extraBlock = {
                    put(com.lightningkite.services.database.Database.TelemetryKeys.limit, limit.toLong())
                    put(com.lightningkite.services.database.Database.TelemetryKeys.skip, skip.toLong())
                }
            ) { span ->
                val results = t {
                    val vector = textSearchVector(columns)
                    val rank = object : ExpressionWithColumnType<Float>() {
                        override val columnType: IColumnType<Float> = FloatColumnType()
                        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
                            append("ts_rank($vector, ")
                            appendTsQuery(search)
                            // Claude: normalization 1 divides by 1 + log(length), so shorter text ranks higher.
                            append(", 1)")
                        }
                    }
                    val matches = object : Op<Boolean>() {
                        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
                            append("$vector @@ ")
                            appendTsQuery(search)
                        }
                    }
                    table
                        .select(table.columns + rank)
                        .where { matches and condition(condition, serializer, table, format).asOp() }
                        .orderBy(rank to SortOrder.DESC)
                        .limit(limit).offset(skip.toLong())
                        .toList()
                        .map { ScoredResult(format.decode(serializer, it), it[rank]) }
                }
                span.enrich(TelemetryAttributes { put(com.lightningkite.services.database.Database.TelemetryKeys.resultCount, results.size.toLong()) })
                results
            }
            results.forEach { emit(it) }
        }
    }
}

private fun QueryBuilder.appendTsQuery(search: String) {
    append("websearch_to_tsquery('english'::regconfig, ")
    registerArgument(TextColumnType(), search)
    append(")")
}
