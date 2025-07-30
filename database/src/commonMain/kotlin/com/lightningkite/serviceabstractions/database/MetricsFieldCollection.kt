package com.lightningkite.serviceabstractions.database

import com.lightningkite.serialization.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlin.time.Clock
import com.lightningkite.serviceabstractions.MetricType
import com.lightningkite.serviceabstractions.increment
import com.lightningkite.serviceabstractions.measure

class MetricsFieldCollection<M : Any>(
    override val wraps: FieldCollection<M>,
    metricsKeyName: String = "Database",
    val waitTimeMetric: MetricType.Performance,
    val callCountMetric: MetricType.Count,
) :
    FieldCollection<M> {
    override val serializer: KSerializer<M> get() = wraps.serializer

    override suspend fun find(
        condition: Condition<M>,
        orderBy: List<SortPart<M>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<M> {
        val source = wraps.find(condition, orderBy, skip, limit, maxQueryMs)
        return flow {
            var now = Clock.System.now().toEpochMilliseconds()
            var timeSum = 0L
            callCountMetric.increment()
            try {
                source.collect {
                    timeSum += (Clock.System.now().toEpochMilliseconds() - now)
                    emit(it)
                    now = Clock.System.now().toEpochMilliseconds()
                }
            } finally {
                waitTimeMetric.measure { timeSum / 1000.0 }
            }
        }
    }

    override suspend fun count(condition: Condition<M>): Int =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.count(condition)
        }

    override suspend fun <Key> groupCount(condition: Condition<M>, groupBy: DataClassPath<M, Key>): Map<Key, Int> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.groupCount(condition, groupBy)
        }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<M>,
        property: DataClassPath<M, N>
    ): Double? =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.aggregate(aggregate, condition, property)
        }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<M>,
        groupBy: DataClassPath<M, Key>,
        property: DataClassPath<M, N>
    ): Map<Key, Double?> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.groupAggregate(aggregate, condition, groupBy, property)
        }

    override suspend fun insert(models: Iterable<M>): List<M> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.insert(models)
        }

    override suspend fun replaceOne(condition: Condition<M>, model: M, orderBy: List<SortPart<M>>): EntryChange<M> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.replaceOne(condition, model, orderBy)
        }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<M>,
        model: M,
        orderBy: List<SortPart<M>>
    ): Boolean =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.replaceOneIgnoringResult(condition, model, orderBy)
        }

    override suspend fun upsertOne(condition: Condition<M>, modification: Modification<M>, model: M): EntryChange<M> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.upsertOne(condition, modification, model)
        }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<M>,
        modification: Modification<M>,
        model: M
    ): Boolean =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.upsertOneIgnoringResult(condition, modification, model)
        }

    override suspend fun updateOne(
        condition: Condition<M>,
        modification: Modification<M>,
        orderBy: List<SortPart<M>>
    ): EntryChange<M> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.updateOne(condition, modification, orderBy)
        }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<M>,
        modification: Modification<M>,
        orderBy: List<SortPart<M>>
    ): Boolean = waitTimeMetric.measure {
        callCountMetric.increment()
        wraps.updateOneIgnoringResult(condition, modification, orderBy)
    }

    override suspend fun updateMany(condition: Condition<M>, modification: Modification<M>): CollectionChanges<M> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.updateMany(condition, modification)
        }

    override suspend fun updateManyIgnoringResult(condition: Condition<M>, modification: Modification<M>): Int =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.updateManyIgnoringResult(condition, modification)
        }

    override suspend fun deleteOne(condition: Condition<M>, orderBy: List<SortPart<M>>): M? =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.deleteOne(condition, orderBy)
        }

    override suspend fun deleteOneIgnoringOld(condition: Condition<M>, orderBy: List<SortPart<M>>): Boolean =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.deleteOneIgnoringOld(condition, orderBy)
        }

    override suspend fun deleteMany(condition: Condition<M>): List<M> =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.deleteMany(condition)
        }

    override suspend fun deleteManyIgnoringOld(condition: Condition<M>): Int =
        waitTimeMetric.measure {
            callCountMetric.increment()
            wraps.deleteManyIgnoringOld(condition)
        }
}
