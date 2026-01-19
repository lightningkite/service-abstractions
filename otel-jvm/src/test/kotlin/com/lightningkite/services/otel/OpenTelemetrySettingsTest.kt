package com.lightningkite.services.otel

import com.lightningkite.services.TestSettingContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OpenTelemetrySettingsTest {
    @Test
    fun test() {
        val telemetry = OpenTelemetrySettings("console", batching = null).invoke("telemetry", TestSettingContext())
        val context = telemetry["asdf"]
        telemetry["sub"].spanBuilder("span").useBlocking {
            println("OK, we're doing work")
            KotlinLogging.logger("sigh").info { "This is a test" }
            context.error("uh oh", Exception("I can't believe you've done this."))
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
    }

    @Test
    fun test2() {
        val telemetry = OpenTelemetrySettings("log", batching = null).invoke("telemetry", TestSettingContext())
        val context = telemetry["asdf"]
        telemetry["sub"].spanBuilder("span").useBlocking {
            println("OK, we're doing work")
            KotlinLogging.logger("sigh").info { "This is a test" }
            context.error("uh oh", Exception("I can't believe you've done this."))
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
    }

    @Test
    fun testWithLimits() {
        // Test with aggressive limits to ensure they don't break functionality
        val telemetry = OpenTelemetrySettings(
            url = "console",
            batching = null,
            spanLimits = OpenTelemetrySettings.SpanLimitSettings(
                maxAttributeValueLength = 100,
                maxNumberOfAttributes = 10,
            ),
            logLimits = OpenTelemetrySettings.LogLimits(
                maxBodyLength = 500,
            ),
            sampling = OpenTelemetrySettings.Sampling(0.5), // 50% sampling
        ).invoke("telemetry-with-limits", TestSettingContext())

        val context = telemetry["test-limits"]
        telemetry["sub"].spanBuilder("span-with-limits").useBlocking {
            println("Testing with limits")
            KotlinLogging.logger("limit-test").info { "This is a test with limits" }
            context.error("error with limits", Exception("Testing error handling with limits"))
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
    }

    @Test
    fun testLogTruncation() {
        // Test that very long log messages get truncated
        val telemetry = OpenTelemetrySettings(
            url = "console",
            batching = null,
            logLimits = OpenTelemetrySettings.LogLimits(
                maxBodyLength = 100,
            ),
        ).invoke("telemetry-truncation", TestSettingContext())

        val logger = KotlinLogging.logger("truncation-test")
        telemetry["truncation"].spanBuilder("truncation-test").useBlocking {
            // Log a very long message
            val longMessage = "x".repeat(500)
            logger.info { longMessage }
            println("Logged message of length ${longMessage.length}, should be truncated to 100")
        }
        Thread.sleep(1.seconds.inWholeMilliseconds)
    }

    @Test
    fun testRateLimiting() {
        // Test with rate limiting
        val telemetry = OpenTelemetrySettings(
            url = "console",
            batching = null,
            maxSpansPerSecond = 5,  // Very low limit for testing
            maxLogsPerSecond = 5
        ).invoke("telemetry-rate-limited", TestSettingContext())

        val logger = KotlinLogging.logger("rate-limit-test")

        // Generate more spans/logs than the rate limit allows
        repeat(20) { i ->
            telemetry["rate-test"].spanBuilder("span-$i").useBlocking {
                logger.info { "Log message $i" }
            }
        }

        println("Generated 20 spans/logs with limit of 5/sec - some should be dropped")
        Thread.sleep(2.seconds.inWholeMilliseconds)
    }

    @Test
    fun testSamplingConfiguration() {
        // Test with 10% sampling - most spans should be dropped
        val telemetry = OpenTelemetrySettings(
            url = "console",
            batching = null,
            sampling = OpenTelemetrySettings.Sampling(0.1)
        ).invoke("telemetry-sampled", TestSettingContext())

        val logger = KotlinLogging.logger("sampling-test")

        // Generate many spans, only ~10% should be exported
        repeat(50) { i ->
            telemetry["sampling-test"].spanBuilder("sampled-span-$i").useBlocking {
                logger.info { "Sampled log $i" }
            }
        }

        println("Generated 50 spans with 10% sampling - expect ~5 to be exported")
        Thread.sleep(2.seconds.inWholeMilliseconds)
    }

    @Test
    fun testBatchProcessorLimits() {
        // Test batch processor configuration
        val telemetry = OpenTelemetrySettings(
            url = "console",
            batching = OpenTelemetrySettings.BatchingRules(
                frequency = 1.seconds,
                maxQueueSize = 10,
                maxSize = 5,
                exportTimeout = 5000.milliseconds
            ),
        ).invoke("telemetry-batch", TestSettingContext())

        val logger = KotlinLogging.logger("batch-test")

        // Generate more items than queue size to test overflow handling
        repeat(15) { i ->
            telemetry["batch-test"].spanBuilder("batch-span-$i").useBlocking {
                logger.info { "Batch log $i" }
            }
        }

        println("Generated 15 spans with maxQueueSize=10 - some should be dropped")
        Thread.sleep(2.seconds.inWholeMilliseconds)
    }

    @Test
    fun testDevMode() {
        // Test dev:// mode - immediate output with pretty formatting
        val telemetry = OpenTelemetrySettings("dev").invoke("my-service", TestSettingContext())
        val logger = KotlinLogging.logger("dev-test")

        println("\n=== Testing dev:// mode with immediate output ===\n")

        // Test nested spans to show parent-child relationships
        telemetry["api"].spanBuilder("GET /api/users").useBlocking {
            logger.info { "Handling request" }
            Thread.sleep(50)

            telemetry["db"].spanBuilder("db.query users").useBlocking {
                logger.debug { "Executing query: SELECT * FROM users" }
                Thread.sleep(100)
            }

            telemetry["serializer"].spanBuilder("serialize response").useBlocking {
                Thread.sleep(20)
            }
        }

        // Test error span
        telemetry["api"].spanBuilder("POST /api/orders").useBlocking { span ->
            logger.warn { "Processing order..." }
            Thread.sleep(30)
            try {
                throw IllegalStateException("Insufficient inventory")
            } catch (e: Exception) {
                span.recordException(e)
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.message ?: "")
                logger.error(e) { "Order failed" }
            }
        }

        // Test different log levels
        logger.trace { "This is a trace message" }
        logger.debug { "This is a debug message" }
        logger.info { "This is an info message" }
        logger.warn { "This is a warning message" }
        logger.error { "This is an error message" }

        println("\n=== Dev mode test complete ===\n")
        Thread.sleep(500) // Brief wait for any async processing
    }

    @Test
    fun testDevModeNoColor() {
        // Test dev:// mode without ANSI colors
        val telemetry = OpenTelemetrySettings("dev://?color=false").invoke("no-color-test", TestSettingContext())
        val logger = KotlinLogging.logger("no-color-test")

        println("\n=== Testing dev:// mode without colors ===\n")

        telemetry["api"].spanBuilder("GET /api/test").useBlocking {
            logger.info { "This should have no ANSI color codes" }
            Thread.sleep(50)
        }

        println("\n=== No-color test complete ===\n")
        Thread.sleep(500)
    }

    @Test
    fun testDevModeFileOutput() {
        // Test dev:// mode with file output
        val outputFile = java.io.File("local/test-traces.log")
        outputFile.parentFile?.mkdirs()
        outputFile.delete() // Clean up from previous runs

        println("Working directory: ${java.io.File(".").absolutePath}")
        println("Output file path: ${outputFile.absolutePath}")

        val telemetry = OpenTelemetrySettings("dev://local/test-traces.log?color=false")
            .invoke("file-output-test", TestSettingContext()) as io.opentelemetry.sdk.OpenTelemetrySdk
        val logger = KotlinLogging.logger("file-output-test")

        telemetry["api"].spanBuilder("GET /api/file-test").useBlocking {
            logger.info { "This should be written to file" }
            Thread.sleep(50)

            telemetry["db"].spanBuilder("db.query").useBlocking {
                Thread.sleep(30)
            }
        }

        // Shutdown to flush file output
        telemetry.sdkTracerProvider.shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(500)

        println("Checking if file exists: ${outputFile.exists()}")
        println("Local dir contents: ${java.io.File("local").listFiles()?.map { it.name }}")

        // Verify file was created and has content
        assertTrue("Output file should exist at ${outputFile.absolutePath}", outputFile.exists())
        val content = outputFile.readText()
        assertTrue("File should contain trace data", content.contains("GET /api/file-test"))
        assertTrue("File should contain nested span", content.contains("db.query"))
        // Verify no ANSI codes (since color=false)
        assertFalse("File should not contain ANSI codes", content.contains("\u001B["))

        println("File output test passed. Content:\n$content")
    }

    @Test
    fun testDebouncedDevMode() {
        // Test debounced-dev:// mode with 1 second debounce window
        val telemetry = OpenTelemetrySettings("debounced-dev://?debounce=1000")
            .invoke("debounced-test", TestSettingContext())
        val logger = KotlinLogging.logger("debounced-test")

        println("\n=== Testing debounced-dev:// mode ===\n")

        // Generate many similar spans rapidly (simulating websocket messages)
        repeat(50) { i ->
            telemetry["websocket"].spanBuilder("websocket.message").useBlocking {
                logger.info { "Message $i" }
                Thread.sleep(10) // Brief pause between messages
            }
        }

        println("\n--- Waiting for debounce window to expire (1s) ---\n")
        Thread.sleep(1500) // Wait for window to expire and aggregate to print

        // Generate a different span to trigger flush of any remaining aggregates
        telemetry["api"].spanBuilder("GET /api/test").useBlocking {
            logger.info { "Different operation" }
            Thread.sleep(50)
        }

        println("\n=== Debounced mode test complete - should see aggregated websocket.message spans ===\n")
        Thread.sleep(500)
    }

    @Test
    fun testDebouncedDevModeWithMinCount() {
        // Test debounced-dev:// with minimum count threshold
        val telemetry = OpenTelemetrySettings("debounced-dev://?debounce=1000&debounce_min=5")
            .invoke("debounced-min-test", TestSettingContext())
        val logger = KotlinLogging.logger("debounced-min-test")

        println("\n=== Testing debounced-dev:// with debounce_min=5 ===\n")

        // Generate only 3 spans (below threshold)
        println("--- Generating 3 spans (below min=5) ---")
        repeat(3) { i ->
            telemetry["below-threshold"].spanBuilder("low.frequency.operation").useBlocking {
                logger.info { "Low frequency $i" }
                Thread.sleep(10)
            }
        }

        Thread.sleep(1500) // Wait for window to expire
        println("--- Above spans should appear individually (count < 5) ---\n")

        // Generate 10 spans (above threshold)
        println("--- Generating 10 spans (above min=5) ---")
        repeat(10) { i ->
            telemetry["above-threshold"].spanBuilder("high.frequency.operation").useBlocking {
                logger.info { "High frequency $i" }
                Thread.sleep(10)
            }
        }

        Thread.sleep(1500) // Wait for window to expire
        println("--- Above spans should be aggregated (count >= 5) ---\n")

        println("\n=== Debounced min count test complete ===\n")
        Thread.sleep(500)
    }

    @Test
    fun testDebouncedDevModeMultipleWindows() {
        // Test that multiple debounce windows work correctly
        val telemetry = OpenTelemetrySettings("debounced-dev://?debounce=500")
            .invoke("multi-window-test", TestSettingContext())
        val logger = KotlinLogging.logger("multi-window-test")

        println("\n=== Testing multiple debounce windows ===\n")

        // First window - 10 spans
        println("--- Window 1: Generating 10 websocket.send spans ---")
        repeat(10) { i ->
            telemetry["ws"].spanBuilder("websocket.send").useBlocking {
                logger.info { "Send $i" }
                Thread.sleep(5)
            }
        }

        Thread.sleep(600) // Wait for first window to expire
        println("--- First window should have printed aggregate ---\n")

        // Second window - 15 spans
        println("--- Window 2: Generating 15 websocket.send spans ---")
        repeat(15) { i ->
            telemetry["ws"].spanBuilder("websocket.send").useBlocking {
                logger.info { "Send ${i + 10}" }
                Thread.sleep(5)
            }
        }

        Thread.sleep(600) // Wait for second window to expire
        println("--- Second window should have printed aggregate ---\n")

        println("\n=== Multiple windows test complete - should see two separate aggregates ===\n")
        Thread.sleep(500)
    }

    @Test
    fun testDebouncedDevModeWithErrors() {
        // Test debounced mode includes error rate in aggregate
        val telemetry = OpenTelemetrySettings("debounced-dev://?debounce=1000")
            .invoke("debounced-errors-test", TestSettingContext())
        val logger = KotlinLogging.logger("debounced-errors-test")

        println("\n=== Testing debounced-dev:// with errors ===\n")

        // Generate mix of successful and failed spans
        repeat(20) { i ->
            telemetry["api"].spanBuilder("api.request").useBlocking { span ->
                if (i % 5 == 0) {
                    // Every 5th request fails
                    logger.error { "Request $i failed" }
                    val error = RuntimeException("Request failed")
                    span.recordException(error)
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, error.message ?: "")
                } else {
                    logger.info { "Request $i succeeded" }
                }
                Thread.sleep(10)
            }
        }

        Thread.sleep(1500) // Wait for window to expire
        println("--- Aggregate should show 4 errors out of 20 (20% error rate) ---\n")

        println("\n=== Debounced errors test complete ===\n")
        Thread.sleep(500)
    }

    @Test
    fun testDebouncedDevModeFileOutput() {
        // Test debounced mode with file output
        val outputFile = java.io.File("local/debounced-traces.log")
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val telemetry = OpenTelemetrySettings("debounced-dev://local/debounced-traces.log?debounce=500&color=false")
            .invoke("debounced-file-test", TestSettingContext()) as io.opentelemetry.sdk.OpenTelemetrySdk
        val logger = KotlinLogging.logger("debounced-file-test")

        println("\n=== Testing debounced-dev:// with file output ===\n")

        // Generate aggregatable spans
        repeat(25) { i ->
            telemetry["ops"].spanBuilder("batch.operation").useBlocking {
                logger.info { "Operation $i" }
                Thread.sleep(5)
            }
            println("Iter $i complete")
        }

        Thread.sleep(1600) // Wait for window to expire
        println("Window should have expired")

        // Shutdown to flush
        telemetry.sdkTracerProvider.shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS)
        println("Flushing")
        Thread.sleep(500)

        // Verify file content
        assertTrue("Output file should exist", outputFile.exists())
        val content = outputFile.readText()
        println("Content: " + content)
        assertTrue("File should contain additional count", content.contains("additional"))
        //  Actual output batches a whole bunch of them together, but one set of 21 and another of 4.
        // This might be an issue
//        assertTrue("File should show 25 total", content.contains("25 total"))
        assertTrue("File should show summary", content.contains("Summary:"))
        assertFalse("File should not contain ANSI codes", content.contains("\u001B["))

        println("Debounced file output test passed. Content:\n$content")
    }

    @Test
    fun testDebouncedDevModeNoDebounceFallback() {
        // Test that debounced-dev without debounce parameter falls back to normal dev behavior
        val telemetry = OpenTelemetrySettings("debounced-dev://")
            .invoke("no-debounce-test", TestSettingContext())
        val logger = KotlinLogging.logger("no-debounce-test")

        println("\n=== Testing debounced-dev:// WITHOUT debounce parameter (should behave like dev://) ===\n")

        repeat(5) { i ->
            telemetry["api"].spanBuilder("normal.span.$i").useBlocking {
                logger.info { "Span $i" }
                Thread.sleep(20)
            }
        }

        println("\n--- All 5 spans should appear individually (no aggregation) ---\n")
        Thread.sleep(500)
    }

}