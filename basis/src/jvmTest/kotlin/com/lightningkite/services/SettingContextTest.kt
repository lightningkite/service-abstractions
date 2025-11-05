package com.lightningkite.services

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.*
import kotlin.time.Clock

/**
 * Tests for SettingContext and related functionality.
 */
class SettingContextTest {

    @Test
    fun testTestSettingContextDefaults() {
        val context = TestSettingContext()

        assertEquals("Test", context.projectName)
        assertEquals("http://localhost:8080", context.publicUrl)
        assertNull(context.openTelemetry)
        assertNotNull(context.sharedResources)
        assertEquals(EmptySerializersModule(), context.internalSerializersModule)
    }

    @Test
    fun testTestSettingContextWithCustomClock() {
        val fixedInstant = kotlinx.datetime.Instant.parse("2025-01-01T00:00:00Z")
        val customClock = object : Clock {
            override fun now() = fixedInstant
        }
        val context = TestSettingContext(clock = customClock)

        assertEquals(customClock, context.clock)
        assertEquals(customClock.now(), context.clock.now())
    }

    @Test
    fun testTestSettingContextWithSerializersModule() {
        val module = SerializersModule {
            // Empty module for test
        }
        val context = TestSettingContext(internalSerializersModule = module)

        assertEquals(module, context.internalSerializersModule)
    }

    @Test
    fun testSharedResourcesIsolation() {
        val context1 = TestSettingContext()
        val context2 = TestSettingContext()

        // Each context should have its own SharedResources instance
        assertNotSame(context1.sharedResources, context2.sharedResources)
    }
}
