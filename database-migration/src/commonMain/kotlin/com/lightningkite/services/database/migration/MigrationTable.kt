package com.lightningkite.services.database.migration

import com.lightningkite.services.database.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/**
 * A Table wrapper that supports dual-write for database migrations.
 *
 * Routes reads and writes based on the current [MigrationMode]:
 * - [MigrationMode.SOURCE_ONLY]: All operations go to source only
 * - [MigrationMode.DUAL_WRITE_READ_SOURCE]: Writes to both, reads from source
 * - [MigrationMode.DUAL_WRITE_READ_TARGET]: Writes to both, reads from target
 * - [MigrationMode.TARGET_ONLY]: All operations go to target only
 *
 * Secondary writes (to the non-primary database) are performed asynchronously with
 * retry on failure. This ensures the primary database response is not blocked by
 * secondary database issues.
 */
public class MigrationTable<Model : Any>(
    public val sourceTable: Table<Model>,
    public val targetTable: Table<Model>,
    override val serializer: KSerializer<Model>,
    private val modeProvider: () -> MigrationMode,
    private val retryQueue: RetryQueue<RetryOperation<Model>>
) : Table<Model> {

    private val logger = KotlinLogging.logger {}

    override val wraps: Table<Model>?
        get() = when (modeProvider()) {
            MigrationMode.SOURCE_ONLY, MigrationMode.DUAL_WRITE_READ_SOURCE -> sourceTable
            MigrationMode.DUAL_WRITE_READ_TARGET, MigrationMode.TARGET_ONLY -> targetTable
        }

    /** The current mode (for observability) */
    public val currentMode: MigrationMode get() = modeProvider()

    // ===== Sealed class for retry operations =====

    public sealed class RetryOperation<Model> {
        public data class Insert<Model>(val models: List<Model>) : RetryOperation<Model>()
        public data class Replace<Model>(
            val condition: Condition<Model>,
            val model: Model,
            val orderBy: List<SortPart<Model>>
        ) : RetryOperation<Model>()
        public data class Upsert<Model>(
            val condition: Condition<Model>,
            val modification: Modification<Model>,
            val model: Model
        ) : RetryOperation<Model>()
        public data class UpdateOne<Model>(
            val condition: Condition<Model>,
            val modification: Modification<Model>,
            val orderBy: List<SortPart<Model>>
        ) : RetryOperation<Model>()
        public data class UpdateMany<Model>(
            val condition: Condition<Model>,
            val modification: Modification<Model>
        ) : RetryOperation<Model>()
        public data class DeleteOne<Model>(
            val condition: Condition<Model>,
            val orderBy: List<SortPart<Model>>
        ) : RetryOperation<Model>()
        public data class DeleteMany<Model>(
            val condition: Condition<Model>
        ) : RetryOperation<Model>()
    }

    // ===== Helper functions =====

    private val primaryTable: Table<Model>
        get() = when (modeProvider()) {
            MigrationMode.SOURCE_ONLY, MigrationMode.DUAL_WRITE_READ_SOURCE -> sourceTable
            MigrationMode.DUAL_WRITE_READ_TARGET, MigrationMode.TARGET_ONLY -> targetTable
        }

    private val secondaryTable: Table<Model>?
        get() = when (modeProvider()) {
            MigrationMode.SOURCE_ONLY, MigrationMode.TARGET_ONLY -> null
            MigrationMode.DUAL_WRITE_READ_SOURCE -> targetTable
            MigrationMode.DUAL_WRITE_READ_TARGET -> sourceTable
        }

    private val readTable: Table<Model>
        get() = when (modeProvider()) {
            MigrationMode.SOURCE_ONLY, MigrationMode.DUAL_WRITE_READ_SOURCE -> sourceTable
            MigrationMode.DUAL_WRITE_READ_TARGET, MigrationMode.TARGET_ONLY -> targetTable
        }

//    private inline fun writeToSecondary(
//        retryOp: RetryOperation<Model>,
//        crossinline block: suspend () -> Unit
//    ) {
//        val secondary = secondaryTable ?: return
//
//        runBlocking {
//            try {
//                block()
//            } catch (e: Exception) {
//                logger.warn(e) { "Secondary write failed, queueing for retry" }
//                retryQueue.enqueue(retryOp, e)
//            }
//        }
//    }

    private suspend inline fun writeToSecondaryAsync(
        retryOp: RetryOperation<Model>,
        crossinline block: suspend () -> Unit
    ) {
        val secondary = secondaryTable ?: return

        try {
            block()
        } catch (e: Exception) {
            logger.warn(e) { "Secondary write failed, queueing for retry" }
            retryQueue.enqueue(retryOp, e)
        }
    }

    // ===== Read Operations =====

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> = readTable.find(condition, orderBy, skip, limit, maxQueryMs)

    override suspend fun findPartial(
        fields: Set<DataClassPathPartial<Model>>,
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Partial<Model>> = readTable.findPartial(fields, condition, orderBy, skip, limit, maxQueryMs)

    override suspend fun count(condition: Condition<Model>): Int = readTable.count(condition)

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>
    ): Map<Key, Int> = readTable.groupCount(condition, groupBy)

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>
    ): Double? = readTable.aggregate(aggregate, condition, property)

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>
    ): Map<Key, Double?> = readTable.groupAggregate(aggregate, condition, groupBy, property)

    override suspend fun findSimilar(
        vectorField: DataClassPath<Model, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long
    ): Flow<ScoredResult<Model>> = readTable.findSimilar(vectorField, params, condition, maxQueryMs)

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<Model, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long
    ): Flow<ScoredResult<Model>> = readTable.findSimilarSparse(vectorField, params, condition, maxQueryMs)

    // ===== Write Operations =====

    override suspend fun insert(models: Iterable<Model>): List<Model> {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.insert(models)
            MigrationMode.TARGET_ONLY -> targetTable.insert(models)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.insert(models)
                writeToSecondaryAsync(RetryOperation.Insert(result)) {
                    targetTable.insert(result)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.insert(models)
                writeToSecondaryAsync(RetryOperation.Insert(result)) {
                    sourceTable.insert(result)
                }
                result
            }
        }
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.replaceOne(condition, model, orderBy)
            MigrationMode.TARGET_ONLY -> targetTable.replaceOne(condition, model, orderBy)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.replaceOne(condition, model, orderBy)
                writeToSecondaryAsync(RetryOperation.Replace(condition, model, orderBy)) {
                    targetTable.replaceOne(condition, model, orderBy)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.replaceOne(condition, model, orderBy)
                writeToSecondaryAsync(RetryOperation.Replace(condition, model, orderBy)) {
                    sourceTable.replaceOne(condition, model, orderBy)
                }
                result
            }
        }
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.replaceOneIgnoringResult(condition, model, orderBy)
            MigrationMode.TARGET_ONLY -> targetTable.replaceOneIgnoringResult(condition, model, orderBy)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.replaceOneIgnoringResult(condition, model, orderBy)
                writeToSecondaryAsync(RetryOperation.Replace(condition, model, orderBy)) {
                    targetTable.replaceOneIgnoringResult(condition, model, orderBy)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.replaceOneIgnoringResult(condition, model, orderBy)
                writeToSecondaryAsync(RetryOperation.Replace(condition, model, orderBy)) {
                    sourceTable.replaceOneIgnoringResult(condition, model, orderBy)
                }
                result
            }
        }
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.upsertOne(condition, modification, model)
            MigrationMode.TARGET_ONLY -> targetTable.upsertOne(condition, modification, model)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.upsertOne(condition, modification, model)
                writeToSecondaryAsync(RetryOperation.Upsert(condition, modification, model)) {
                    targetTable.upsertOne(condition, modification, model)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.upsertOne(condition, modification, model)
                writeToSecondaryAsync(RetryOperation.Upsert(condition, modification, model)) {
                    sourceTable.upsertOne(condition, modification, model)
                }
                result
            }
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.upsertOneIgnoringResult(condition, modification, model)
            MigrationMode.TARGET_ONLY -> targetTable.upsertOneIgnoringResult(condition, modification, model)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.upsertOneIgnoringResult(condition, modification, model)
                writeToSecondaryAsync(RetryOperation.Upsert(condition, modification, model)) {
                    targetTable.upsertOneIgnoringResult(condition, modification, model)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.upsertOneIgnoringResult(condition, modification, model)
                writeToSecondaryAsync(RetryOperation.Upsert(condition, modification, model)) {
                    sourceTable.upsertOneIgnoringResult(condition, modification, model)
                }
                result
            }
        }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.updateOne(condition, modification, orderBy)
            MigrationMode.TARGET_ONLY -> targetTable.updateOne(condition, modification, orderBy)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.updateOne(condition, modification, orderBy)
                writeToSecondaryAsync(RetryOperation.UpdateOne(condition, modification, orderBy)) {
                    targetTable.updateOne(condition, modification, orderBy)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.updateOne(condition, modification, orderBy)
                writeToSecondaryAsync(RetryOperation.UpdateOne(condition, modification, orderBy)) {
                    sourceTable.updateOne(condition, modification, orderBy)
                }
                result
            }
        }
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.updateOneIgnoringResult(condition, modification, orderBy)
            MigrationMode.TARGET_ONLY -> targetTable.updateOneIgnoringResult(condition, modification, orderBy)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.updateOneIgnoringResult(condition, modification, orderBy)
                writeToSecondaryAsync(RetryOperation.UpdateOne(condition, modification, orderBy)) {
                    targetTable.updateOneIgnoringResult(condition, modification, orderBy)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.updateOneIgnoringResult(condition, modification, orderBy)
                writeToSecondaryAsync(RetryOperation.UpdateOne(condition, modification, orderBy)) {
                    sourceTable.updateOneIgnoringResult(condition, modification, orderBy)
                }
                result
            }
        }
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.updateMany(condition, modification)
            MigrationMode.TARGET_ONLY -> targetTable.updateMany(condition, modification)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.updateMany(condition, modification)
                writeToSecondaryAsync(RetryOperation.UpdateMany(condition, modification)) {
                    targetTable.updateMany(condition, modification)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.updateMany(condition, modification)
                writeToSecondaryAsync(RetryOperation.UpdateMany(condition, modification)) {
                    sourceTable.updateMany(condition, modification)
                }
                result
            }
        }
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.updateManyIgnoringResult(condition, modification)
            MigrationMode.TARGET_ONLY -> targetTable.updateManyIgnoringResult(condition, modification)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.updateManyIgnoringResult(condition, modification)
                writeToSecondaryAsync(RetryOperation.UpdateMany(condition, modification)) {
                    targetTable.updateManyIgnoringResult(condition, modification)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.updateManyIgnoringResult(condition, modification)
                writeToSecondaryAsync(RetryOperation.UpdateMany(condition, modification)) {
                    sourceTable.updateManyIgnoringResult(condition, modification)
                }
                result
            }
        }
    }

    override suspend fun deleteOne(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Model? {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.deleteOne(condition, orderBy)
            MigrationMode.TARGET_ONLY -> targetTable.deleteOne(condition, orderBy)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.deleteOne(condition, orderBy)
                writeToSecondaryAsync(RetryOperation.DeleteOne(condition, orderBy)) {
                    targetTable.deleteOne(condition, orderBy)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.deleteOne(condition, orderBy)
                writeToSecondaryAsync(RetryOperation.DeleteOne(condition, orderBy)) {
                    sourceTable.deleteOne(condition, orderBy)
                }
                result
            }
        }
    }

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.deleteOneIgnoringOld(condition, orderBy)
            MigrationMode.TARGET_ONLY -> targetTable.deleteOneIgnoringOld(condition, orderBy)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.deleteOneIgnoringOld(condition, orderBy)
                writeToSecondaryAsync(RetryOperation.DeleteOne(condition, orderBy)) {
                    targetTable.deleteOneIgnoringOld(condition, orderBy)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.deleteOneIgnoringOld(condition, orderBy)
                writeToSecondaryAsync(RetryOperation.DeleteOne(condition, orderBy)) {
                    sourceTable.deleteOneIgnoringOld(condition, orderBy)
                }
                result
            }
        }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.deleteMany(condition)
            MigrationMode.TARGET_ONLY -> targetTable.deleteMany(condition)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.deleteMany(condition)
                writeToSecondaryAsync(RetryOperation.DeleteMany(condition)) {
                    targetTable.deleteMany(condition)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.deleteMany(condition)
                writeToSecondaryAsync(RetryOperation.DeleteMany(condition)) {
                    sourceTable.deleteMany(condition)
                }
                result
            }
        }
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        val mode = modeProvider()
        return when (mode) {
            MigrationMode.SOURCE_ONLY -> sourceTable.deleteManyIgnoringOld(condition)
            MigrationMode.TARGET_ONLY -> targetTable.deleteManyIgnoringOld(condition)
            MigrationMode.DUAL_WRITE_READ_SOURCE -> {
                val result = sourceTable.deleteManyIgnoringOld(condition)
                writeToSecondaryAsync(RetryOperation.DeleteMany(condition)) {
                    targetTable.deleteManyIgnoringOld(condition)
                }
                result
            }
            MigrationMode.DUAL_WRITE_READ_TARGET -> {
                val result = targetTable.deleteManyIgnoringOld(condition)
                writeToSecondaryAsync(RetryOperation.DeleteMany(condition)) {
                    sourceTable.deleteManyIgnoringOld(condition)
                }
                result
            }
        }
    }
}
