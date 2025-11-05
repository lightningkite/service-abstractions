package com.lightningkite.services

/**
 * Non-JVM actual implementation of [OpenTelemetry] - no-op marker interface.
 *
 * On JS, Native, and other non-JVM platforms, OpenTelemetry is not currently supported.
 * This empty interface allows the API to remain consistent across platforms while
 * providing no actual telemetry functionality.
 *
 * Services should check for null when accessing [SettingContext.openTelemetry] to
 * handle both missing configuration and unsupported platforms gracefully.
 */
public actual interface OpenTelemetry