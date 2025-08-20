package com.lightningkite.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

public interface ExceptionReporter : Service {

    /**
     * The function that makes the reports to the underlying service.
     */
    public suspend fun report(t: Throwable, context: String)

    /**
     * Will attempt to send a report to confirm that the service is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        val report = try {
            report(Exception("Health Check: Can Report Exception"), "HEALTH CHECK")
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
        return HealthStatus(HealthStatus.Level.OK)
    }


    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "none"
    ) : Setting<ExceptionReporter> {

        public companion object : UrlSettingParser<ExceptionReporter>() {
            init {
                register("none") { name, url, context -> None(name, context) }
                register("log") { name, url, context -> Logging(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): ExceptionReporter {
            return parse(name, url, context)
        }
    }

    public class None(override val name: String, override val context: SettingContext): ExceptionReporter {
        override suspend fun report(t: Throwable, context: String) {
        }
        override suspend fun healthCheck(): HealthStatus = HealthStatus(
            level = HealthStatus.Level.WARNING,
            additionalMessage = "No metrics will be reported.",
            checkedAt = context.clock.now()
        )
    }

    public class Logging(override val name: String, override val context: SettingContext): ExceptionReporter {
        private val logs = KotlinLogging.logger("com.lightningkite.services.ExceptionReporter.$name")
        override suspend fun report(t: Throwable, context: String) {
            logs.error(t) { context }
        }
        override suspend fun healthCheck(): HealthStatus = HealthStatus(
            level = HealthStatus.Level.WARNING,
            additionalMessage = "No metrics will be reported other than the logs",
            checkedAt = context.clock.now()
        )
    }
}