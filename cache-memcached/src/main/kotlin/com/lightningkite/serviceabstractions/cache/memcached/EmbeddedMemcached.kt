package com.lightningkite.serviceabstractions.cache.memcached

import java.io.File

/**
 * Utility for starting an embedded Memcached server for testing.
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