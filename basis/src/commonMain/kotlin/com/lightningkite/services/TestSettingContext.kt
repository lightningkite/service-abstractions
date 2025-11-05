package com.lightningkite.services

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

/**
 * Minimal [SettingContext] implementation for testing purposes.
 *
 * Provides sensible defaults for all context properties:
 * - Empty serializers module
 * - System clock (but mutable for test control)
 * - Localhost public URL
 * - Fresh shared resources
 * - "Test" project name
 * - No telemetry
 *
 * ## Usage
 *
 * Basic usage with defaults:
 * ```kotlin
 * @Test
 * fun testDatabaseOperations() = runTest {
 *     val context = TestSettingContext()
 *     val db = Database.Settings("ram://")("test-db", context)
 *
 *     // Use database in test
 * }
 * ```
 *
 * With custom clock for time-dependent tests:
 * ```kotlin
 * @Test
 * fun testTimeBasedFeatures() = runTest {
 *     val testClock = TestClock(Instant.parse("2025-01-01T00:00:00Z"))
 *     val context = TestSettingContext(clock = testClock)
 *
 *     val service = MyService("test", context)
 *     // Service will use testClock for all time operations
 * }
 * ```
 *
 * With custom serializers for domain types:
 * ```kotlin
 * @Test
 * fun testWithCustomTypes() = runTest {
 *     val module = SerializersModule {
 *         polymorphic(MyInterface::class) {
 *             subclass(MyImpl::class)
 *         }
 *     }
 *     val context = TestSettingContext(internalSerializersModule = module)
 *
 *     // Services can now serialize/deserialize MyInterface
 * }
 * ```
 *
 * @property internalSerializersModule Serialization module for custom types (default: empty)
 * @property clock Time source for services (default: system clock, mutable for testing)
 */
public class TestSettingContext(
    override val internalSerializersModule: SerializersModule = EmptySerializersModule(),
    override var clock: Clock = Clock.System
): SettingContext {
    /**
     * Public URL for the test application.
     *
     * Defaults to "http://localhost:8080" which is suitable for most tests.
     * Override if tests need to verify URL generation logic.
     */
    override val publicUrl: String get() = "http://localhost:8080"

    /**
     * Shared resource pool for this test context.
     *
     * Each TestSettingContext gets its own SharedResources instance to ensure
     * test isolation - resources from one test don't leak into another.
     */
    override val sharedResources: SharedResources = SharedResources()

    /**
     * Project name for the test.
     *
     * Defaults to "Test". Override if tests need to verify project-name-specific behavior.
     */
    override val projectName: String get() = "Test"

    /**
     * OpenTelemetry instance for testing.
     *
     * Defaults to null since most tests don't need telemetry instrumentation.
     * Set a mock OpenTelemetry instance if testing tracing/metrics behavior.
     */
    override val openTelemetry: OpenTelemetry? get() = null
}
