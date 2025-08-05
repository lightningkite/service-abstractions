package com.lightningkite.services.notifications

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.countMetric
import com.lightningkite.services.increment
import com.lightningkite.services.performanceMetric
import com.lightningkite.services.report
import kotlin.time.TimeSource

/**
 * Abstract base class for notification services that tracks metrics.
 * Uses the template method pattern to implement metrics tracking around the core functionality.
 */
public abstract class MetricTrackingNotificationService(
    final override val context: SettingContext
) : NotificationService {

    // Define metric types
    private val sendCountMetric by lazy { this.countMetric("notification", "send", "count") }
    private val sendSuccessMetric by lazy { this.countMetric("notification", "send", "success") }
    private val sendDeadTokenMetric by lazy { this.countMetric("notification", "send", "deadtoken") }
    private val sendFailureMetric by lazy { this.countMetric("notification", "send", "failure") }
    private val sendErrorMetric by lazy { this.countMetric("notification", "send", "error") }
    private val sendPerformanceMetric by lazy { this.performanceMetric("notification", "send", "duration") }
    
    private val healthCheckCountMetric by lazy { this.countMetric("notification", "healthcheck", "count") }
    private val healthCheckErrorMetric by lazy { this.countMetric("notification", "healthcheck", "error") }
    private val healthCheckOkMetric by lazy { this.countMetric("notification", "healthcheck", "ok") }
    private val healthCheckWarningMetric by lazy { this.countMetric("notification", "healthcheck", "warning") }
    private val healthCheckUrgentMetric by lazy { this.countMetric("notification", "healthcheck", "urgent") }
    private val healthCheckErrorStatusMetric by lazy { this.countMetric("notification", "healthcheck", "error") }
    private val healthCheckPerformanceMetric by lazy { this.performanceMetric("notification", "healthcheck", "duration") }

    /**
     * The implementation-specific method for sending notifications.
     * This is called by the public [send] method which handles metrics tracking.
     */
    protected abstract suspend fun sendImplementation(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult>

    /**
     * The implementation-specific method for health checks.
     * This is called by the public [healthCheck] method which handles metrics tracking.
     */
    protected open suspend fun healthCheckImplementation(): HealthStatus = HealthStatus(HealthStatus.Level.OK)

    /**
     * Sends a notification to the specified targets, with metrics tracking.
     * 
     * @param targets The device tokens to send the notification to
     * @param data The notification data to send
     * @return A map of target tokens to send results
     */
    override suspend fun send(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult> {
        if (targets.isEmpty()) return emptyMap()
        
        var result: Map<String, NotificationSendResult>? = null
        val mark = TimeSource.Monotonic.markNow()
        
        try {
            result = sendImplementation(targets, data)
        } catch (e: Exception) {
            sendErrorMetric.increment(1.0)
            throw e
        } finally {
            val duration = mark.elapsedNow()
            sendPerformanceMetric.report(duration.inWholeSeconds.toDouble())
        }
        
        sendCountMetric.increment(targets.size.toDouble())
        
        // Track success/failure metrics
        val successCount = result.count { it.value == NotificationSendResult.Success }
        val deadTokenCount = result.count { it.value == NotificationSendResult.DeadToken }
        val failureCount = result.count { it.value == NotificationSendResult.Failure }
        
        sendSuccessMetric.increment(successCount.toDouble())
        
        if (deadTokenCount > 0) {
            sendDeadTokenMetric.increment(deadTokenCount.toDouble())
        }
        
        if (failureCount > 0) {
            sendFailureMetric.increment(failureCount.toDouble())
        }
        
        return result
    }

    /**
     * Performs a health check on the notification service, with metrics tracking.
     */
    override suspend fun healthCheck(): HealthStatus {
        var result: HealthStatus? = null
        val mark = TimeSource.Monotonic.markNow()
        
        try {
            result = healthCheckImplementation()
        } catch (e: Exception) {
            healthCheckErrorMetric.increment(1.0)
            result = HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        } finally {
            val duration = mark.elapsedNow()
            healthCheckPerformanceMetric.report(duration.inWholeSeconds.toDouble())
        }
        
        healthCheckCountMetric.increment(1.0)
        
        when (result.level) {
            HealthStatus.Level.OK -> healthCheckOkMetric.increment(1.0)
            HealthStatus.Level.WARNING -> healthCheckWarningMetric.increment(1.0)
            HealthStatus.Level.URGENT -> healthCheckUrgentMetric.increment(1.0)
            HealthStatus.Level.ERROR -> healthCheckErrorStatusMetric.increment(1.0)
        }
        
        return result
    }
}