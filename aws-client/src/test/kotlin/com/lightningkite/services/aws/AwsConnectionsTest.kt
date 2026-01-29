// by Claude
package com.lightningkite.services.aws

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.get
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests for [AwsConnections] shared resource.
 *
 * These tests verify:
 * - Proper instantiation via SharedResources pattern
 * - Health status calculation at various utilization levels
 * - Client availability
 * - OpenTelemetry configuration handling
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
    fun `test health status OK when utilization below 70 percent`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // Set up for 50% utilization
        connections.total = 100
        connections.used = 50

        val health = connections.health
        assertEquals(HealthStatus.Level.OK, health.level)
        assertNull(health.additionalMessage)
    }

    @Test
    fun `test health status OK at 0 percent utilization`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        connections.total = 100
        connections.used = 0

        val health = connections.health
        assertEquals(HealthStatus.Level.OK, health.level)
    }

    @Test
    fun `test health status OK at 69 percent utilization`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        connections.total = 100
        connections.used = 69

        val health = connections.health
        assertEquals(HealthStatus.Level.OK, health.level)
    }

    @Test
    fun `test health status WARNING when utilization between 70 and 95 percent`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // Set up for 80% utilization
        connections.total = 100
        connections.used = 80

        val health = connections.health
        assertEquals(HealthStatus.Level.WARNING, health.level)
        assertNotNull(health.additionalMessage)
        assert(health.additionalMessage!!.contains("80%"))
    }

    @Test
    fun `test health status WARNING at 70 percent boundary`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        connections.total = 100
        connections.used = 70

        val health = connections.health
        assertEquals(HealthStatus.Level.WARNING, health.level)
        assertNotNull(health.additionalMessage)
        assert(health.additionalMessage!!.contains("70%"))
    }

    @Test
    fun `test health status WARNING at 94 percent`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        connections.total = 100
        connections.used = 94

        val health = connections.health
        assertEquals(HealthStatus.Level.WARNING, health.level)
        assertNotNull(health.additionalMessage)
        assert(health.additionalMessage!!.contains("94%"))
    }

    @Test
    fun `test health status URGENT when utilization between 95 and 100 percent`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // Set up for 97% utilization
        connections.total = 100
        connections.used = 97

        val health = connections.health
        assertEquals(HealthStatus.Level.URGENT, health.level)
        assertNotNull(health.additionalMessage)
        assert(health.additionalMessage!!.contains("97%"))
    }

    @Test
    fun `test health status URGENT at 95 percent boundary`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        connections.total = 100
        connections.used = 95

        val health = connections.health
        assertEquals(HealthStatus.Level.URGENT, health.level)
        assertNotNull(health.additionalMessage)
        assert(health.additionalMessage!!.contains("95%"))
    }

    @Test
    fun `test health status URGENT at 99 percent`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        connections.total = 100
        connections.used = 99

        val health = connections.health
        assertEquals(HealthStatus.Level.URGENT, health.level)
    }

    @Test
    fun `test health status ERROR when utilization at or above 100 percent`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // Set up for 100% utilization (at boundary)
        connections.total = 100
        connections.used = 100

        val health = connections.health
        assertEquals(HealthStatus.Level.ERROR, health.level)
        assertNotNull(health.additionalMessage)
        assert(health.additionalMessage!!.contains("100%"))
    }

    @Test
    fun `test health status ERROR when over-subscribed`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // Set up for 150% utilization (over-subscribed)
        connections.total = 100
        connections.used = 150

        val health = connections.health
        assertEquals(HealthStatus.Level.ERROR, health.level)
        assertNotNull(health.additionalMessage)
        assert(health.additionalMessage!!.contains("150%"))
    }

    @Test
    fun `test default total is MAX_VALUE`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        assertEquals(Int.MAX_VALUE, connections.total)
    }

    @Test
    fun `test default used is 0`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        assertEquals(0, connections.used)
    }

    @Test
    fun `test health is OK with default values`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // With default values (used=0, total=MAX_VALUE), utilization is ~0%
        val health = connections.health
        assertEquals(HealthStatus.Level.OK, health.level)
    }

    @Test
    fun `test clientOverrideConfiguration is not null`() {
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        assertNotNull(connections.clientOverrideConfiguration)
    }

    @Test
    fun `test clientOverrideConfiguration without OpenTelemetry`() {
        // TestSettingContext has openTelemetry = null
        val context = TestSettingContext()
        val connections = context[AwsConnections]

        // Configuration should still be created, just without telemetry interceptor
        assertNotNull(connections.clientOverrideConfiguration)
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
