package com.lightningkite.services.database

import kotlin.math.sqrt

public interface Aggregator {
    public enum class Mode { Precision, Speed }

    public val aggregate: Aggregate
    public val mode: Mode

    public fun consume(value: Double)
    public fun complete(): Double?
}

public inline fun <T> Aggregator.aggregate(iterator: Iterator<T>, transform: (T) -> Double?): Double? {
    for (item in iterator) transform(item)?.let(::consume)
    return complete()
}

public fun Aggregate.aggregator(
    mode: Aggregator.Mode = Aggregator.Mode.Precision
): Aggregator = when (this) {
    Aggregate.Sum -> SumAggregator(mode)
    Aggregate.Average -> when (mode) {
        Aggregator.Mode.Speed -> AverageAggregatorSpeed()
        Aggregator.Mode.Precision -> AverageAggregatorPrecision()
    }
    Aggregate.StandardDeviationSample -> when (mode) {
        Aggregator.Mode.Speed -> StandardDeviationSampleAggregatorSpeed()
        Aggregator.Mode.Precision -> StandardDeviationSampleAggregatorPrecision()
    }
    Aggregate.StandardDeviationPopulation -> when (mode) {
        Aggregator.Mode.Speed -> StandardDeviationPopulationAggregatorSpeed()
        Aggregator.Mode.Precision -> StandardDeviationPopulationAggregatorPrecision()
    }
}

private class SumAggregator(override val mode: Aggregator.Mode) : Aggregator {
    override val aggregate: Aggregate get() = Aggregate.Sum
    var current: Double = 0.0
    var anyFound = false

    override fun consume(value: Double) {
        anyFound = true
        current += value
    }

    override fun complete(): Double? = if (anyFound) current else null
}

private class AverageAggregatorSpeed : Aggregator {
    override val aggregate: Aggregate get() = Aggregate.Average
    override val mode: Aggregator.Mode get() = Aggregator.Mode.Speed
    var count: Int = 0
    var sum: Double = 0.0

    override fun consume(value: Double) {
        count++
        sum += value
    }

    override fun complete(): Double? = if (count == 0) null else sum / count.toDouble()
}

private class AverageAggregatorPrecision : Aggregator {
    override val aggregate: Aggregate get() = Aggregate.Average
    override val mode: Aggregator.Mode get() = Aggregator.Mode.Precision
    var count: Int = 0
    var current: Double = 0.0

    override fun consume(value: Double) {
        count++
        current += (value - current) / count.toDouble()
    }

    override fun complete(): Double? = if (count == 0) null else current
}

private class StandardDeviationSampleAggregatorSpeed : Aggregator {
    override val aggregate: Aggregate get() = Aggregate.StandardDeviationSample
    override val mode: Aggregator.Mode get() = Aggregator.Mode.Speed
    var count: Int = 0
    var sum: Double = 0.0
    var sumSquares: Double = 0.0

    override fun consume(value: Double) {
        count++
        sum += value
        sumSquares += value * value
    }

    override fun complete(): Double? {
        if (count < 2) return null
        val n = count.toDouble()
        val mean = sum / n
        val variance = (sumSquares / n) - (mean * mean)
        return sqrt(variance * n / (n - 1))
    }
}

private class StandardDeviationSampleAggregatorPrecision : Aggregator {
    override val aggregate: Aggregate get() = Aggregate.StandardDeviationSample
    override val mode: Aggregator.Mode get() = Aggregator.Mode.Precision
    var count: Int = 0
    var mean: Double = 0.0
    var m2: Double = 0.0

    override fun consume(value: Double) {
        count++
        val delta1 = value - mean
        mean += (delta1) / count.toDouble()
        val delta2 = value - mean
        m2 += delta1 * delta2
    }

    override fun complete(): Double? = if (count < 2) null else sqrt(m2 / (count.toDouble() - 1))
}

private class StandardDeviationPopulationAggregatorSpeed : Aggregator {
    override val aggregate: Aggregate get() = Aggregate.StandardDeviationPopulation
    override val mode: Aggregator.Mode get() = Aggregator.Mode.Speed
    var count: Int = 0
    var sum: Double = 0.0
    var sumSquares: Double = 0.0

    override fun consume(value: Double) {
        count++
        sum += value
        sumSquares += value * value
    }

    override fun complete(): Double? {
        if (count == 0) return null
        val n = count.toDouble()
        val mean = sum / n
        val variance = (sumSquares / n) - (mean * mean)
        return sqrt(variance)
    }
}

private class StandardDeviationPopulationAggregatorPrecision : Aggregator {
    override val aggregate: Aggregate get() = Aggregate.StandardDeviationPopulation
    override val mode: Aggregator.Mode get() = Aggregator.Mode.Precision
    var count: Int = 0
    var mean: Double = 0.0
    var m2: Double = 0.0

    override fun consume(value: Double) {
        count++
        val delta1 = value - mean
        mean += (delta1) / count.toDouble()
        val delta2 = value - mean
        m2 += delta1 * delta2
    }

    override fun complete(): Double? = if (count == 0) null else sqrt(m2 / count.toDouble())
}