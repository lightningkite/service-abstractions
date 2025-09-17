package com.lightningkite.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.Level
import kotlinx.serialization.Serializable

/**
 * LoggingSettings configures what the logging of the server should look like.
 * This has a lot of customizability though it can be complicated.
 * You can tell it what files to log to, if logs get printed to the console, and what packages you want to log.
 * This uses ch.qos.logback:logback-classic, so you can reference its docs for custom file patterns.
 *
 * @param default will log everything from all packages unless specified otherwise.
 * @param logger is where you can be more specific logging for certain packages. Additive will state if default should also log that package still.
 */
@Serializable
public data class LoggingSettings(
    val default: ContextSettings? = ContextSettings(null, true, Level.INFO, false),
    val logger: Map<String, ContextSettings>? = null
) {
    @Serializable
    public data class ContextSettings(
        val filePattern: String? = "local/logs/logfile-%d{yyyy-MM-dd}.log",
        val toConsole: Boolean = false,
        val level: Level = Level.INFO,
        val additive: Boolean = false,
    ) {
    }
}
