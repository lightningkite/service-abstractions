package com.lightningkite.services.email

import com.lightningkite.services.countMetric
import com.lightningkite.services.increment
import com.lightningkite.services.measure
import com.lightningkite.services.performanceMetric

/**
 * Automatically track performance for email service implementations.
 */
public abstract class MetricTrackingEmailService : EmailService {
    private val sendMetric = performanceMetric("send")
    private val sendBulkMetric = performanceMetric("sendBulk")
    private val failureMetric = countMetric("failures")

    /**
     * Internal implementation of send that will be measured for performance.
     */
    protected abstract suspend fun sendInternal(email: Email)

    /**
     * Sends a single email with performance tracking.
     */
    override suspend fun send(email: Email) {
        try {
            sendMetric.measure { sendInternal(email) }
        } catch (e: Exception) {
            failureMetric.increment()
            throw e
        }
    }

    /**
     * Internal implementation of sendBulk for personalized emails that will be measured for performance.
     */
    protected open suspend fun sendBulkInternal(template: Email, personalizations: List<EmailPersonalization>) {
        personalizations.forEach {
            sendInternal(it(template))
        }
    }

    /**
     * Sends multiple personalized emails based on a template with performance tracking.
     */
    override suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) {
        try {
            sendBulkMetric.measure { sendBulkInternal(template, personalizations) }
        } catch (e: Exception) {
            failureMetric.increment()
            throw e
        }
    }

    /**
     * Internal implementation of sendBulk for multiple emails that will be measured for performance.
     */
    protected open suspend fun sendBulkInternal(emails: Collection<Email>) {
        emails.forEach {
            sendInternal(it)
        }
    }

    /**
     * Sends multiple emails with performance tracking.
     */
    override suspend fun sendBulk(emails: Collection<Email>) {
        try {
            sendBulkMetric.measure { sendBulkInternal(emails) }
        } catch (e: Exception) {
            failureMetric.increment()
            throw e
        }
    }
}