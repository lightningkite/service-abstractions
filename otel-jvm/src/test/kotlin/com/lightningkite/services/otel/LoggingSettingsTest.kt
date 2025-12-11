package com.lightningkite.services.otel

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.lightningkite.services.LoggingSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.junit.Assert.*
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for [LoggingSettings] to verify that log level filtering works correctly.
 *
 * These tests ensure that:
 * - Log levels are properly applied (DEBUG logs hidden when level=INFO, etc.)
 * - Per-package overrides work correctly
 * - Console and file appenders are configured as expected
 */
class LoggingSettingsTest {

    private lateinit var listAppender: ListAppender<ILoggingEvent>

    @BeforeTest
    fun setup() {
        // Create a list appender to capture log events for verification
        listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
    }

    @AfterTest
    fun teardown() {
        listAppender.stop()
        listAppender.list.clear()
    }

    /**
     * Helper to attach our list appender to the root logger to capture all output
     */
    private fun attachToRoot() {
        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.addAppender(listAppender)
    }

    @Test
    fun `INFO level should filter out DEBUG and TRACE logs`() {
        // Configure logging at INFO level
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.INFO,
                additive = false
            )
        ).applyToLogback()

        attachToRoot()

        val logger = KotlinLogging.logger("test.info.filter")

        // Log at various levels
        logger.trace { "TRACE message - should be filtered" }
        logger.debug { "DEBUG message - should be filtered" }
        logger.info { "INFO message - should appear" }
        logger.warn { "WARN message - should appear" }
        logger.error { "ERROR message - should appear" }

        // Verify only INFO and above were captured
        val messages = listAppender.list.map { it.message }

        assertFalse("TRACE should be filtered at INFO level", messages.any { it.contains("TRACE") })
        assertFalse("DEBUG should be filtered at INFO level", messages.any { it.contains("DEBUG") })
        assertTrue("INFO should pass at INFO level", messages.any { it.contains("INFO message") })
        assertTrue("WARN should pass at INFO level", messages.any { it.contains("WARN message") })
        assertTrue("ERROR should pass at INFO level", messages.any { it.contains("ERROR message") })

        assertEquals("Should have exactly 3 log messages", 3, listAppender.list.size)
    }

    @Test
    fun `DEBUG level should allow DEBUG logs but filter TRACE`() {
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.DEBUG,
                additive = false
            )
        ).applyToLogback()

        attachToRoot()

        val logger = KotlinLogging.logger("test.debug.filter")

        logger.trace { "TRACE message - should be filtered" }
        logger.debug { "DEBUG message - should appear" }
        logger.info { "INFO message - should appear" }

        val messages = listAppender.list.map { it.message }

        assertFalse("TRACE should be filtered at DEBUG level", messages.any { it.contains("TRACE") })
        assertTrue("DEBUG should pass at DEBUG level", messages.any { it.contains("DEBUG message") })
        assertTrue("INFO should pass at DEBUG level", messages.any { it.contains("INFO message") })

        assertEquals("Should have exactly 2 log messages", 2, listAppender.list.size)
    }

    @Test
    fun `WARN level should filter INFO and below`() {
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.WARN,
                additive = false
            )
        ).applyToLogback()

        attachToRoot()

        val logger = KotlinLogging.logger("test.warn.filter")

        logger.debug { "DEBUG message - should be filtered" }
        logger.info { "INFO message - should be filtered" }
        logger.warn { "WARN message - should appear" }
        logger.error { "ERROR message - should appear" }

        val messages = listAppender.list.map { it.message }

        assertFalse("DEBUG should be filtered at WARN level", messages.any { it.contains("DEBUG") })
        assertFalse("INFO should be filtered at WARN level", messages.any { it.contains("INFO") })
        assertTrue("WARN should pass at WARN level", messages.any { it.contains("WARN message") })
        assertTrue("ERROR should pass at WARN level", messages.any { it.contains("ERROR message") })

        assertEquals("Should have exactly 2 log messages", 2, listAppender.list.size)
    }

    @Test
    fun `ERROR level should filter everything except ERROR`() {
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.ERROR,
                additive = false
            )
        ).applyToLogback()

        attachToRoot()

        val logger = KotlinLogging.logger("test.error.filter")

        logger.debug { "DEBUG message - should be filtered" }
        logger.info { "INFO message - should be filtered" }
        logger.warn { "WARN message - should be filtered" }
        logger.error { "ERROR message - should appear" }

        val messages = listAppender.list.map { it.message }

        assertFalse("DEBUG should be filtered at ERROR level", messages.any { it.contains("DEBUG") })
        assertFalse("INFO should be filtered at ERROR level", messages.any { it.contains("INFO") })
        assertFalse("WARN should be filtered at ERROR level", messages.any { it.contains("WARN message") })
        assertTrue("ERROR should pass at ERROR level", messages.any { it.contains("ERROR message") })

        assertEquals("Should have exactly 1 log message", 1, listAppender.list.size)
    }

    @Test
    fun `TRACE level should allow all logs`() {
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.TRACE,
                additive = false
            )
        ).applyToLogback()

        attachToRoot()

        val logger = KotlinLogging.logger("test.trace.filter")

        logger.trace { "TRACE message - should appear" }
        logger.debug { "DEBUG message - should appear" }
        logger.info { "INFO message - should appear" }
        logger.warn { "WARN message - should appear" }
        logger.error { "ERROR message - should appear" }

        assertEquals("Should have all 5 log messages at TRACE level", 5, listAppender.list.size)
    }

    @Test
    fun `per-package override should apply different level than default`() {
        val specialPackage = "com.special.verbose"
        val normalPackage = "com.normal.quiet"

        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.WARN,  // Default: only WARN and ERROR
                additive = false
            ),
            logger = mapOf(
                specialPackage to LoggingSettings.ContextSettings(
                    filePattern = null,
                    toConsole = false,
                    level = Level.DEBUG,  // Override: allow DEBUG
                    additive = true
                )
            )
        ).applyToLogback()

        attachToRoot()

        val specialLogger = KotlinLogging.logger(specialPackage)
        val normalLogger = KotlinLogging.logger(normalPackage)

        // Log DEBUG from both
        specialLogger.debug { "Special DEBUG - should appear" }
        normalLogger.debug { "Normal DEBUG - should be filtered" }

        // Log WARN from both
        specialLogger.warn { "Special WARN - should appear" }
        normalLogger.warn { "Normal WARN - should appear" }

        val messages = listAppender.list.map { it.message }

        assertTrue("Special package DEBUG should pass", messages.any { it.contains("Special DEBUG") })
        assertFalse("Normal package DEBUG should be filtered", messages.any { it.contains("Normal DEBUG") })
        assertTrue("Special package WARN should pass", messages.any { it.contains("Special WARN") })
        assertTrue("Normal package WARN should pass", messages.any { it.contains("Normal WARN") })

        assertEquals("Should have exactly 3 log messages", 3, listAppender.list.size)
    }

    @Test
    fun `child logger inherits parent package override`() {
        val parentPackage = "com.parent"
        val childLoggerName = "com.parent.child.deep"

        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.ERROR,  // Default: only ERROR
                additive = false
            ),
            logger = mapOf(
                parentPackage to LoggingSettings.ContextSettings(
                    filePattern = null,
                    toConsole = false,
                    level = Level.DEBUG,  // Parent override: allow DEBUG
                    additive = true  // Allow propagation
                )
            )
        ).applyToLogback()

        attachToRoot()

        val logger = KotlinLogging.logger(childLoggerName)

        logger.debug { "Child DEBUG - should inherit parent's DEBUG level" }
        logger.info { "Child INFO - should appear" }

        val messages = listAppender.list.map { it.message }

        assertTrue("Child should inherit parent's DEBUG level", messages.any { it.contains("Child DEBUG") })
        assertTrue("Child INFO should appear", messages.any { it.contains("Child INFO") })
    }

    @Test
    fun `console appender is added when toConsole is true`() {
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = true,
                level = Level.INFO,
                additive = false
            )
        ).applyToLogback()

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val consoleAppenders = rootLogger.iteratorForAppenders().asSequence()
            .filter { it.name?.contains("Console") == true }
            .toList()

        assertTrue("Should have a console appender when toConsole=true", consoleAppenders.isNotEmpty())
    }

    @Test
    fun `no console appender when toConsole is false`() {
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.INFO,
                additive = false
            )
        ).applyToLogback()

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val consoleAppenders = rootLogger.iteratorForAppenders().asSequence()
            .filter { it.name?.contains("Console") == true }
            .toList()

        assertTrue("Should have no console appender when toConsole=false", consoleAppenders.isEmpty())
    }

    @Test
    fun `default LoggingSettings uses INFO level`() {
        val defaultSettings = LoggingSettings()

        assertEquals(
            "Default level should be INFO",
            Level.INFO,
            defaultSettings.default?.level
        )
    }

    @Test
    fun `logger level is correctly set in logback`() {
        // Verify the level is actually set on the logback Logger object
        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.WARN,
                additive = false
            )
        ).applyToLogback()

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        assertEquals(
            "Root logger should be set to WARN level",
            ch.qos.logback.classic.Level.WARN,
            rootLogger.level
        )
    }

    @Test
    fun `package-specific logger level is correctly set`() {
        val packageName = "com.test.specific"

        LoggingSettings(
            default = LoggingSettings.ContextSettings(
                filePattern = null,
                toConsole = false,
                level = Level.ERROR,
                additive = false
            ),
            logger = mapOf(
                packageName to LoggingSettings.ContextSettings(
                    filePattern = null,
                    toConsole = false,
                    level = Level.DEBUG,
                    additive = false
                )
            )
        ).applyToLogback()

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val specificLogger = LoggerFactory.getLogger(packageName) as Logger

        assertEquals(
            "Root logger should be ERROR",
            ch.qos.logback.classic.Level.ERROR,
            rootLogger.level
        )
        assertEquals(
            "Specific logger should be DEBUG",
            ch.qos.logback.classic.Level.DEBUG,
            specificLogger.level
        )
    }
}
