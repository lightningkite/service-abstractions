package com.lightningkite.services.otel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import com.lightningkite.services.LoggingSettings
import org.slf4j.LoggerFactory

public fun LoggingSettings.applyToLogback() {
    val logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    logCtx.getLogger(Logger.ROOT_LOGGER_NAME).apply {
        detachAndStopAllAppenders()
        default?.apply(Logger.ROOT_LOGGER_NAME, this)
    }
    for (sub in (logger ?: mapOf())) {
        sub.value.apply(sub.key, logCtx.getLogger(sub.key), logCtx)
    }
}


private fun LoggingSettings.ContextSettings.apply(
    partName: String,
    to: Logger,
    logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext,
) {
    to.isAdditive = additive
    // Convert by name since kotlin-logging Level.toInt() values don't match Logback's integer values
    to.level = Level.toLevel(level.name)
    if (filePattern?.isNotBlank() == true) {
        to.addAppender(RollingFileAppender<ILoggingEvent>().apply rolling@{
            context = logCtx
            name = partName
            encoder = PatternLayoutEncoder().apply {
                context = logCtx
                pattern = "%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n"
                start()
            }
            isAppend = true
            rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
                context = logCtx
                setParent(this@rolling);
                fileNamePattern = filePattern;
                maxHistory = 7;
                start();
            }
            start()
        })
    }
    if (toConsole) {
        to.addAppender(ConsoleAppender<ILoggingEvent>().apply {
            context = logCtx
            name = partName + "Console"
            encoder = PatternLayoutEncoder().apply {
                context = logCtx
                pattern = "%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n"
                start()
            }
            start()
        })
    }
}
