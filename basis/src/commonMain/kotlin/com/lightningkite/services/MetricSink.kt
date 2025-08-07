package com.lightningkite.services

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import io.github.oshai.kotlinlogging.KotlinLogging

public interface MetricSink: Service {
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "ram"
    ) : Setting<MetricSink> {

        public companion object : UrlSettingParser<MetricSink>() {
            init {
                register("none") { url, context -> None(context) }
                register("logger") { url, context -> MetricLogger(context) }
            }
        }

        override fun invoke(context: SettingContext): MetricSink {
            return parse(url, context)
        }
    }

    public suspend fun report(reportingInfo: ReportingContextElement)
    public suspend fun flush() {}

    public class None(override val context: SettingContext): MetricSink {
        override suspend fun report(reportingInfo: ReportingContextElement) {
        }
        override suspend fun healthCheck(): HealthStatus = HealthStatus(
            level = HealthStatus.Level.WARNING,
            additionalMessage = "No metrics will be reported.",
            checkedAt = context.clock.now()
        )
    }
    public class MetricLogger(override val context: SettingContext): MetricSink {
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
