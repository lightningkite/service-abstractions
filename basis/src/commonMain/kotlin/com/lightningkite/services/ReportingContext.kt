package com.lightningkite.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext


public class ReportingContextElement(
    public val context: String,
    public val destination: MetricSink,
    public val parent: ReportingContextElement? = null,
    public val metricSums: MutableList<MetricEventInContext> = ArrayList()
) : AbstractCoroutineContextElement(ReportingContextElement) {
    public companion object Key : CoroutineContext.Key<ReportingContextElement>

    public fun report(type: MetricType, value: Double) {
        metricSums.add(MetricEventInContext(type, value))
    }

    public val path: String
        get() = generateSequence(this) { it.parent }.map { it.context }.toList().reversed().joinToString(".")

    override fun toString(): String = "$context: ${metricSums.joinToString(", ")}"
}

public val reportingLogger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.reporting")

public suspend inline fun <T> topLevelReportingContext(
    context: String,
    destination: MetricSink,
    crossinline action: suspend CoroutineScope.() -> T
): T {
    val parent = currentReportingContext()
    reportingLogger.info { "Handling $context" }
    val element = ReportingContextElement(context, destination, parent)
    return try {
        withContext(element) {
            MetricType.duration.measure {
                action()
            }
        }
    } finally {
        destination.report(element)
    }
}

public suspend inline fun <T> reportingContext(
    context: String,
    crossinline action: suspend CoroutineScope.() -> T
): T {
    val parent = currentReportingContext() ?: return action(CoroutineScope(coroutineContext))
    val destination = parent.destination
    val element = ReportingContextElement(context, destination, parent)
    return try {
        withContext(element) {
            MetricType.duration.measure {
                action()
            }
        }
    } finally {
        destination.report(element)
    }
}

public suspend fun currentReportingContext(): ReportingContextElement? = coroutineContext[ReportingContextElement.Key]
