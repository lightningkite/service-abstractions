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
import kotlin.collections.iterator

public fun LoggingSettings.applyToLogback() {
    val logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    logCtx.getLogger(Logger.ROOT_LOGGER_NAME).detachAndStopAllAppenders()
    default?.apply(Logger.ROOT_LOGGER_NAME, logCtx.getLogger(Logger.ROOT_LOGGER_NAME))
    for (sub in (logger ?: mapOf())) {
        sub.value.apply(sub.key, logCtx.getLogger(sub.key))
    }
}


private fun LoggingSettings.ContextSettings.apply(partName: String, to: Logger) {
    to.isAdditive = additive
    to.level = Level.toLevel(level.toInt())
    if (filePattern?.isNotBlank() == true) {
        to.addAppender(RollingFileAppender<ILoggingEvent>().apply rolling@{
            context = LoggerFactory.getILoggerFactory() as LoggerContext
            name = partName
            encoder = PatternLayoutEncoder().apply {
                context = LoggerFactory.getILoggerFactory() as LoggerContext
                pattern = "%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n"
                start()
            }
            isAppend = true
            rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
                context = LoggerFactory.getILoggerFactory() as LoggerContext
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
            context = LoggerFactory.getILoggerFactory() as LoggerContext
            name = partName + "Console"
            encoder = PatternLayoutEncoder().apply {
                context = LoggerFactory.getILoggerFactory() as LoggerContext
                pattern = "%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n"
                start()
            }
            start()
        })
    }
}
