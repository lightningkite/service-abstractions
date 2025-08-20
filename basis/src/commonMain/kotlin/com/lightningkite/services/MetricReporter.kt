package com.lightningkite.services

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import io.github.oshai.kotlinlogging.KotlinLogging

public interface MetricReporter: Service {
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "none"
    ) : Setting<MetricReporter> {

        public companion object : UrlSettingParser<MetricReporter>() {
            init {
                register("none") { name, url, context -> None(name, context) }
                register("logger") { name, url, context -> MetricLogger(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): MetricReporter {
            return parse(name, url, context)
        }
    }

    public suspend fun report(reportingInfo: ReportingContextElement)
    public suspend fun flush() {}

    public class None(override val name: String, override val context: SettingContext): MetricReporter {
        override suspend fun report(reportingInfo: ReportingContextElement) {
        }
        override suspend fun healthCheck(): HealthStatus = HealthStatus(
            level = HealthStatus.Level.WARNING,
            additionalMessage = "No metrics will be reported.",
            checkedAt = context.clock.now()
        )
    }
    public class MetricLogger(override val name: String, override val context: SettingContext): MetricReporter {
        private val log = KotlinLogging.logger("com.lightningkite.services.MetricSink.MetricLogger")
        override suspend fun report(reportingInfo: ReportingContextElement) {
            log.info { reportingInfo.toString() }
        }
        override suspend fun healthCheck(): HealthStatus = HealthStatus(
            level = HealthStatus.Level.OK,
            additionalMessage = "Metrics are logged.",
            checkedAt = context.clock.now()
        )
    }
}
