package com.lightningkite.services.database.cassandra

import com.github.nosan.embedded.cassandra.Cassandra
import com.github.nosan.embedded.cassandra.CassandraBuilder
import com.github.nosan.embedded.cassandra.Settings
import com.lightningkite.services.SettingContext
import java.net.InetSocketAddress
import kotlin.time.Duration

/**
 * Creates a Cassandra database backed by embedded Cassandra for testing.
 * No Docker required - downloads and runs Cassandra in a separate process.
 *
 * The embedded Cassandra instance is shared per JVM to avoid startup overhead.
 * Each call creates a new keyspace to isolate tests.
 *
 * @param name Service name
 * @param context Setting context with serializers
 * @param version Cassandra version (default uses library default, typically 5.0.x)
 */
public fun embeddedCassandra(
    name: String,
    context: SettingContext,
    version: String? = null
): CassandraDatabase {
    val settings = ensureEmbeddedCassandraStarted(version)
    val keyspace = "test_${System.nanoTime()}"

    return CassandraDatabase(
        name = name,
        keyspace = keyspace,
        contactPoints = listOf(InetSocketAddress(settings.address, settings.port)),
        datacenter = "datacenter1",
        username = null,
        password = null,
        // by Claude - Use zero debounce for fastest test performance
        schemaDebounceWindow = Duration.ZERO,
        context = context
    )
}

/**
 * Creates an embedded Cassandra database with a specific keyspace.
 * Useful for development when you want a consistent keyspace name.
 *
 * @param name Service name
 * @param context Setting context
 * @param keyspace Keyspace name to use
 * @param version Cassandra version (optional)
 */
public fun embeddedCassandraPersistent(
    name: String,
    context: SettingContext,
    keyspace: String = "app",
    version: String? = null
): CassandraDatabase {
    val settings = ensureEmbeddedCassandraStarted(version)

    return CassandraDatabase(
        name = name,
        keyspace = keyspace,
        contactPoints = listOf(InetSocketAddress(settings.address, settings.port)),
        datacenter = "datacenter1",
        username = null,
        password = null,
        // by Claude - Use zero debounce for fastest test/dev performance
        schemaDebounceWindow = Duration.ZERO,
        context = context
    )
}

private var embeddedCassandra: Cassandra? = null
private var embeddedSettings: Settings? = null

@Synchronized
private fun ensureEmbeddedCassandraStarted(version: String?): Settings {
    embeddedSettings?.let { return it }

    val builder = CassandraBuilder()
    if (version != null) {
        builder.version(version)
    }

    val cassandra = builder.build()
    cassandra.start()

    embeddedCassandra = cassandra
    embeddedSettings = cassandra.settings

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            cassandra.stop()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    })

    return cassandra.settings
}
