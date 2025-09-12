package com.lightningkite.services

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public interface Service {
    /**
     * The name of the service.  This should be unique across all services.
     */
    public val name: String

    /**
     * The context in which this service was started.
     * Contains references to both serialization information and metrics information.
     */
    public val context: SettingContext

    /**
     * Explicitly connect to the service - i.e. warm up the connection.
     * Calling this method is optional.
     */
    public suspend fun connect() {}

    /**
     * Explicitly disconnect from the service.
     * Important for resuming state in AWS Lambda and other similar situations.
     * Calling this method is optional.
     */
    public suspend fun disconnect() {}

    /**
     * The intended frequency of health checks for this service
     */
    public val healthCheckFrequency: Duration get() = 1.minutes

    /**
     * Checks the status of this service
     */
    public suspend fun healthCheck(): HealthStatus
}