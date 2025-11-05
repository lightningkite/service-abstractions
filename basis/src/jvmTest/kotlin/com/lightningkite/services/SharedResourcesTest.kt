package com.lightningkite.services

import kotlin.test.*

/**
 * Tests for SharedResources functionality.
 */
class SharedResourcesTest {

    private class TestResource(val value: String)

    private object TestResourceKey : SharedResources.Key<TestResource> {
        var setupCallCount = 0

        override fun setup(context: SettingContext): TestResource {
            setupCallCount++
            return TestResource("test-value-$setupCallCount")
        }
    }

    @BeforeTest
    fun resetCounter() {
        TestResourceKey.setupCallCount = 0
    }

    @Test
    fun testResourceCreatedOnFirstAccess() {
        val resources = SharedResources()
        val context = TestSettingContext()

        assertEquals(0, TestResourceKey.setupCallCount)

        val resource = resources.get(TestResourceKey, context)

        assertEquals(1, TestResourceKey.setupCallCount)
        assertEquals("test-value-1", resource.value)
    }

    @Test
    fun testResourceCachedAfterCreation() {
        val resources = SharedResources()
        val context = TestSettingContext()

        val resource1 = resources.get(TestResourceKey, context)
        val resource2 = resources.get(TestResourceKey, context)

        // Should only call setup once
        assertEquals(1, TestResourceKey.setupCallCount)
        // Should return same instance
        assertSame(resource1, resource2)
    }

    @Test
    fun testMultipleResourceTypes() {
        val resources = SharedResources()
        val context = TestSettingContext()

        val key1 = object : SharedResources.Key<String> {
            override fun setup(context: SettingContext) = "value1"
        }

        val key2 = object : SharedResources.Key<Int> {
            override fun setup(context: SettingContext) = 42
        }

        val value1 = resources.get(key1, context)
        val value2 = resources.get(key2, context)

        assertEquals("value1", value1)
        assertEquals(42, value2)
    }

    @Test
    fun testContextOperatorOverload() {
        val context = TestSettingContext()

        val resource1 = context[TestResourceKey]
        val resource2 = context[TestResourceKey]

        // Operator should work same as get()
        assertEquals(1, TestResourceKey.setupCallCount)
        assertSame(resource1, resource2)
    }
}
