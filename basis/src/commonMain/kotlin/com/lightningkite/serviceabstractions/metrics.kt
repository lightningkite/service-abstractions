package com.lightningkite.serviceabstractions

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

public interface MetricSink {
    public suspend fun metric(type: MetricType, value: Double = 1.0)
    public object None: MetricSink {
        override suspend fun metric(type: MetricType, value: Double) {}
    }
}

public sealed interface MetricType {
    public val service: Service
    public val path: List<String>
    public val unit: MetricUnit
    public class Count(
        override val service: Service,
        override val path: List<String>,
    ) : MetricType {
        override val unit: MetricUnit = MetricUnit.Count
    }
    public class Performance(
        override val service: Service,
        override val path: List<String>,
    ) : MetricType {
        override val unit: MetricUnit = MetricUnit.Seconds
    }
}
public fun Service.performanceMetric(vararg path: String) = MetricType.Performance(this, path.toList())
public fun Service.countMetric(vararg path: String) = MetricType.Count(this, path.toList())

public suspend fun MetricType.report(value: Double = 1.0) {
    service.context.metricSink.metric(this, value)
}
public suspend fun MetricType.Count.increment(value: Double = 1.0) {
    service.context.metricSink.metric(this, value)
}
public suspend inline fun <R> MetricType.Performance.measure(block: () -> R): R {
    val start = TimeSource.Monotonic.markNow()
    val result = block()
    report(start.elapsedNow().toDouble(DurationUnit.SECONDS))
    return result
}

@Serializable
public enum class MetricUnit {
    Seconds,
    Bytes,
    Percent,
    Count,
    BytesPerSecond,
    CountPerSecond,
    Other,
}