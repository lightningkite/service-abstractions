package com.lightningkite.serviceabstractions

import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Clock.System.now
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.TimeSource


public interface Metrics: Service {
//    public val settings: MetricSettings
    public suspend fun report(events: List<MetricEvent>)
    public suspend fun clean() {}

    override suspend fun healthCheck(): HealthStatus {
        return try {
            report(
                listOf(
                    MetricEvent(
                        type = healthCheckMetric,
                        timestamp = now(),
                        value = 1.0,
                        context = context,
                    )
                )
            )
            HealthStatus(HealthStatus.Level.OK, additionalMessage = "Available metrics: ${MetricType.known.map { it.name }.joinToString()}")
        } catch(e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    public companion object {
//        public val main get() = metricsSettings
        public val logger: getLogger = LoggerFactory.getLogger(Metrics::class)
        public val toReport: ConcurrentLinkedQueue = ConcurrentLinkedQueue<MetricEvent>()

        public val healthCheckMetric: MetricType =
            MetricType("Health Checks Run", MetricUnit.Count)
        public val executionTime = MetricType("Execution Time", MetricUnit.Milliseconds)

        init {
            Tasks.onEngineReady {
                engine.engine.backgroundReportingAction {
                    logger.debug("Assembling metrics to report...")
                    val assembledData = ArrayList<MetricEvent>(toReport.size)
                    while (true) {
                        val item = toReport.poll() ?: break
                        assembledData.add(item)
                    }
                    logger.debug("Reporting ${assembledData.size} metric events to ${main()}...")
                    main().report(assembledData)
                    logger.debug("Report complete.")
                }
            }
        }

        public fun report(type: MetricType, value: Double) {
            if (!Settings.sealed) return
            if (metricsSettings().settings.tracked(type.name))
                toReport.add(MetricEvent(type, null, now(), value))
        }

        public suspend fun reportPerHandler(type: MetricType, value: Double) {
            if (!Settings.sealed) return
            if (metricsSettings().settings.tracked(type.name))
                toReport.add(MetricEvent(type, serverContext()?.entryPoint?.toString() ?: "Unknown", now(), value))
        }

        public suspend fun addToSumPerHandler(type: MetricType, value: Double) {
            coroutineContext[ServerContextElement.Key]?.metricSums?.compute(type) { _, it -> (it ?: 0.0) + value }
        }

        public suspend fun <T> addPerformanceToSumPerHandler(type: MetricType, countType: MetricType? = null, action: suspend () -> T): T {
            val start = now().toEpochMilliseconds()
            val result = action()
            addToSumPerHandler(type, (now().toEpochMilliseconds() - start) / 1000.0)
            countType?.let { addToSumPerHandler(it, 1.0) }
            return result
        }

        public suspend fun <T> performancePerHandler(type: MetricType, action: suspend () -> T): T {
            val start = now().toEpochMilliseconds()
            val result = action()
            reportPerHandler(type, (now().toEpochMilliseconds() - start) / 1000.0)
            return result
        }

        public suspend fun <T> handlerPerformance(handler: ServerContext, action: suspend () -> T): T {
            return serverContext(handler) {
                val result = performancePerHandler(executionTime, action)
                try {
                    coroutineContext[ServerContextElement.Key]?.metricSums?.forEach {
                        reportPerHandler(it.key, it.value)
                    }
                } catch(e: Exception) {
                    e.report()
                }
                result
            }
        }
    }
}


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