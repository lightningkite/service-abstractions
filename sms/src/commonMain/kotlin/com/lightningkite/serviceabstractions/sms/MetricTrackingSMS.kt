package com.lightningkite.serviceabstractions.sms

import com.lightningkite.PhoneNumber
import com.lightningkite.serviceabstractions.HealthStatus
import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.countMetric
import com.lightningkite.serviceabstractions.performanceMetric
import com.lightningkite.serviceabstractions.increment
import com.lightningkite.serviceabstractions.report
import kotlin.time.TimeSource
import kotlin.time.DurationUnit

/**
 * An abstract base class for SMS implementations that tracks metrics.
 * Uses the template method pattern to implement metrics tracking.
 */
public abstract class MetricTrackingSMS(
    final override val context: SettingContext
) : SMS {
    private val sendPerformance = performanceMetric("send")
    private val sendCount = countMetric("send")
    private val sendFailure = countMetric("send", "failure")

    /**
     * Implementation of the send method that should be overridden by subclasses.
     */
    protected abstract suspend fun sendImplementation(to: PhoneNumber, message: String)

    /**
     * Sends an SMS message and tracks metrics.
     */
    final override suspend fun send(to: PhoneNumber, message: String) {
        try {
            // Manually measure time for suspending function
            val start = TimeSource.Monotonic.markNow()
            sendImplementation(to, message)
            val elapsed = start.elapsedNow()
            sendPerformance.report(elapsed.toDouble(DurationUnit.SECONDS))
            
            sendCount.increment()
        } catch (e: Exception) {
            sendFailure.increment()
            throw e
        }
    }

    /**
     * Default health check implementation.
     */
    override suspend fun healthCheck(): HealthStatus = SMS.healthStatusOk()
}