# OpenTelemetry Cost Mitigation Guide

This document describes the cost mitigation features added to the `otel-jvm` module to prevent excessive infrastructure charges from OpenTelemetry data transmission.

## Problem Statement

OpenTelemetry can generate massive amounts of data in production environments:
- Large stack traces from exceptions
- High-frequency spans in busy applications
- Verbose log messages
- Error bursts during incidents

Without proper limits, this can lead to:
- Excessive network bandwidth usage
- High costs from telemetry backends (DataDog, New Relic, Honeycomb, etc.)
- Memory exhaustion from unbounded queues
- Performance degradation

## Solutions Implemented

### 1. Batch Processor Limits

**Configuration:**
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    maxQueueSize = 2048,              // Max items queued before dropping
    maxExportBatchSize = 512,         // Max items per export batch
    exportTimeoutMillis = 30_000      // Export timeout (30 seconds)
)
```

**Benefits:**
- Prevents unbounded memory growth
- Fails fast when backend is slow or unavailable
- Provides backpressure when system is overloaded

**Default values:**
- `maxQueueSize`: 2048 items
- `maxExportBatchSize`: 512 items
- `exportTimeoutMillis`: 30000 ms (30 seconds)

### 2. Payload Size Limits

**Configuration:**
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    maxAttributeLength = 1024,        // Max chars per attribute value
    maxSpanAttributeCount = 128,      // Max attributes per span
    maxSpanEventCount = 128,          // Max events per span
    maxSpanLinkCount = 128,           // Max links per span
    maxLogBodyLength = 8192           // Max log message size (8KB)
)
```

**Benefits:**
- Prevents individual items from being excessively large
- Truncates long attribute values automatically
- Limits nested complexity

**Default values:**
- `maxAttributeLength`: 1024 characters
- `maxSpanAttributeCount`: 128 attributes
- `maxSpanEventCount`: 128 events
- `maxSpanLinkCount`: 128 links
- `maxLogBodyLength`: 8192 characters (8KB)

### 3. Sampling

**Configuration:**
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    traceSamplingRatio = 0.05,        // Sample 5% of traces
    parentBasedSampling = true        // Respect parent sampling decisions
)
```

**Benefits:**
- Dramatically reduces data volume (95% reduction at 5% sampling)
- Maintains statistical validity for metrics
- Can use parent-based sampling to keep full traces

**Default values:**
- `traceSamplingRatio`: 1.0 (100% - no sampling)
- `parentBasedSampling`: true

**How sampling works:**
- `traceSamplingRatio = 1.0`: Export 100% of traces (no sampling)
- `traceSamplingRatio = 0.1`: Export 10% of traces (90% cost reduction)
- `traceSamplingRatio = 0.01`: Export 1% of traces (99% cost reduction)
- `parentBasedSampling = true`: Child spans inherit parent's sampling decision (keeps traces complete)

### 4. Rate Limiting

**Configuration:**
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    maxSpansPerSecond = 1000,         // Max 1000 spans/sec
    maxLogsPerSecond = 5000           // Max 5000 logs/sec
)
```

**Benefits:**
- Prevents cost spikes during incidents
- Limits damage from runaway error loops
- Token bucket algorithm allows short bursts

**Default values:**
- `maxSpansPerSecond`: null (unlimited)
- `maxLogsPerSecond`: null (unlimited)

**How rate limiting works:**
- Uses token bucket algorithm with 1-second refill
- Allows bursts up to the configured limit
- Drops excess items and logs a warning
- Does not block or delay execution

### 5. Log Truncation (SafeLogRecordExporter)

Automatically applied to all log exports. Monitors log body sizes and warns when they exceed `maxLogBodyLength`.

**Note:** Full truncation of log bodies is complex due to OpenTelemetry's immutable data structures. The current implementation focuses on monitoring and relies on attribute limits for actual truncation.

## Recommended Configurations

### Development Environment
```kotlin
OpenTelemetrySettings(
    url = "console",                  // Log to console
    traceSamplingRatio = 1.0,         // 100% sampling
    reportFrequency = null            // Immediate export
)
```

### Staging Environment
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    traceSamplingRatio = 0.25,        // 25% sampling
    maxQueueSize = 2048,
    maxExportBatchSize = 512,
    maxLogBodyLength = 8192
)
```

### Production - Low Cost
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    traceSamplingRatio = 0.05,        // 5% sampling (95% cost reduction)
    maxQueueSize = 1024,
    maxExportBatchSize = 256,
    maxLogBodyLength = 4096,          // 4KB limit
    maxStackTraceDepth = 30,
    maxSpansPerSecond = 1000,
    maxLogsPerSecond = 5000
)
```

### Production - High Fidelity
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    traceSamplingRatio = 0.5,         // 50% sampling (50% cost reduction)
    maxQueueSize = 4096,
    maxExportBatchSize = 512,
    maxLogBodyLength = 16384,         // 16KB limit
    maxSpansPerSecond = 5000,
    maxLogsPerSecond = 10000
)
```

### Emergency - Incident Mode
```kotlin
OpenTelemetrySettings(
    url = "otlp-http://collector:4318",
    traceSamplingRatio = 0.01,        // 1% sampling (99% cost reduction)
    maxQueueSize = 512,
    maxExportBatchSize = 128,
    maxLogBodyLength = 1024,          // 1KB limit
    maxSpansPerSecond = 100,          // Very aggressive rate limiting
    maxLogsPerSecond = 500
)
```

## Monitoring

### Rate Limit Warnings

When rate limits are exceeded, warnings are logged:
```
WARNING: Rate limit exceeded: dropped 42 spans
WARNING: Rate limit exceeded: dropped 123 log records
```

These warnings use `java.util.logging.Logger` and appear in your application logs.

### Queue Overflow

When `maxQueueSize` is exceeded, OpenTelemetry's BatchSpanProcessor will drop items. This is logged by OpenTelemetry itself.

### Sampling

Sampled-out spans are silently dropped (not exported). To verify sampling is working:
1. Generate known number of spans
2. Check exported count in your telemetry backend
3. Verify ratio matches `traceSamplingRatio`

## Cost Estimation

Example cost reduction with different sampling ratios:

| Sampling Ratio | Traces Exported | Cost Reduction |
|----------------|----------------|----------------|
| 1.0 (default)  | 100%           | 0%             |
| 0.5            | 50%            | 50%            |
| 0.1            | 10%            | 90%            |
| 0.05           | 5%             | 95%            |
| 0.01           | 1%             | 99%            |

**Additional reductions from:**
- Payload size limits: 10-30% reduction (depends on attribute verbosity)
- Rate limiting: Prevents unbounded spikes (protects against incidents)
- Batch limits: Prevents memory exhaustion (improves reliability)

## Implementation Details

### Rate Limiting Algorithm

Uses token bucket with semaphores:
- Permits are granted at configured rate per second
- Permits refill every second
- No blocking - excess items are immediately dropped
- Scheduler thread refills permits continuously

### Sampling Algorithm

Uses OpenTelemetry's built-in samplers:
- `Sampler.traceIdRatioBased(ratio)`: Consistent sampling based on trace ID
- `Sampler.parentBasedBuilder()`: Respects parent sampling decisions
- Sampling decision made at span creation time
- Sampled-out spans are never exported

### Span Limits

Applied via `SpanLimits.builder()`:
- Attribute values truncated at `maxAttributeLength`
- Extra attributes dropped after `maxSpanAttributeCount`
- Extra events dropped after `maxSpanEventCount`
- Extra links dropped after `maxSpanLinkCount`

## Backward Compatibility

All new configuration parameters have sensible defaults that maintain current behavior:
- Sampling defaults to 1.0 (100% - no sampling)
- Rate limits default to null (unlimited)
- Payload limits have generous defaults
- Existing configurations continue to work unchanged

## Testing

Comprehensive tests added in `OpenTelemetrySettingsTest.kt`:
- `testWithLimits()`: Tests all limits work together
- `testLogTruncation()`: Verifies log body length monitoring
- `testRateLimiting()`: Verifies rate limiter drops excess items
- `testSamplingConfiguration()`: Verifies sampling reduces span count
- `testBatchProcessorLimits()`: Verifies queue overflow handling

Run tests with:
```bash
./gradlew :otel-jvm:test
```

## Future Enhancements

Potential improvements for future versions:
1. Dynamic sampling based on error rates
2. Metric-based sampling (sample more when errors occur)
3. Content-aware truncation (preserve important log context)
4. Compression before export
5. Local aggregation to reduce item count
6. Adaptive rate limiting based on backend health

## Questions?

If you encounter issues or have questions about these cost mitigation features:
1. Check application logs for rate limit warnings
2. Verify configuration values match your needs
3. Start with conservative limits and gradually increase
4. Monitor your telemetry backend costs over time
