package com.lightningkite.services

import kotlin.test.*

/**
 * Tests for UrlSettingParser functionality.
 */
class UrlSettingParserTest {

    private interface TestService {
        val type: String
    }

    private class RamTestService : TestService {
        override val type = "ram"
    }

    private class RedisTestService(val url: String) : TestService {
        override val type = "redis"
    }

    // Use a function to create fresh parser for each test
    private fun createTestParser(): UrlSettingParser<TestService> {
        val parser = object : UrlSettingParser<TestService>() {}
        parser.register("ram") { name, url, context -> RamTestService() }
        parser.register("redis") { name, url, context -> RedisTestService(url) }
        return parser
    }

    @Test
    fun testParseRecognizedScheme() {
        val parser = createTestParser()
        val context = TestSettingContext()
        val service = parser.parse("test", "ram://", context)

        assertTrue(service is RamTestService)
        assertEquals("ram", service.type)
    }

    @Test
    fun testParseWithFullUrl() {
        val parser = createTestParser()
        val context = TestSettingContext()
        val service = parser.parse("test", "redis://localhost:6379/0", context) as RedisTestService

        assertEquals("redis", service.type)
        assertEquals("redis://localhost:6379/0", service.url)
    }

    @Test
    fun testSupportsRecognizedScheme() {
        val parser = createTestParser()
        assertTrue(parser.supports("ram"))
        assertTrue(parser.supports("redis"))
    }

    @Test
    fun testDoesNotSupportUnrecognizedScheme() {
        val parser = createTestParser()
        assertFalse(parser.supports("mongodb"))
        assertFalse(parser.supports("unknown"))
    }

    @Test
    fun testParseThrowsOnUnrecognizedScheme() {
        val parser = createTestParser()
        val context = TestSettingContext()

        val exception = assertFailsWith<IllegalArgumentException> {
            parser.parse("test", "unknown://localhost", context)
        }

        assertTrue(exception.message!!.contains("No handler unknown"))
        assertTrue(exception.message!!.contains("available handlers"))
    }

    @Test
    fun testOptionsReturnsRegisteredSchemes() {
        val parser = createTestParser()
        val options = parser.options

        assertTrue(options.contains("ram"))
        assertTrue(options.contains("redis"))
        assertEquals(2, options.size)
    }

    @Test
    fun testRegisterDuplicateThrowsError() {
        val parser = object : UrlSettingParser<String>() {}

        parser.register("test") { _, _, _ -> "first" }

        val exception = assertFailsWith<Error> {
            parser.register("test") { _, _, _ -> "second" }
        }

        assertTrue(exception.message!!.contains("already registered"))
        assertTrue(exception.message!!.contains("hostile library"))
    }
}
