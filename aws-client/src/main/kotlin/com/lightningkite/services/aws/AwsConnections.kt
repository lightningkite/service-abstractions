package com.lightningkite.services.aws

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.SharedResources
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient
import software.amazon.awssdk.http.crt.AwsCrtHttpClient
import kotlin.math.roundToInt

/**
 * Shared AWS HTTP client connections with connection pooling and health monitoring.
 *
 * Provides centralized HTTP client management for AWS SDK operations with:
 * - **Connection pooling**: Reuses HTTP connections across AWS service clients
 * - **Performance**: Uses AWS CRT (Common Runtime) HTTP clients for optimal performance
 * - **Health monitoring**: Tracks connection utilization for observability
 * - **OpenTelemetry integration**: Automatic instrumentation when OpenTelemetry is configured
 * - **Resource efficiency**: Prevents each AWS client from creating separate connection pools
 *
 * ## Usage Pattern
 *
 * This class is designed to be used as a shared resource via `SettingContext`:
 *
 * ```kotlin
 * val awsConnections = context[AwsConnections]
 *
 * val s3Client = S3Client.builder()
 *     .httpClient(awsConnections.client)  // Sync operations
 *     .build()
 *
 * val dynamoAsync = DynamoDbAsyncClient.builder()
 *     .httpClient(awsConnections.asyncClient)  // Async operations
 *     .build()
 * ```
 *
 * ## Implementation Notes
 *
 * - **SharedResources pattern**: Automatically created once per SettingContext
 * - **CRT clients**: Uses AWS Common Runtime HTTP clients (faster than default)
 * - **Telemetry**: Integrates with OpenTelemetry if configured in SettingContext
 * - **Health tracking**: Provides connection utilization metrics via health property
 * - **Thread-safe**: CRT clients handle concurrency internally
 *
 * ## Important Gotchas
 *
 * - **Connection limits**: Default CRT client has connection pool limits (configurable)
 * - **total/used tracking**: Currently total is Int.MAX_VALUE (manual tracking not implemented)
 * - **Health check accuracy**: used/total metrics require manual updates by consumers
 * - **Shutdown**: CRT clients should be closed on application shutdown (not implemented here)
 * - **Memory**: Connection pools consume memory; monitor in serverless environments
 * - **OpenTelemetry overhead**: Telemetry adds ~5-10% performance overhead
 *
 * ## Health Status Thresholds
 *
 * - **OK**: < 70% connection utilization
 * - **WARNING**: 70-95% utilization
 * - **URGENT**: 95-100% utilization
 * - **ERROR**: > 100% utilization (over-subscribed)
 *
 * ## Performance Benefits
 *
 * Using shared connections provides significant benefits:
 * - **Reduced latency**: Connection reuse eliminates TLS handshake overhead
 * - **Lower memory**: Single pool vs. one per service client
 * - **Better throughput**: CRT clients optimized for high concurrency
 * - **Cost savings**: Fewer connections = less network overhead
 *
 * @property context Service context for accessing OpenTelemetry configuration
 * @property client Synchronous HTTP client for AWS SDK (blocking operations)
 * @property asyncClient Asynchronous HTTP client for AWS SDK (non-blocking operations)
 * @property total Total connection pool size (currently hardcoded to Int.MAX_VALUE)
 * @property used Currently used connections (requires manual tracking)
 * @property health Current health status based on connection utilization
 * @property clientOverrideConfiguration AWS SDK configuration with optional telemetry
 */
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
