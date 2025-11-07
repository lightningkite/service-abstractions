package com.lightningkite.services

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Represents the health status of a service at a specific point in time.
 *
 * Used by [Service.healthCheck] to report service availability and performance.
 * Health monitoring systems can collect these statuses to track service health over time.
 *
 * @property level Current operational level (OK, WARNING, URGENT, ERROR)
 * @property checkedAt Timestamp when this health check was performed
 * @property additionalMessage Optional diagnostic details (error message, metrics, etc.)
 *
 * @see Service.healthCheck
 */
@Serializable
public data class HealthStatus(
    val level: Level,
    val checkedAt: Instant = Clock.System.now(),
    val additionalMessage: String? = null
) {
    /**
     * Health status severity levels.
     *
     * Services should return these levels based on their operational state:
     * - [OK]: Service is fully operational with normal performance
     * - [WARNING]: Service is operational but degraded (slow responses, high load)
     * - [ERROR]: Service is non-functional or experiencing critical errors
     * - [URGENT]: Critical failure requiring immediate attention (data loss, security breach)
     */
    @Serializable
    public enum class Level {
        /** Service is fully operational with normal performance. */
        OK,
        /** Service is operational but degraded (slow responses, high load, etc.). */
        WARNING,
        /** Critical failure requiring immediate attention (data loss risk, security breach, etc.). */
        URGENT,
        /** Service is non-functional or experiencing critical errors. */
        ERROR,
    }
}