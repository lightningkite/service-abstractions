package com.lightningkite.services.aws

import com.lightningkite.services.SettingContext
import com.lightningkite.services.SharedResources
import com.lightningkite.services.data.HealthStatus
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient
import software.amazon.awssdk.http.crt.AwsCrtHttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Shared AWS HTTP client connections with connection pooling and health monitoring.
 *
 * Provides centralized HTTP client management for AWS SDK operations with:
 * - **Connection pooling**: Reuses HTTP connections across AWS service clients
 * - **Performance**: Uses AWS CRT (Common Runtime) HTTP clients for optimal performance
 * - **Timeouts**: Lambda-safe defaults so a stuck/unreachable endpoint fails fast instead of
 *   hanging for the lifetime of a serverless invocation
 * - **Health monitoring**: Tracks real in-flight request count for observability
 * - **Resource efficiency**: Prevents each AWS client from creating separate connection pools
 *
 * ## Usage Pattern
 *
 * This class is designed to be used as a shared resource via `SettingContext`:
 *
 * ```kotlin
 * val awsConnections = context[AwsConnections]
 *
 * val dynamoAsync = DynamoDbAsyncClient.builder()
 *     .httpClient(awsConnections.asyncClient)  // Async operations
 *     .overrideConfiguration(awsConnections.clientOverrideConfiguration)  // default budget
 *     .build()
 *
 * // A consumer needing a longer total budget builds its own:
 * val s3Client = S3Client.builder()
 *     .httpClient(awsConnections.client)
 *     .overrideConfiguration(awsConnections.buildOverrideConfiguration(1.hours))
 *     .build()
 * ```
 *
 * ## Timeout policy
 *
 * Timeouts are split into a short per-attempt/connection budget (shared by everyone) and a
 * per-operation total budget (which a consumer may override):
 *
 * - [connectionTimeout] (default 10s): how long to wait for a TCP/TLS connection. Short for
 *   everyone — an unreachable endpoint must fail fast.
 * - [apiCallAttemptTimeout] (default 10s): budget for a single HTTP attempt. Short for everyone,
 *   so an unreachable endpoint does not hang.
 * - [apiCallTimeout] (default 30s): total budget for an operation across retries. This is the
 *   sensible default; a consumer that needs a different total budget builds its own override
 *   configuration via [buildOverrideConfiguration].
 *
 * The default [clientOverrideConfiguration] carries the 30s total budget and is the sensible
 * choice for DynamoDB, PubSub, and most services. A consumer that legitimately needs a longer
 * total budget (e.g. large transfers) calls [buildOverrideConfiguration] with its own budget;
 * the short attempt/connection timeouts stay the same.
 *
 * ## Health
 *
 * [health] reflects the number of currently in-flight AWS SDK requests (tracked via an execution
 * interceptor) against [maxConcurrency]. Note this is in-flight request count, **not** CRT socket
 * pool occupancy — the CRT client does not expose its internal pool gauges — but it is a real
 * signal of how close we are to saturating the configured concurrency.
 *
 * @property context Service context
 * @property client Synchronous HTTP client for AWS SDK (blocking operations)
 * @property asyncClient Asynchronous HTTP client for AWS SDK (non-blocking operations)
 * @property total Configured concurrency ceiling used as the denominator for [health]
 * @property used Currently in-flight request count (real, tracked via execution interceptor)
 * @property health Current health status based on in-flight requests vs [maxConcurrency]
 * @property clientOverrideConfiguration AWS SDK configuration with the default total budget
 */
public class AwsConnections(
    private val context: SettingContext,
    private val connectionTimeout: Duration = 10.seconds,
    private val connectionMaxIdleTime: Duration = 60.seconds,
    private val maxConcurrency: Int = 50,
    private val apiCallAttemptTimeout: Duration = 10.seconds,
    private val apiCallTimeout: Duration = 30.seconds,
) {
    public companion object Key : SharedResources.Key<AwsConnections> {
        override fun setup(context: SettingContext): AwsConnections = AwsConnections(context)
    }

    public val client: AwsCrtHttpClient = AwsCrtHttpClient.builder()
        .connectionTimeout(connectionTimeout.toJavaDuration())
        .connectionMaxIdleTime(connectionMaxIdleTime.toJavaDuration())
        .maxConcurrency(maxConcurrency)
        .build() as AwsCrtHttpClient
    public val asyncClient: AwsCrtAsyncHttpClient = AwsCrtAsyncHttpClient.builder()
        .connectionTimeout(connectionTimeout.toJavaDuration())
        .connectionMaxIdleTime(connectionMaxIdleTime.toJavaDuration())
        .maxConcurrency(maxConcurrency)
        .build() as AwsCrtAsyncHttpClient

    /**
     * Concurrency ceiling, used as the denominator for [health]. Defaults to [maxConcurrency]
     * so health is comparable against the actual CRT client configuration.
     */
    public var total: Int = maxConcurrency

    private val inFlight = AtomicInteger(0)

    /** Currently in-flight AWS SDK requests, tracked in real time by [inFlightInterceptor]. */
    public val used: Int get() = inFlight.get()

    public val health: HealthStatus
        get() = when (val amount = used / total.toFloat()) {
            in 0f..<0.7f -> HealthStatus(HealthStatus.Level.OK)
            in 0.7f..<0.95f -> HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "In-flight requests: ${amount.times(100).roundToInt()}% of $total"
            )

            in 0.95f..<1f -> HealthStatus(
                HealthStatus.Level.URGENT,
                additionalMessage = "In-flight requests: ${amount.times(100).roundToInt()}% of $total"
            )

            else -> HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "In-flight requests: ${amount.times(100).roundToInt()}% of $total"
            )
        }

    /**
     * Tracks real in-flight request count. Exactly one of [afterExecution]/[onExecutionFailure]
     * fires per execution, so the counter stays balanced.
     */
    private val inFlightInterceptor: ExecutionInterceptor = object : ExecutionInterceptor {
        override fun beforeExecution(context: Context.BeforeExecution, executionAttributes: ExecutionAttributes) {
            inFlight.incrementAndGet()
        }

        override fun afterExecution(context: Context.AfterExecution, executionAttributes: ExecutionAttributes) {
            inFlight.decrementAndGet()
        }

        override fun onExecutionFailure(context: Context.FailedExecution, executionAttributes: ExecutionAttributes) {
            inFlight.decrementAndGet()
        }
    }

    /**
     * Builds an override configuration with the given total operation budget.
     *
     * Callers pass their own total budget ([apiCallTimeout]) for an operation across retries;
     * the per-attempt ([apiCallAttemptTimeout]) and connection ([connectionTimeout]) budgets stay
     * short so an unreachable endpoint still fails fast regardless of the total budget. The result
     * also carries the in-flight tracking interceptor.
     *
     * Most consumers should use [clientOverrideConfiguration]; call this only when a different
     * total budget is genuinely required (e.g. large transfers that are legitimately slow).
     *
     * @param apiCallTimeout total budget for an operation across retries
     */
    public fun buildOverrideConfiguration(apiCallTimeout: Duration): ClientOverrideConfiguration =
        ClientOverrideConfiguration.builder()
            .apiCallTimeout(apiCallTimeout.toJavaDuration())
            .apiCallAttemptTimeout(apiCallAttemptTimeout.toJavaDuration())
            .addExecutionInterceptor(inFlightInterceptor)
            .build()

    /** Override configuration with the default total operation budget ([apiCallTimeout]). */
    public val clientOverrideConfiguration: ClientOverrideConfiguration = buildOverrideConfiguration(apiCallTimeout)

    public fun close() {
        client.close()
        asyncClient.close()
    }
}
