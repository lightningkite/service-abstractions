package com.lightningkite.services

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Base interface for all service abstractions in the library.
 *
 * Service represents any external infrastructure dependency (databases, caches, file systems,
 * email providers, etc.) that an application needs to interact with. Implementations provide
 * concrete connectivity to specific service providers.
 *
 * ## Lifecycle Management
 *
 * Services support explicit lifecycle management through [connect] and [disconnect] methods,
 * which is critical for:
 * - Serverless environments (AWS Lambda, Cloud Functions) where connections may be frozen/resumed
 * - Resource pooling and warm-up during application startup
 * - Graceful shutdown procedures
 *
 * ## Health Monitoring
 *
 * All services must implement [healthCheck] to enable monitoring and alerting systems to
 * verify service availability and performance.
 *
 * ## Naming
 *
 * Each service instance must have a unique [name] that identifies it within the application.
 * This name is used for logging, metrics, and debugging purposes.
 *
 * @see SettingContext for the context passed to all service implementations
 * @see HealthStatus for health check result representation
 */
public interface Service {
    /**
     * Unique identifier for this service instance within the application.
     *
     * Used for:
     * - Logging and debugging to identify which service instance is being referenced
     * - OpenTelemetry traces and metrics tagging
     * - Service registry lookups
     *
     * Example names: "user-database", "session-cache", "s3-uploads"
     */
    public val name: String

    /**
     * Configuration and runtime context provided when the service was instantiated.
     *
     * The context contains:
     * - [SettingContext.internalSerializersModule]: Serializers for custom types
     * - [SettingContext.openTelemetry]: Optional telemetry for tracing/metrics
     * - [SettingContext.clock]: Clock for time-dependent operations (mockable for tests)
     * - [SettingContext.sharedResources]: Shared connection pools and resources
     * - [SettingContext.projectName]: Application name for logging
     * - [SettingContext.publicUrl]: Base URL for generating public links
     */
    public val context: SettingContext

    /**
     * Establishes connection to the underlying service provider.
     *
     * This method is **optional** to call - most implementations will lazily connect on first use.
     * However, calling it explicitly is useful for:
     * - Pre-warming connections during application startup to reduce initial request latency
     * - Validating configuration early (fail-fast on misconfiguration)
     * - Connection pooling initialization
     *
     * ## Serverless Environments
     *
     * In serverless environments (AWS Lambda, SnapStart), this should be called during
     * initialization to establish connections that will be frozen with the execution context.
     *
     * ## Idempotency
     *
     * Implementations should make this method idempotent - calling it multiple times
     * should not create multiple connections or cause errors.
     */
    public suspend fun connect() {}

    /**
     * Explicitly closes connections to the underlying service provider.
     *
     * This method is **optional** to call but is critical for:
     * - Serverless environments (AWS Lambda) before execution context freezes
     * - Graceful application shutdown to release resources
     * - Testing scenarios where services need to be torn down between tests
     *
     * ## Serverless Environments
     *
     * AWS Lambda and similar platforms freeze the execution context between invocations.
     * Active network connections can cause issues when thawed. Call [disconnect] before
     * the handler returns to properly release resources.
     *
     * ## Idempotency
     *
     * Implementations should make this method idempotent - calling it multiple times
     * should not cause errors, even if already disconnected.
     */
    public suspend fun disconnect() {}

    /**
     * How often health checks should be performed on this service instance.
     *
     * Health monitoring systems should use this value to schedule periodic checks.
     * Implementations can override this based on service characteristics:
     * - Fast, reliable services (in-memory caches): Longer intervals
     * - Critical, external services (databases): Shorter intervals
     * - Expensive checks: Longer intervals to avoid overhead
     *
     * Default: 1 minute
     */
    public val healthCheckFrequency: Duration get() = 1.minutes

    /**
     * Verifies that this service is operational and responds within acceptable time limits.
     *
     * Health checks should:
     * - Execute quickly (typically < 5 seconds)
     * - Test actual connectivity (not just local state)
     * - Return [HealthStatus.Level.OK] when fully operational
     * - Return [HealthStatus.Level.WARNING] for degraded performance
     * - Return [HealthStatus.Level.ERROR] when non-functional
     * - Return [HealthStatus.Level.URGENT] for critical failures requiring immediate attention
     *
     * ## Implementation Guidelines
     *
     * Good health check implementation:
     * ```kotlin
     * override suspend fun healthCheck(): HealthStatus = try {
     *     // Perform lightweight operation that verifies connectivity
     *     database.runCommand(Document("ping", 1))
     *     HealthStatus(level = HealthStatus.Level.OK)
     * } catch (e: Exception) {
     *     HealthStatus(
     *         level = HealthStatus.Level.ERROR,
     *         additionalMessage = e.message
     *     )
     * }
     * ```
     *
     * @return Current health status with optional diagnostic message
     */
    public suspend fun healthCheck(): HealthStatus
}