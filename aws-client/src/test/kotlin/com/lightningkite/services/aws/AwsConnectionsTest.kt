// by Claude
package com.lightningkite.services.aws

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.get
import org.junit.Test
import java.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.test.*

/**
 * Tests for [AwsConnections] shared resource.
 *
 * These tests verify:
 * - Proper instantiation via SharedResources pattern
 * - Health status reflects real in-flight request count (no fabricated gauge)
 * - Client availability
 * - Timeout configuration on the default override config and on custom-budget configs
 */
class AwsConnectionsTest {

    @Test
    fun `test AwsConnections instantiation via SharedResources`() {
        val context = TestSettingContext()

        // Get AwsConnections via the SharedResources pattern
        val connections = context[AwsConnections]

        assertNotNull(connections)
        assertNotNull(connections.client)
        assertNotNull(connections.asyncClient)
    }

    @Test
    fun `test AwsConnections is cached in SharedResources`() {
        val context = TestSettingContext()

        // Get AwsConnections twice from the same context
        val connections1 = context[AwsConnections]
        val connections2 = context[AwsConnections]

        // Should be the exact same instance
        assertSame(connections1, connections2)
    }

    @Test
    fun `test different contexts get different AwsConnections instances`() {
        val context1 = TestSettingContext()
        val context2 = TestSettingContext()

        val connections1 = context1[AwsConnections]
        val connections2 = context2[AwsConnections]

        // Different contexts should have different instances
        assert(connections1 !== connections2)
    }

    @Test
    fun `test default total matches default maxConcurrency`() {
        // total is the denominator for health and defaults to the configured maxConcurrency (50).
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        assertEquals(50, connections.total)
    }

    @Test
    fun `test used starts at zero in-flight`() {
        // used is now a real in-flight gauge tracked by the execution interceptor, not a
        // fabricated value. With no requests issued it must be 0.
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        assertEquals(0, connections.used)
    }

    @Test
    fun `test health is OK with no in-flight requests`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // With used=0 the utilization is 0%, which is OK.
        val health = connections.health
        assertEquals(HealthStatus.Level.OK, health.level)
        assertNull(health.additionalMessage)
    }

    @Test
    fun `test clientOverrideConfiguration is non-null and carries default timeouts`() {
        // 1.0.0 always builds an override configuration so it can carry the timeout policy.
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        val config = connections.clientOverrideConfiguration
        assertNotNull(config)

        // Default total operation budget is 30s.
        assertEquals(Duration.ofSeconds(30), config.apiCallTimeout().orElse(null))
        // Per-attempt budget stays short for everyone.
        assertEquals(Duration.ofSeconds(10), config.apiCallAttemptTimeout().orElse(null))
    }

    @Test
    fun `test buildOverrideConfiguration honors a custom total budget but keeps short attempt timeout`() {
        // A consumer that needs a longer total budget (e.g. S3 large transfers) builds its own
        // config; the per-attempt timeout stays short so an unreachable endpoint fails fast.
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        val config = connections.buildOverrideConfiguration(1.hours)
        assertNotNull(config)

        // Total budget is the custom one hour the caller requested.
        assertEquals(Duration.ofHours(1), config.apiCallTimeout().orElse(null))
        // Attempt budget is still short, identical to the default config.
        assertEquals(Duration.ofSeconds(10), config.apiCallAttemptTimeout().orElse(null))
    }

    @Test
    fun `test custom-budget config differs from default only in total timeout`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        val default = connections.clientOverrideConfiguration
        val custom = connections.buildOverrideConfiguration(1.hours)

        // Same short attempt budget...
        assertEquals(
            default.apiCallAttemptTimeout().orElse(null),
            custom.apiCallAttemptTimeout().orElse(null),
        )
        // ...but different total budgets.
        assertNotEquals(
            default.apiCallTimeout().orElse(null),
            custom.apiCallTimeout().orElse(null),
        )
    }

    @Test
    fun `test companion object Key setup creates new instance`() {
        val context = TestSettingContext()

        val connections = AwsConnections.Key.setup(context)

        assertNotNull(connections)
        assertNotNull(connections.client)
        assertNotNull(connections.asyncClient)
    }
}
