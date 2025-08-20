package com.lightningkite.services

public interface ExceptionReporter : Service {

    /**
     * The function that makes the reports to the underlying service.
     */
    public suspend fun report(t: Throwable, context: Any? = null): Boolean

    /**
     * Will attempt to send a report to confirm that the service is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        val report = try {
            report(Exception("Health Check: Can Report Exception"))
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
        return if (report)
            HealthStatus(HealthStatus.Level.OK)
        else
            HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "Disabled"
            )
    }
}