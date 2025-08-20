package com.lightningkite.services

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

public interface ExceptionReporter : Service {

    /**
     * The function that makes the reports to the underlying service.
     */
    public suspend fun report(t: Throwable, context: Any? = null)

    /**
     * Will attempt to send a report to confirm that the service is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        val report = try {
            report(Exception("Health Check: Can Report Exception"))
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
            }
        }

        override fun invoke(name: String, context: SettingContext): ExceptionReporter {
            return parse(name, url, context)
        }
    }

    public class None(override val name: String, override val context: SettingContext): ExceptionReporter {
        override suspend fun report(t: Throwable, context: Any?) {
        }
        override suspend fun healthCheck(): HealthStatus = HealthStatus(
            level = HealthStatus.Level.WARNING,
            additionalMessage = "No metrics will be reported.",
            checkedAt = context.clock.now()
        )
    }
}