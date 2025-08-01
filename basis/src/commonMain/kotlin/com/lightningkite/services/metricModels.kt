package com.lightningkite.services

import kotlinx.serialization.Serializable
import kotlin.time.Clock.System.now
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.TimeSource


public sealed interface MetricType {
    public val service: Service?
    public val name: String
    public val unit: MetricUnit

    public companion object {
        public val duration: Performance = Performance(null, "duration")
    }

    public class Count(
        override val service: Service?,
        override val name: String,
    ) : MetricType {
        override val unit: MetricUnit = MetricUnit.Count
    }

    public class Performance(
        override val service: Service?,
        override val name: String,
    ) : MetricType {
        override val unit: MetricUnit = MetricUnit.Seconds
    }
}
public fun Service.performanceMetric(name: String): MetricType.Performance = MetricType.Performance(this, name)
public fun Service.countMetric(name: String): MetricType.Count = MetricType.Count(this, name)

public suspend fun MetricType.report(value: Double) {
    currentReportingContext()?.report(this, value)
}
public suspend fun MetricType.Count.increment(value: Double = 1.0): Unit = report(value)
public suspend inline fun <R> MetricType.Performance.measure(block: () -> R): R {
    val start = TimeSource.Monotonic.markNow()
    val result = block()
    report(start.elapsedNow().toDouble(DurationUnit.SECONDS))
    return result
}

public class MetricEvent(
    public val type: MetricType,
    public val context: String,
    public val value: Double,
    public val timestamp: Instant = now(),
) {
    override fun toString(): String = "$context/${type.service}/${type.name}: $value ${type.unit}"
}
public data class MetricEventInContext(
    public val type: MetricType,
    public val value: Double,
) {
    override fun toString(): String = "${type.service}/${type.name}: $value ${type.unit}"
}

@Serializable
public enum class MetricUnit {
    Seconds,
    Microseconds,
    Milliseconds,
    Bytes,
    Kilobytes,
    Megabytes,
    Gigabytes,
    Terabytes,
    Bits,
    Kilobits,
    Megabits,
    Gigabits,
    Terabits,
    Percent,
    Count,
    BytesPerSecond,
    KilobytesPerSecond,
    MegabytesPerSecond,
    GigabytesPerSecond,
    TerabytesPerSecond,
    BitsPerSecond,
    KilobitsPerSecond,
    MegabitsPerSecond,
    GigabitsPerSecond,
    TerabitsPerSecond,
    CountPerSecond,
    Other,
}