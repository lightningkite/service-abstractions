package com.lightningkite.services

import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

/**
 * Runtime context provided to all services during instantiation and operation.
 *
 * `SettingContext` contains shared configuration and resources that services need:
 * - Serialization configuration for custom types
 * - Observability (OpenTelemetry) integration
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
 *     override val openTelemetry = OpenTelemetry.get()
 *     override val sharedResources = SharedResources()
 * }
 *
 * // Pass to all service instantiations
 * val db = Database.Settings("mongodb://localhost")("main-db", context)
 * ```
 *
 * @see SharedResources for resource sharing pattern
 * @see OpenTelemetry for observability integration
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
     * Optional OpenTelemetry instance for distributed tracing and metrics.
     *
     * When provided, services will:
     * - Create spans for operations (database queries, cache hits, etc.)
     * - Record metrics (operation counts, latencies, error rates)
     * - Propagate trace context across service boundaries
     *
     * When `null`, services operate normally but without telemetry.
     * This is common in:
     * - Local development environments
     * - Testing scenarios
     * - Applications that use alternative observability solutions
     */
    public val openTelemetry: OpenTelemetry?

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
     * Wraps an async action with potential observability/error handling.
     *
     * Default implementation simply executes the action. Implementations can override to:
     * - Wrap actions in OpenTelemetry spans
     * - Add error logging/reporting
     * - Implement retry logic
     * - Track operation metrics
     *
     * This is primarily used internally by service implementations to instrument operations.
     */
    public suspend fun report(action: suspend ()->Unit): Unit = action()

    public companion object {
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
