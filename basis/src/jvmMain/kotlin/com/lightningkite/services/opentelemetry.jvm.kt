package com.lightningkite.services

import io.opentelemetry.api.OpenTelemetry

/**
 * JVM actual implementation of [OpenTelemetry] - uses the official OpenTelemetry SDK.
 *
 * On JVM, this is a typealias to `io.opentelemetry.api.OpenTelemetry` from the
 * OpenTelemetry Java SDK. This provides full-featured distributed tracing, metrics,
 * and logging capabilities.
 *
 * See [OpenTelemetry Java documentation](https://opentelemetry.io/docs/languages/java/)
 * for detailed usage information.
 */
public actual typealias OpenTelemetry = OpenTelemetry