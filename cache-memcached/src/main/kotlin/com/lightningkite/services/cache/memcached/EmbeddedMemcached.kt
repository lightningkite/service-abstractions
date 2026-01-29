package com.lightningkite.services.cache.memcached

import java.io.File

/**
 * Utility for starting an embedded Memcached server for testing.
 *
 * This object provides a simple mechanism to check for memcached availability
 * and start memcached processes for integration testing purposes.
 *
 * ## Usage Notes
 *
 * - The [available] property checks if memcached is in the system PATH by scanning
 *   all directories in PATH for an executable starting with "memcached"
 * - The [start] function launches memcached on the default port (11211) with default settings
 * - Callers are responsible for process lifecycle management (destruction, waiting for startup)
 * - If port 11211 is already in use, the process may fail silently or exit immediately
 *
 * ## Typical Usage Pattern
 *
 * ```kotlin
 * if (EmbeddedMemcached.available) {
 *     val process = EmbeddedMemcached.start()
 *     Runtime.getRuntime().addShutdownHook(Thread { process.destroy() })
 *     Thread.sleep(1000) // Wait for memcached to be ready
 *     // ... use memcached on localhost:11211 ...
 * }
 * ```
 */
public object EmbeddedMemcached {
    /**
     * Checks if memcached is available in the system PATH.
     */
    public val available: Boolean by lazy {
        System.getenv("PATH").split(File.pathSeparatorChar)
            .any {
                File(it).listFiles()?.any {
                    it.name.startsWith("memcached")
                } ?: false
            }
    }
    
    /**
     * Starts a memcached process.
     * @return The process that was started.
     */
    public fun start(): Process = ProcessBuilder().command("memcached").start()
}