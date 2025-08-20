package com.lightningkite.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.jvm.JvmInline


public class ReportingContextElement internal constructor(
    public val context: String,
    internal val metrics: MetricReporter,
    internal val exceptions: ExceptionReporter,
    public val parent: ReportingContextElement? = null,
    public val metricSums: MutableList<MetricEventInContext> = ArrayList(),
    public val tags: MutableMap<ReportingTag, String> = mutableMapOf(),
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

@JvmInline public value class ReportingTag(public val raw: String) {
    public companion object {
        public val USER_ID: ReportingTag = ReportingTag("USER_ID")
        public val USERNAME: ReportingTag = ReportingTag("USERNAME")
        public val EMAIL_ADDRESS: ReportingTag = ReportingTag("EMAIL_ADDRESS")
        public val PHONE_NUMBER: ReportingTag = ReportingTag("PHONE_NUMBER")

        public val IP_ADDRESS: ReportingTag = ReportingTag("IP_ADDRESS")

        public val HEADERS: ReportingTag = ReportingTag("HEADERS")
        public val COOKIES: ReportingTag = ReportingTag("COOKIES")
        public val INPUT: ReportingTag = ReportingTag("INPUT")
        public val METHOD: ReportingTag = ReportingTag("METHOD")
        public val URL: ReportingTag = ReportingTag("URL")
        public val QUERY_STRING: ReportingTag = ReportingTag("QUERY_STRING")
    }
    public suspend fun set(value: String) {
        currentReportingContext()?.tags[this] = value
    }
}

public suspend fun <T> topLevelReportingContext(
    context: String,
    metrics: MetricReporter,
    exceptions: ExceptionReporter,
    action: suspend CoroutineScope.() -> T
): T {
    val parent = currentReportingContext()
    val element = ReportingContextElement(context, metrics, exceptions, parent)
    return try {
        withContext(element) {
            MetricType.duration.measure {
                action()
            }
        }
    } catch(e: CancellationException) {
        // do nothing
        throw e
    } catch(t: Throwable) {
        if(t.cause is CancellationException) {
            // do nothing
        } else {
            exceptions.report(t, context)
        }
        throw t
    } finally {
        metrics.report(element)
    }
}

public suspend fun <T> reportingContext(
    context: String,
    action: suspend CoroutineScope.() -> T
): T {
    val parent = currentReportingContext() ?: return action(CoroutineScope(coroutineContext))
    val element = ReportingContextElement(context, parent.metrics, parent.exceptions, parent)
    return try {
        withContext(element) {
            MetricType.duration.measure {
                action()
            }
        }
    } catch(e: CancellationException) {
        // do nothing
        throw e
    } catch(t: Throwable) {
        if(t.cause is CancellationException) {
            // do nothing
        } else {
            parent.exceptions.report(t, context)
        }
        throw t
    } finally {
        parent.metrics.report(element)
    }
}

internal suspend fun currentReportingContext(): ReportingContextElement? = coroutineContext[ReportingContextElement.Key]
