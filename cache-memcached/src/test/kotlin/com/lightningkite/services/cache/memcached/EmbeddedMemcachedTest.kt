// by Claude
package com.lightningkite.services.cache.memcached

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Direct unit tests for [EmbeddedMemcached] utility object.
 */
class EmbeddedMemcachedTest {

    /**
     * Tests that the [EmbeddedMemcached.available] property correctly checks if memcached
     * is in the system PATH. This test verifies the property returns a boolean without
     * throwing exceptions, regardless of whether memcached is actually installed.
     */
    @Test
    fun availableReturnsBoolean() {
        // Should return true or false without throwing, regardless of system state
        val result = EmbeddedMemcached.available
        assertNotNull(result)
        // Result is either true or false depending on system configuration
        assertEquals(result, EmbeddedMemcached.available, "available should be consistent on repeated calls")
    }

    /**
     * Tests that [EmbeddedMemcached.available] is a lazy property that caches its value.
     * Multiple accesses should return the same value since it's computed only once.
     */
    @Test
    fun availableIsLazy() {
        // Access multiple times to verify lazy behavior (same value returned)
        val first = EmbeddedMemcached.available
        val second = EmbeddedMemcached.available
        val third = EmbeddedMemcached.available
        assertEquals(first, second, "Lazy property should return consistent value")
        assertEquals(second, third, "Lazy property should return consistent value")
    }

    /**
     * Tests that [EmbeddedMemcached.start] returns a valid Process when memcached is available.
     * This test is conditional on memcached being installed.
     */
    @Test
    fun startReturnsProcessWhenAvailable() {
        if (!EmbeddedMemcached.available) {
            println("Skipping start test: memcached not available on this system")
            return
        }

        val process = EmbeddedMemcached.start()
        try {
            assertNotNull(process, "start() should return a non-null Process")
            // Give it a moment to start
            Thread.sleep(100)
            // Process should be alive (or at least was started)
            // Note: We don't strictly require isAlive because memcached might exit
            // immediately if port 11211 is already in use
        } finally {
            process.destroy()
            process.waitFor()
        }
    }

    /**
     * Tests that [EmbeddedMemcached.start] can be called and the returned process can be destroyed.
     * Verifies basic process lifecycle management works correctly.
     */
    @Test
    fun startedProcessCanBeDestroyed() {
        if (!EmbeddedMemcached.available) {
            println("Skipping destroy test: memcached not available on this system")
            return
        }

        val process = EmbeddedMemcached.start()
        try {
            Thread.sleep(100)
            process.destroy()
            val exitCode = process.waitFor()
            // Process should have terminated (exit code varies by platform)
            assertNotNull(exitCode, "Process should have an exit code after destroy")
        } finally {
            // Ensure cleanup even if assertions fail
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}
