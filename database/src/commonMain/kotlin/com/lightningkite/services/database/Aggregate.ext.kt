package com.lightningkite.services.database


public inline fun <T> Aggregate.aggregate(
    iterator: Iterator<T>,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    transform: (T) -> Double?
): Double? = aggregator(mode).aggregate(iterator, transform)

public inline fun <T, K> Aggregate.groupAggregate(
    iterator: Iterator<T>,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    entry: (T) -> Pair<K, Double>?
): Map<K, Double?> {
    val aggregators = HashMap<K, Aggregator>()
    for (item in iterator) entry(item)?.let {
        aggregators.getOrPut(it.first) { aggregator(mode) }.consume(it.second)
    }
    return aggregators.mapValues { it.value.complete() }
}


public fun Iterable<Double>.aggregate(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision
): Double? =
    aggregate.aggregate(iterator(), mode) { it }

public fun <N : Number> Iterable<N>.aggregate(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision
): Double? =
    aggregate.aggregate(iterator(), mode) { it.toDouble() }

public inline fun <T> Iterable<T>.aggregateOf(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    transform: (T) -> Double
): Double? =
    aggregate.aggregate(iterator(), mode, transform)

public inline fun <T> Iterable<T>.aggregateOfNotNull(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    transform: (T) -> Double?
): Double? =
    aggregate.aggregate(iterator(), mode, transform)

public fun Sequence<Double>.aggregate(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision
): Double? =
    aggregate.aggregate(iterator(), mode) { it }

public inline fun <T> Sequence<T>.aggregateOf(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    transform: (T) -> Double
): Double? =
    aggregate.aggregate(iterator(), mode, transform)

public inline fun <T> Sequence<T>.aggregateOfNotNull(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    transform: (T) -> Double?
): Double? =
    aggregate.aggregate(iterator(), mode, transform)

public inline fun <T, GROUP> Iterable<T>.groupAggregate(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    entry: (T) -> Pair<GROUP, Double>
): Map<GROUP, Double?> =
    aggregate.groupAggregate(iterator(), mode, entry)

public inline fun <T, GROUP> Iterable<T>.groupAggregateNotNull(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    entry: (T) -> Pair<GROUP, Double>?
): Map<GROUP, Double?> =
    aggregate.groupAggregate(iterator(), mode, entry)

public inline fun <T, GROUP> Sequence<T>.groupAggregate(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    entry: (T) -> Pair<GROUP, Double>
): Map<GROUP, Double?> =
    aggregate.groupAggregate(iterator(), mode, entry)

public inline fun <T, GROUP> Sequence<T>.groupAggregateNotNull(
    aggregate: Aggregate,
    mode: Aggregator.Mode = Aggregator.Mode.Precision,
    entry: (T) -> Pair<GROUP, Double>?
): Map<GROUP, Double?> =
    aggregate.groupAggregate(iterator(), mode, entry)


public fun <GROUP> Sequence<Pair<GROUP, Double>>.aggregate(aggregate: Aggregate): Map<GROUP, Double?> =
    groupAggregate(aggregate) { it }
