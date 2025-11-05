package com.lightningkite.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.Level
import kotlinx.serialization.Serializable

/**
 * Configuration for application logging behavior.
 *
 * `LoggingSettings` provides fine-grained control over where logs go and what gets logged.
 * You can configure:
 * - Default logging for all packages
 * - Per-package logging overrides
 * - Log file patterns and rotation
 * - Console output
 * - Log levels (TRACE, DEBUG, INFO, WARN, ERROR)
 *
 * ## Logging Backend
 *
 * Uses [kotlin-logging](https://github.com/oshai/kotlin-logging) which wraps:
 * - JVM: SLF4J (typically with Logback)
 * - JS/Native: Simple console logging
 *
 * On JVM, file patterns use Logback's pattern syntax. See:
 * https://logback.qos.ch/manual/appenders.html#FileAppender
 *
 * ## Usage Examples
 *
 * Default configuration (INFO level to console and daily rotating files):
 * ```kotlin
 * val settings = LoggingSettings()
 * ```
 *
 * Custom configuration with package-specific logging:
 * ```kotlin
 * val settings = LoggingSettings(
 *     default = ContextSettings(
 *         filePattern = "logs/app-%d{yyyy-MM-dd}.log",
 *         toConsole = true,
 *         level = Level.INFO
 *     ),
 *     logger = mapOf(
 *         // Debug logging for database operations
 *         "com.example.database" to ContextSettings(
 *             filePattern = "logs/database-%d{yyyy-MM-dd}.log",
 *             toConsole = false,
 *             level = Level.DEBUG,
 *             additive = false // Don't also log to default
 *         ),
 *         // Error-only logging for noisy library
 *         "io.ktor.client" to ContextSettings(
 *             level = Level.ERROR,
 *             additive = true // Still log to default too
 *         )
 *     )
 * )
 * ```
 *
 * @property default Default logging configuration for all packages not explicitly configured
 * @property logger Per-package logging overrides keyed by package name
 */
@Serializable
public data class LoggingSettings(
    val default: ContextSettings? = ContextSettings(null, true, Level.INFO, false),
    val logger: Map<String, ContextSettings>? = null
) {
    /**
     * Logging configuration for a specific context (default or package-specific).
     *
     * @property filePattern Logback file pattern for log files, or null to disable file logging.
     *                       Supports date patterns like `%d{yyyy-MM-dd}` for rotation.
     *                       Example: "logs/app-%d{yyyy-MM-dd}.log" creates daily files.
     * @property toConsole Whether to write logs to console/stdout
     * @property level Minimum log level to capture (TRACE, DEBUG, INFO, WARN, ERROR)
     * @property additive If true, logs also go to parent logger (typically [default]).
     *                    If false, only this logger handles the logs.
     *                    Useful to prevent duplicate log entries when using custom loggers.
     */
    @Serializable
    public data class ContextSettings(
        val filePattern: String? = "local/logs/logfile-%d{yyyy-MM-dd}.log",
        val toConsole: Boolean = false,
        val level: Level = Level.INFO,
        val additive: Boolean = false,
    )
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding structured logging support:
 *    - Properties for JSON output format
 *    - MDC (Mapped Diagnostic Context) configuration
 *    - Would enable better log parsing in centralized logging systems
 *
 * 2. Consider adding log sampling/rate limiting:
 *    - Sample only N% of DEBUG logs in production
 *    - Rate limit noisy error logs
 *    - Would reduce log volume while preserving insight
 *
 * 3. Consider validation:
 *    - Validate filePattern syntax before applying
 *    - Check for common misconfiguration (e.g., overlapping file patterns)
 *    - Provide helpful error messages for invalid settings
 */
