package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.Aggregate
import com.lightningkite.services.database.CollectionChanges
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.findOne
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.statementsRequiredToActualizeScheme
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import java.sql.Connection.TRANSACTION_SERIALIZABLE

public class PostgresCollection<T : Any>(
    public val db: Database,
    public val name: String,
    override val serializer: KSerializer<T>,
    public val serializersModule: SerializersModule
) : Table<T> {
    private var format = DbMapLikeFormat(serializersModule)

    private val table = SerialDescriptorTable(name, serializersModule, serializer.descriptor)

    private suspend inline fun <T> t(noinline action: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db, transactionIsolation = TRANSACTION_READ_COMMITTED, statement = {
            addLogger(StdOutSqlLogger)
            action()
        })

    @OptIn(DelicateCoroutinesApi::class, ExperimentalSerializationApi::class)
    private val prepare = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        t {
//            MigrationUtils.statementsRequiredForDatabaseMigration
            statementsRequiredToActualizeScheme(table).forEach {
                exec(it)
            }
        }
    }

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> {
        prepare.await()
        val items = t {
            table
                .selectAll()
                .where { condition(condition, serializer, table, format).asOp() }
                .orderBy(*orderBy.map {
                    @Suppress("UNCHECKED_CAST")
                    (
                            if (it.field.serializerAny.descriptor.kind == PrimitiveKind.STRING) {
                                // TODO: Check database default collation to skip extra work
                                if (it.ignoreCase) (table.col[it.field.colName]!! as Column<String>).lowerCase()
                                else AsciiValue(table.col[it.field.colName]!! as Column<String>)
                            } else table.col[it.field.colName]!!
                            ) to if (it.ascending) SortOrder.ASC else SortOrder.DESC
                }
                    .toTypedArray())
                .limit(limit).offset(skip.toLong())
                .toList().also { println("list is $it") }
//                .prep
                .map {
                    format.decode(serializer, it)
                }
        }
        return items.asFlow()
    }

    override suspend fun count(condition: Condition<T>): Int {
        prepare.await()
        return t {
            table
                .selectAll().where { condition(condition, serializer, table, format).asOp() }
                .count().toInt()
        }
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: DataClassPath<T, Key>): Map<Key, Int> {
        prepare.await()
        return t {
            @Suppress("UNCHECKED_CAST")
            val groupCol = table.col[groupBy.colName] as Column<Key>
            val count = Count(stringLiteral("*"))
            table.select(groupCol, count)
                .where { condition(condition, serializer, table, format).asOp() }
                .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[count].toInt() }
        }
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: DataClassPath<T, N>,
    ): Double? {
        prepare.await()
        return t {
            @Suppress("UNCHECKED_CAST")
            val valueCol = table.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.select(agg)
                .where { condition(condition, serializer, table, format).asOp() }
                .firstOrNull()?.get(agg)?.toDouble()
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> {
        prepare.await()
        return t {
            @Suppress("UNCHECKED_CAST")
            val groupCol = table.col[groupBy.colName] as Column<Key>

            @Suppress("UNCHECKED_CAST")
            val valueCol = table.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.select(groupCol, agg)
                .where { condition(condition, serializer, table, format).asOp() }
                .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[agg]?.toDouble() }
        }
    }

    override suspend fun insert(models: Iterable<T>): List<T> {
        prepare.await()
        t {
            table.batchInsert(models) {
                format.encode(serializer, it, this)
            }
        }
        return models.toList()
    }

    override suspend fun replaceOne(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> {
        return updateOne(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>
    ): Boolean {
        return updateOneIgnoringResult(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun upsertOne(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T
    ): EntryChange<T> {
        return newSuspendedTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                EntryChange(null, insert(listOf(model)).first())
            } else
                updateOne(condition, modification)
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        return newSuspendedTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                insert(listOf(model))
                false
            } else
                updateOneIgnoringResult(condition, modification, listOf())
        }
    }

    override suspend fun updateOne(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>
    ): EntryChange<T> {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            val old = table.updateReturningOld(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = 1,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
            old.map { format.decode(serializer, it) }.firstOrNull()?.let {
                EntryChange(it, modification(it))
            } ?: EntryChange()
        }
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>
    ): Boolean {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            table.update(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
        } > 0
    }

    override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> {
        return t {
            val old = table.updateReturningOld(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
            CollectionChanges(old.map { format.decode(serializer, it) }.map {
                EntryChange(it, modification(it))
            })
        }
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int {
        return t {
            table.update(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
        }
    }

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            table.deleteReturningWhere(
                limit = 1,
                where = { condition(condition, serializer, table, format).asOp() }
            ).firstOrNull()?.let { format.decode(serializer, it) }
        }
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            table.deleteWhere(
                limit = 1,
                op = { it.condition(condition, serializer, table, format).asOp() }
            ) > 0
        }
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> {
        return t {
            table.deleteReturningWhere(
                where = { condition(condition, serializer, table, format).asOp() }
            ).map { format.decode(serializer, it) }
        }
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int {
        return t {
            table.deleteWhere(
                op = { it.condition(condition, serializer, table, format).asOp() }
            )
        }
    }

}