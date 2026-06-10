package com.lightningkite.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.modules.SerializersModule
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * Runtime context provided to all services during instantiation and operation.
 *
 * `SettingContext` contains shared configuration and resources that services need:
 * - Serialization configuration for custom types
 * - Observability (the vendor-neutral [MetricsBackend]) integration
 * - Shared resource pools (database connections, HTTP clients, etc.)
 * - Application metadata (project name, public URL)
 * - Time source (mockable for testing)
 *
 * ## Design Philosophy
 *
 * Rather than passing individual parameters to every service constructor, this context
 * object provides a stable interface for shared concerns. This:
 * - Reduces constructor parameter counts
 * - Allows adding new shared resources without breaking existing services
 * - Enables consistent behavior across all service implementations
 *
 * ## Usage
 *
 * Typically, an application creates one `SettingContext` at startup:
 * ```kotlin
 * val context = object : SettingContext {
 *     override val projectName = "my-app"
 *     override val publicUrl = "https://example.com"
 *     override val internalSerializersModule = SerializersModule {
 *         polymorphic(MyInterface::class) { subclass(MyImpl::class) }
 *     }
 *     override val metricsBackend = OtelMetricsBackend(openTelemetrySdk)
 *     override val sharedResources = SharedResources()
 * }
 *
 * // Pass to all service instantiations
 * val db = Database.Settings("mongodb://localhost")("main-db", context)
 * ```
 *
 * @see SharedResources for resource sharing pattern
 * @see MetricsBackend for observability integration
 */
public interface SettingContext {
    /**
     * Name of the application/project using these services.
     *
     * Used for:
     * - Logging and error reporting to identify which application is calling
     * - OpenTelemetry service name tagging
     * - Multi-tenant scenarios where multiple apps share infrastructure
     *
     * Example: "user-service", "payment-api", "admin-dashboard"
     */
    public val projectName: String

    /**
     * Public-facing base URL where this application is accessible.
     *
     * Used for:
     * - Generating absolute URLs in emails and notifications
     * - OAuth redirect URI construction
     * - Webhook callback URL generation
     * - CORS and security policy configuration
     *
     * Should include protocol and domain, typically no trailing slash.
     * Example: "https://api.example.com" or "http://localhost:8080"
     */
    public val publicUrl: String

    /**
     * Kotlinx Serialization module containing custom type serializers.
     *
     * Services use this to serialize/deserialize application-specific types when:
     * - Storing data in databases
     * - Caching objects
     * - Sending data over queues/pubsub
     *
     * ## Important
     *
     * All custom types that will be persisted must be registered here.
     * Missing serializers will cause runtime errors when services attempt serialization.
     *
     * Example:
     * ```kotlin
     * SerializersModule {
     *     polymorphic(BaseClass::class) {
     *         subclass(SubclassA::class)
     *         subclass(SubclassB::class)
     *     }
     *     contextual(CustomType::class, CustomTypeSerializer)
     * }
     * ```
     */
    public val internalSerializersModule: SerializersModule

    /**
     * Backend for the coroutine-first metrics API ([metricsTrace], [metricsHistogram], etc.) and
     * error reporting ([reportException]).
     *
     * Defaults to `null`, in which case those functions run with no-op instruments and error
     * reporting only logs. The JVM OpenTelemetry-backed implementation
     * (`com.lightningkite.services.otel.OtelMetricsBackend` in otel-jvm) is wired in here at
     * application startup. This is the vendor-neutral telemetry abstraction; it is the only
     * telemetry surface exposed by `SettingContext`.
     */
    public val metricsBackend: MetricsBackend? get() = null

    public val telemetrySanitization: TelemetrySanitization get() = TelemetrySanitization.Strict

    /**
     * Time source for all time-dependent operations.
     *
     * Defaults to [Clock.System] (actual system time).
     *
     * ## Testing
     *
     * Can be overridden with a custom clock for testing:
     * ```kotlin
     * val testContext = object : SettingContext {
     *     override val clock = TestClock(fixedInstant)
     *     // ... other properties
     * }
     * ```
     *
     * Services should use `context.clock.now()` instead of `Clock.System.now()`
     * to respect this setting.
     */
    public val clock: Clock get() = Clock.System

    /**
     * Shared resource pool for expensive or limited resources.
     *
     * Services use this to share:
     * - HTTP client instances (connection pooling)
     * - Thread pools for blocking I/O
     * - SSL/TLS contexts
     * - Other singleton resources that shouldn't be duplicated per service
     *
     * Access via the extension function: `context[MyResource.Key]`
     *
     * @see SharedResources for usage details
     * @see get operator extension for convenient access
     */
    public val sharedResources: SharedResources

    /**
     * Reports an already-caught [throwable] to logging and telemetry.
     *
     * Default behavior:
     * - Always logs the throwable at ERROR via the standard multiplatform logger, including
     *   the [context] entries, so failures are visible even with no telemetry backend.
     * - Delegates to [metricsBackend]'s [MetricsBackend.reportError] when a backend is configured.
     *   The JVM OpenTelemetry backend records the exception on the active span if one exists, or
     *   otherwise emits an ERROR log record (the path for failures outside any span, e.g. background
     *   index creation).
     *
     * @param throwable The exception to report.
     * @param context Additional key/value attributes attached to the report for diagnostics.
     */
    public suspend fun reportException(throwable: Throwable) {
        logger.error(throwable){""}
        metricsBackend?.reportError(throwable, currentCoroutineContext()[MetricAttributeElement]?.attributes ?: MetricAttributes.empty)
    }

    public companion object {
        private val logger = KotlinLogging.logger("com.lightningkite.services")
    }
}

/**
 * Convenient operator to access shared resources.
 *
 * Instead of:
 * ```kotlin
 * val httpClient = context.sharedResources.get(HttpClient.Key, context)
 * ```
 *
 * Use:
 * ```kotlin
 * val httpClient = context[HttpClient.Key]
 * ```
 *
 * @param key The resource key to look up
 * @return The resource instance, creating it if necessary
 * @see SharedResources.get
 */
public operator fun <T> SettingContext.get(key: SharedResources.Key<T>): T = sharedResources.get(key, this)
