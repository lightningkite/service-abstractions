package com.lightningkite.services.aws

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.SharedResources
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient
import software.amazon.awssdk.http.crt.AwsCrtHttpClient
import kotlin.math.roundToInt

public class AwsConnections(private val context: SettingContext) {
    public companion object Key: SharedResources.Key<AwsConnections> {
        override fun setup(context: SettingContext): AwsConnections = AwsConnections(context)
    }
    public val client: AwsCrtHttpClient = AwsCrtHttpClient.builder()
        .build() as AwsCrtHttpClient
    public val asyncClient: AwsCrtAsyncHttpClient = AwsCrtAsyncHttpClient.builder()
        .build() as AwsCrtAsyncHttpClient
    public var total: Int = Int.MAX_VALUE
    public var used: Int = 0
    public val health: HealthStatus
        get() = when(val amount = used / total.toFloat()) {
        in 0f ..< 0.7f -> HealthStatus(HealthStatus.Level.OK)
        in 0.7f ..< 0.95f -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
        in 0.95f ..< 1f -> HealthStatus(HealthStatus.Level.URGENT, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
        else -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
    }
    private val telemetry: AwsSdkTelemetry? = context.openTelemetry?.let {
        AwsSdkTelemetry.create(it)
    }
    public val clientOverrideConfiguration: ClientOverrideConfiguration? = ClientOverrideConfiguration.builder()
        .let {
            if(telemetry != null) it.addExecutionInterceptor(telemetry.newExecutionInterceptor())
            else it
        }
        .build()
}
