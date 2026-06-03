package com.lightningkite.services.database.sql

import com.lightningkite.services.database.*
import com.lightningkite.services.database.mapformat.ChildRow
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ops.SingleValueInListOp
import org.jetbrains.exposed.sql.SchemaUtils.statementsRequiredToActualizeScheme
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import java.sql.Connection.TRANSACTION_SERIALIZABLE

public class SqlCollection<T : Any>(
    public val db: Database,
    public val name: String,
    override val serializer: KSerializer<T>,
    public val serializersModule: SerializersModule,
    private val tracer: Tracer?,
) : com.lightningkite.services.database.Table<T> {

    private val format = SqlMapFormat(serializersModule)
    internal val schema = SqlSchema(name, serializersModule, serializer.descriptor)

    private suspend inline fun <T> t(noinline action: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db, transactionIsolation = TRANSACTION_READ_COMMITTED, statement = {
            action()
        })

    private suspend inline fun <T> tSerial(noinline action: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db, transactionIsolation = TRANSACTION_SERIALIZABLE, statement = {
            action()
        })

    private suspend inline fun <R> traced(
        operation: String,
        crossinline attributes: SpanBuilder.() -> Unit = {},
        crossinline block: suspend (Span?) -> R,
    ): R {
        return if (tracer != null) {
            val span = tracer.spanBuilder("sql.$operation")
                .setAttribute("db.system", "sql")
                .setAttribute("db.operation", operation)
                .setAttribute("db.collection", name)
                .apply { attributes() }
                .startSpan()
            try {
                withContext(span.asContextElement()) {
                    val result = block(span)
                    span.setStatus(StatusCode.OK)
                    result
                }
            } catch (t: CancellationException) {
                span.addEvent("Cancelled")
                throw t
            } catch (t: Throwable) {
                span.setStatus(StatusCode.ERROR)
                throw t
            } finally {
                span.end()
            }
        } else {
            block(null)
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalSerializationApi::class)
    private val prepare = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        t {
            val allTables = listOf(schema.mainTable) + schema.childTables.values.map { it.table }
            allTables.forEach { table ->
                statementsRequiredToActualizeScheme(table).forEach {
                    try { exec(it) } catch (_: Exception) { /* index/constraint may already exist */ }
                }
            }
        }
    }

    // ====================== Helpers ======================

    private fun getId(value: T): Any? {
        val encoded = format.encode(serializer, value)
        return schema.mainTable.extractId(encoded.mainRecord)
    }

    /**
     * Read child rows for a set of owner IDs, grouped by child table path and owner ID.
     */
    private fun Transaction.fetchChildRows(
        ids: List<Any?>,
    ): Map<String, Map<Any?, List<ChildRow>>> {
        if (ids.isEmpty() || schema.childTables.isEmpty()) return emptyMap()

        return schema.childTables.mapValues { (_, childDef) ->
            val childTable = childDef.table
            @Suppress("UNCHECKED_CAST")
            val ownerCol = childTable.ownerIdColumns[0] as Column<Any?>

            val query = childTable.selectAll().where { ownerCol inList ids }
            // Sets have no idx column; only order rows when ordering is meaningful.
            childTable.idxColumn?.let { query.orderBy(it to SortOrder.ASC) }
            query
                .toList()
                .groupBy { it[ownerCol] }
                .mapValues { (_, rows) ->
                    rows.map { row ->
                        val idx = childTable.idxColumn?.let { row[it] }
                        val values = childTable.elementColumns.mapValues { (_, col) ->
                            try { row[col] } catch (_: Exception) { null }
                        }
                        val keys = if (childDef.isMap) {
                            childTable.keyColumns.mapValues { (_, col) ->
                                try { row[col] } catch (_: Exception) { null }
                            }
                        } else null
                        ChildRow(index = idx, key = keys, values = values)
                    }
                }
        }
    }

    /**
     * Decode a main table row + its child rows into a T.
     */
    private fun decodeRow(
        mainRow: ResultRow,
        allChildRows: Map<String, Map<Any?, List<ChildRow>>>,
    ): T {
        val id = schema.mainTable.extractId(mainRow)
        val children = allChildRows.mapValues { (_, byOwner) ->
            byOwner[id] ?: emptyList()
        }
        return format.decode(serializer, mainRow, children)
    }

    /**
     * Insert child rows for a single entity.
     */
    private fun Transaction.insertChildren(ownerId: Any?, writeResult: com.lightningkite.services.database.mapformat.WriteResult) {
        for ((childPath, childRows) in writeResult.children) {
            val childDef = schema.childTables[childPath] ?: continue
            val childTable = childDef.table
            if (childRows.isEmpty()) continue

            childTable.batchInsert(childRows) { childRow ->
                @Suppress("UNCHECKED_CAST")
                this[childTable.ownerIdColumns[0] as Column<Any?>] = ownerId
                childTable.idxColumn?.let { this[it] = childRow.index ?: 0 }
                if (childDef.isMap && childRow.key != null) {
                    for ((keyName, keyValue) in childRow.key) {
                        val col = childTable.keyColumns[keyName] ?: continue
                        @Suppress("UNCHECKED_CAST")
                        this[col as Column<Any?>] = keyValue
                    }
                }
                for ((valName, valValue) in childRow.values) {
                    val col = childTable.elementColumns[valName] ?: continue
                    @Suppress("UNCHECKED_CAST")
                    this[col as Column<Any?>] = valValue
                }
            }
        }
    }

    /**
     * Delete all child rows for an owner ID.
     */
    private fun Transaction.deleteChildren(ownerId: Any?) {
        for ((_, childDef) in schema.childTables) {
            val childTable = childDef.table
            @Suppress("UNCHECKED_CAST")
            val ownerCol = childTable.ownerIdColumns[0] as ExpressionWithColumnType<Any?>
            childTable.deleteWhere { EqOp(ownerCol, sqlLiteralOfSomeKind(ownerCol.columnType, ownerId)) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun idEq(id: Any?): Op<Boolean> {
        val idCols = schema.mainTable.idColumns
        return if (idCols.size == 1) {
            val idCol = idCols[0] as ExpressionWithColumnType<Any?>
            EqOp(idCol, sqlLiteralOfSomeKind(idCol.columnType, id))
        } else {
            // Compound id: id is a Map<String, Any?>
            val idMap = id as Map<String, Any?>
            AndOp(idCols.map { col ->
                val c = col as ExpressionWithColumnType<Any?>
                EqOp(c, sqlLiteralOfSomeKind(c.columnType, idMap[col.name]))
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun idInList(ids: List<Any?>): Op<Boolean> {
        val idCols = schema.mainTable.idColumns
        return if (idCols.size == 1) {
            val idCol = idCols[0] as ExpressionWithColumnType<Any?>
            SingleValueInListOp(idCol, ids)
        } else {
            // Compound id: each id is a Map<String, Any?>
            OrOp(ids.map { idEq(it) })
        }
    }

    /**
     * Write (replace) an entire entity: update main row + re-write all children.
     */
    private fun Transaction.writeEntity(oldId: Any?, newValue: T) {
        val writeResult = format.encode(serializer, newValue)
        // Update main table
        schema.mainTable.update(where = {
            idEq(oldId)
        }) { builder ->
            for ((key, value) in writeResult.mainRecord) {
                val col = schema.mainTable.col[key] ?: continue
                @Suppress("UNCHECKED_CAST")
                builder[col as Column<Any?>] = value
            }
        }
        // Re-write children
        deleteChildren(oldId)
        insertChildren(oldId, writeResult)
    }

    // ====================== Query Operations ======================

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> = traced(
        operation = "find",
        attributes = {
            setAttribute("db.limit", limit.toLong())
            setAttribute("db.skip", skip.toLong())
        }
    ) { span ->
        prepare.await()
        val items = t {
            val ctx = SqlConditionContext(schema, format)
            val condExpr = SqlExpressionBuilder.run {
                condition(condition, serializer, schema, format, ctx)
            }

            // Step 1: Get matching main rows with limit/offset
            val mainRows = schema.mainTable
                .selectAll()
                .where { condExpr.asOp() }
                .orderBy(*orderBy.map {
                    @Suppress("UNCHECKED_CAST")
                    val col = if (it.field.serializerAny.descriptor.kind == PrimitiveKind.STRING && serializationOverride(it.field.serializerAny.descriptor) == null) {
                        if (it.ignoreCase) (schema.mainTable.col[it.field.colName]!! as Column<String>).lowerCase()
                        else schema.mainTable.col[it.field.colName]!!
                    } else schema.mainTable.col[it.field.colName]!!
                    col to if (it.ascending) SortOrder.ASC else SortOrder.DESC
                }.toTypedArray())
                .limit(limit).offset(skip.toLong())
                .toList()

            if (mainRows.isEmpty()) return@t emptyList()

            // Step 2: Batch fetch child rows
            @Suppress("UNCHECKED_CAST")
            val ids = mainRows.map { schema.mainTable.extractId(it) }
            val allChildRows = fetchChildRows(ids)

            // Step 3: Decode
            var result = mainRows.map { decodeRow(it, allChildRows) }

            // Step 4: Post-filter if condition had unsupported parts
            if (!ctx.isExact) {
                result = result.filter { condition(it) }
            }

            result
        }
        span?.setAttribute("db.result_count", items.size.toLong())
        items.asFlow()
    }

    override suspend fun count(condition: Condition<T>): Int = traced(
        operation = "count"
    ) { span ->
        prepare.await()
        val result = t {
            val ctx = SqlConditionContext(schema, format)
            val condExpr = SqlExpressionBuilder.run {
                condition(condition, serializer, schema, format, ctx)
            }

            if (ctx.isExact) {
                schema.mainTable.selectAll()
                    .where { condExpr.asOp() }
                    .count().toInt()
            } else {
                // Table scan fallback: load all candidates and filter
                @Suppress("UNCHECKED_CAST")
                val mainRows = schema.mainTable.selectAll()
                    .where { condExpr.asOp() }
                    .toList()
                val ids = mainRows.map { schema.mainTable.extractId(it) }
                val allChildRows = fetchChildRows(ids)
                mainRows.map { decodeRow(it, allChildRows) }.count { condition(it) }
            }
        }
        span?.setAttribute("db.count", result.toLong())
        result
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: DataClassPath<T, Key>): Map<Key, Int> = traced(
        operation = "groupCount",
        attributes = { setAttribute("db.groupBy", groupBy.colName) }
    ) { span ->
        prepare.await()
        val result = t {
            val ctx = SqlConditionContext(schema, format)
            val condExpr = SqlExpressionBuilder.run {
                condition(condition, serializer, schema, format, ctx)
            }
            @Suppress("UNCHECKED_CAST")
            val groupCol = schema.mainTable.col[groupBy.colName] as Column<Key>
            val count = Count(stringLiteral("*"))
            schema.mainTable.select(groupCol, count)
                .where { condExpr.asOp() }
                .groupBy(schema.mainTable.col[groupBy.colName]!!)
                .associate { it[groupCol] to it[count].toInt() }
        }
        span?.setAttribute("db.groups", result.size.toLong())
        result
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: DataClassPath<T, N>,
    ): Double? = traced(
        operation = "aggregate",
        attributes = {
            setAttribute("db.aggregate", aggregate.toString())
            setAttribute("db.property", property.colName)
        }
    ) { span ->
        prepare.await()
        t {
            val ctx = SqlConditionContext(schema, format)
            val condExpr = SqlExpressionBuilder.run {
                condition(condition, serializer, schema, format, ctx)
            }
            @Suppress("UNCHECKED_CAST")
            val valueCol = schema.mainTable.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            schema.mainTable.select(agg)
                .where { condExpr.asOp() }
                .firstOrNull()?.get(agg)?.toDouble()
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> = traced(
        operation = "groupAggregate",
        attributes = {
            setAttribute("db.aggregate", aggregate.toString())
            setAttribute("db.groupBy", groupBy.colName)
            setAttribute("db.property", property.colName)
        }
    ) { span ->
        prepare.await()
        val result = t {
            val ctx = SqlConditionContext(schema, format)
            val condExpr = SqlExpressionBuilder.run {
                condition(condition, serializer, schema, format, ctx)
            }
            @Suppress("UNCHECKED_CAST")
            val groupCol = schema.mainTable.col[groupBy.colName] as Column<Key>
            @Suppress("UNCHECKED_CAST")
            val valueCol = schema.mainTable.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            schema.mainTable.select(groupCol, agg)
                .where { condExpr.asOp() }
                .groupBy(schema.mainTable.col[groupBy.colName]!!)
                .associate { it[groupCol] to it[agg]?.toDouble() }
        }
        span?.setAttribute("db.groups", result.size.toLong())
        result
    }

    // ====================== Insert ======================

    override suspend fun insert(models: Iterable<T>): List<T> = traced(
        operation = "insert"
    ) { span ->
        prepare.await()
        val modelsList = models.toList()
        span?.setAttribute("db.insert_count", modelsList.size.toLong())
        try {
            t {
                for (model in modelsList) {
                    val writeResult = format.encode(serializer, model)
                    val ownerId = writeResult.mainRecord["_id"]

                    // Insert main row
                    schema.mainTable.insert { builder ->
                        for ((key, value) in writeResult.mainRecord) {
                            val col = schema.mainTable.col[key] ?: continue
                            @Suppress("UNCHECKED_CAST")
                            builder[col as Column<Any?>] = value
                        }
                    }

                    // Insert child rows
                    insertChildren(ownerId, writeResult)
                }
            }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            val sqlState = (e.cause as? java.sql.SQLException)?.sqlState
            if (sqlState == "23505" || sqlState == "23000") {
                throw UniqueViolationException(e, table = name)
            }
            throw e
        }
        modelsList
    }

    // ====================== Update ======================

    override suspend fun replaceOne(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> {
        return updateOne(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun replaceOneIgnoringResult(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): Boolean {
        return updateOneIgnoringResult(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun upsertOne(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): EntryChange<T> {
        prepare.await()
        return tSerial {
            val existing = findOneInTransaction(condition)
            if (existing == null) {
                val writeResult = format.encode(serializer, model)
                val ownerId = writeResult.mainRecord["_id"]
                schema.mainTable.insert { builder ->
                    for ((key, value) in writeResult.mainRecord) {
                        val col = schema.mainTable.col[key] ?: continue
                        @Suppress("UNCHECKED_CAST")
                        builder[col as Column<Any?>] = value
                    }
                }
                insertChildren(ownerId, writeResult)
                EntryChange(null, model)
            } else {
                val old = existing
                val new = modification(old)
                writeEntity(getId(old), new)
                EntryChange(old, new)
            }
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        prepare.await()
        return tSerial {
            val existing = findOneInTransaction(condition)
            if (existing == null) {
                val writeResult = format.encode(serializer, model)
                val ownerId = writeResult.mainRecord["_id"]
                schema.mainTable.insert { builder ->
                    for ((key, value) in writeResult.mainRecord) {
                        val col = schema.mainTable.col[key] ?: continue
                        @Suppress("UNCHECKED_CAST")
                        builder[col as Column<Any?>] = value
                    }
                }
                insertChildren(ownerId, writeResult)
                false
            } else {
                val new = modification(existing)
                writeEntity(getId(existing), new)
                true
            }
        }
    }

    override suspend fun updateOne(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): EntryChange<T> = traced(
        operation = "updateOne"
    ) { span ->
        prepare.await()
        val result = t {
            // Find the matching row
            val old = findOneInTransaction(condition, orderBy) ?: return@t EntryChange<T>()
            val oldId = getId(old)

            // Apply modification in memory
            val new = modification(old)

            // Write back
            writeEntity(oldId, new)

            EntryChange(old, new)
        }
        span?.setAttribute("db.updated", if (result.old != null) 1L else 0L)
        result
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): Boolean = traced(
        operation = "updateOneIgnoringResult"
    ) { span ->
        prepare.await()
        val updated = t {
            if (modification.isScalarOnly(schema) && schema.childTables.isEmpty()) {
                // Efficient: single SQL UPDATE
                val ctx = SqlConditionContext(schema, format)
                val condExpr = SqlExpressionBuilder.run {
                    condition(condition, serializer, schema, format, ctx)
                }
                schema.mainTable.update(
                    where = { condExpr.asOp() },
                    limit = null,
                ) { it.modification(modification, serializer, schema, format) } > 0
            } else {
                // Fallback: read-modify-write
                val old = findOneInTransaction(condition, orderBy) ?: return@t false
                val new = modification(old)
                writeEntity(getId(old), new)
                true
            }
        }
        span?.setAttribute("db.updated", if (updated) 1L else 0L)
        updated
    }

    override suspend fun updateMany(
        condition: Condition<T>,
        modification: Modification<T>,
    ): CollectionChanges<T> = traced(
        operation = "updateMany"
    ) { span ->
        prepare.await()
        val result = t {
            val olds = findManyInTransaction(condition)
            val changes = olds.map { old ->
                val new = modification(old)
                writeEntity(getId(old), new)
                EntryChange(old, new)
            }
            CollectionChanges(changes)
        }
        span?.setAttribute("db.updated", result.changes.size.toLong())
        result
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
    ): Int = traced(
        operation = "updateManyIgnoringResult"
    ) { span ->
        prepare.await()
        val count = t {
            if (modification.isScalarOnly(schema) && schema.childTables.isEmpty()) {
                val ctx = SqlConditionContext(schema, format)
                val condExpr = SqlExpressionBuilder.run {
                    condition(condition, serializer, schema, format, ctx)
                }
                schema.mainTable.update(
                    where = { condExpr.asOp() },
                    limit = null,
                ) { it.modification(modification, serializer, schema, format) }
            } else {
                val olds = findManyInTransaction(condition)
                for (old in olds) {
                    val new = modification(old)
                    writeEntity(getId(old), new)
                }
                olds.size
            }
        }
        span?.setAttribute("db.updated", count.toLong())
        count
    }

    // ====================== Delete ======================

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? = traced(
        operation = "deleteOne"
    ) { span ->
        prepare.await()
        val result = t {
            val old = findOneInTransaction(condition, orderBy) ?: return@t null
            val oldId = getId(old)
            // Delete children first (in case no CASCADE), then main row
            deleteChildren(oldId)
            schema.mainTable.deleteWhere(limit = 1) {
                idEq(oldId)
            }
            old
        }
        span?.setAttribute("db.deleted", if (result != null) 1L else 0L)
        result
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean = traced(
        operation = "deleteOneIgnoringOld"
    ) { span ->
        prepare.await()
        val deleted = t {
            val old = findOneInTransaction(condition, orderBy) ?: return@t false
            val oldId = getId(old)
            deleteChildren(oldId)
            schema.mainTable.deleteWhere(limit = 1) {
                idEq(oldId)
            }
            true
        }
        span?.setAttribute("db.deleted", if (deleted) 1L else 0L)
        deleted
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> = traced(
        operation = "deleteMany"
    ) { span ->
        prepare.await()
        val result = t {
            val olds = findManyInTransaction(condition)
            for (old in olds) {
                val oldId = getId(old)
                deleteChildren(oldId)
            }
            val ids = olds.map { getId(it) }
            if (ids.isNotEmpty()) {
                schema.mainTable.deleteWhere {
                    idInList(ids)
                }
            }
            olds
        }
        span?.setAttribute("db.deleted", result.size.toLong())
        result
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int = traced(
        operation = "deleteManyIgnoringOld"
    ) { span ->
        prepare.await()
        val count = t {
            // Delete children for matching rows first
            val ctx = SqlConditionContext(schema, format)
            val condExpr = SqlExpressionBuilder.run {
                condition(condition, serializer, schema, format, ctx)
            }

            val ids = schema.mainTable.selectAll()
                .where { condExpr.asOp() }
                .map { schema.mainTable.extractId(it) }

            if (ids.isNotEmpty()) {
                for ((_, childDef) in schema.childTables) {
                    @Suppress("UNCHECKED_CAST")
                    val ownerCol = childDef.table.ownerIdColumns[0] as ExpressionWithColumnType<Any?>
                    childDef.table.deleteWhere {
                        SingleValueInListOp(ownerCol, ids)
                    }
                }
                schema.mainTable.deleteWhere {
                    idInList(ids)
                }
            } else 0
        }
        span?.setAttribute("db.deleted", count.toLong())
        count
    }

    // ====================== Vector Search (Not Supported) ======================

    override suspend fun findSimilar(
        vectorField: DataClassPath<T, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> = throw UnsupportedOperationException(
        "Vector search is not supported by the generic SQL driver"
    )

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<T, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> = throw UnsupportedOperationException(
        "Sparse vector search is not supported by the generic SQL driver"
    )

    // ====================== Transaction Helpers ======================

    private fun Transaction.findOneInTransaction(
        condition: Condition<T>,
        orderBy: List<SortPart<T>> = listOf(),
    ): T? {
        val ctx = SqlConditionContext(schema, format)
        val condExpr = SqlExpressionBuilder.run {
            condition(condition, serializer, schema, format, ctx)
        }

        val mainRow = schema.mainTable.selectAll()
            .where { condExpr.asOp() }
            .apply {
                if (orderBy.isNotEmpty()) {
                    orderBy(*orderBy.map {
                        schema.mainTable.col[it.field.colName]!! to
                                if (it.ascending) SortOrder.ASC else SortOrder.DESC
                    }.toTypedArray())
                }
            }
            .limit(1)
            .firstOrNull() ?: return null

        val id = schema.mainTable.extractId(mainRow)
        val allChildRows = fetchChildRows(listOf(id))

        val decoded = decodeRow(mainRow, allChildRows)

        // Post-filter if condition had unsupported parts
        if (!ctx.isExact && !condition(decoded)) return null

        return decoded
    }

    private fun Transaction.findManyInTransaction(condition: Condition<T>): List<T> {
        val ctx = SqlConditionContext(schema, format)
        val condExpr = SqlExpressionBuilder.run {
            condition(condition, serializer, schema, format, ctx)
        }

        val mainRows = schema.mainTable.selectAll()
            .where { condExpr.asOp() }
            .toList()

        if (mainRows.isEmpty()) return emptyList()

        val ids = mainRows.map { schema.mainTable.extractId(it) }
        val allChildRows = fetchChildRows(ids)

        var result = mainRows.map { decodeRow(it, allChildRows) }

        if (!ctx.isExact) {
            result = result.filter { condition(it) }
        }

        return result
    }
}

private fun Expression<Boolean>.asOp(): Op<Boolean> = when (this) {
    is Op<Boolean> -> this
    else -> object : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            this@asOp.toQueryBuilder(queryBuilder)
        }
    }
}
