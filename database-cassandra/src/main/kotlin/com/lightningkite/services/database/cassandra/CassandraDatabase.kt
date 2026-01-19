package com.lightningkite.services.database.cassandra

import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import software.aws.mcs.auth.SigV4AuthProvider
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

/**
 * Cassandra implementation of the Database abstraction.
 *
 * Provides Cassandra-specific query translation with:
 * - **Efficient queries**: Translates type-safe queries to CQL
 * - **Schema management**: Automatic table and index creation
 * - **Serverless awareness**: Supports connect/disconnect for Lambda SnapStart
 * - **Geospatial support**: Geohash-based computed columns for range queries
 *
 * ## Supported URL Schemes
 *
 * - `cassandra://host:port/keyspace` - Standard Cassandra connection
 * - `cassandra://host1:port,host2:port/keyspace` - Multiple contact points
 * - `cassandra-test://` - Ephemeral test instance (TestContainers)
 * - `keyspaces://region/keyspace` - AWS Keyspaces with SigV4 IAM authentication
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production
 * Database.Settings("cassandra://cassandra1.example.com:9042/myapp")
 *
 * // With authentication
 * Database.Settings("cassandra://user:pass@cassandra.example.com:9042/myapp")
 *
 * // Multiple nodes
 * Database.Settings("cassandra://node1:9042,node2:9042,node3:9042/myapp")
 *
 * // AWS Keyspaces (serverless, pay-per-request)
 * Database.Settings("keyspaces://us-west-2/myapp")
 *
 * // Testing with ephemeral instance
 * Database.Settings("cassandra-test://")
 * ```
 *
 * ## AWS Keyspaces
 *
 * AWS Keyspaces is a serverless, fully managed Cassandra-compatible database.
 * When using the `keyspaces://` scheme:
 * - Uses SigV4 authentication via AWS IAM credentials
 * - Connects to `cassandra.{region}.amazonaws.com:9142` with TLS
 * - Pay-per-request pricing (no provisioning required)
 * - Auto-scaling without capacity planning
 *
 * ## Performance Considerations
 *
 * - **Partition keys**: Queries MUST include partition key for efficient lookup
 * - **Clustering columns**: Support range queries only in defined order
 * - **SASI/SAI indexes**: Enable queries on non-key columns
 * - **Computed columns**: Use geohash for geospatial range queries
 *
 * @property name Service name for logging/metrics
 * @property keyspace Cassandra keyspace name
 * @property contactPoints List of Cassandra nodes
 * @property datacenter Datacenter name for load balancing
 * @property context Service context with serializers and observability
 */
public class CassandraDatabase(
    override val name: String,
    public val keyspace: String,
    public val contactPoints: List<InetSocketAddress>,
    public val datacenter: String,
    public val username: String? = null,
    public val password: String? = null,
    public val useAwsKeyspaces: Boolean = false,
    /**
     * Replication factor for the keyspace. Default is 1 for development.
     * **For production, use 3 or higher for fault tolerance.**
     */
    public val replicationFactor: Int = 1,
    /**
     * Schema metadata debounce window. The driver batches schema change events
     * and refreshes metadata once per window. Lower values mean faster schema
     * operations but more metadata refreshes. Default is 100ms.
     * Set to Duration.ZERO to disable debouncing entirely.
     */
    // by Claude - configurable schema debounce for faster tests
    public val schemaDebounceWindow: Duration = 100.milliseconds,
    override val context: SettingContext
) : Database {

    public companion object {
        private fun parseContactPoints(hosts: String): List<InetSocketAddress> {
            return hosts.split(",").map { hostPort ->
                val parts = hostPort.trim().split(":")
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 9042
                InetSocketAddress(host, port)
            }
        }

        init {
            Database.Settings.register("cassandra") { name, url, context ->
                // Parse: cassandra://[user:pass@]host[:port][,host[:port]...]/keyspace[?dc=datacenter&rf=3]
                val regex = Regex("""cassandra://(?:([^:]+):([^@]+)@)?([^/]+)/([^?]+)(?:\?(.*))?""")
                val match = regex.matchEntire(url)
                    ?: throw IllegalArgumentException("Invalid Cassandra URL: $url")

                val user = match.groups[1]?.value
                val pass = match.groups[2]?.value
                val hosts = match.groups[3]!!.value
                val keyspace = match.groups[4]!!.value
                val params = match.groups[5]?.value?.split("&")
                    ?.associate { it.split("=").let { p -> p[0] to p.getOrElse(1) { "" } } }
                    ?: emptyMap()

                CassandraDatabase(
                    name = name,
                    keyspace = keyspace,
                    contactPoints = parseContactPoints(hosts),
                    datacenter = params["dc"] ?: params["datacenter"] ?: "datacenter1",
                    username = user,
                    password = pass,
                    replicationFactor = (params["rf"] ?: params["replication_factor"])?.toIntOrNull() ?: 1,
                    context = context
                )
            }

            Database.Settings.register("cassandra-test") { name, _, context ->
                // Will be implemented with TestContainers
                testCassandraDatabase(name, context)
            }

            // AWS Keyspaces: keyspaces://region/keyspace
            Database.Settings.register("keyspaces") { name, url, context ->
                val regex = Regex("""keyspaces://([^/]+)/([^?]+)(?:\?(.*))?""")
                val match = regex.matchEntire(url)
                    ?: throw IllegalArgumentException("Invalid Keyspaces URL: $url. Expected format: keyspaces://region/keyspace")

                val region = match.groups[1]!!.value
                val keyspace = match.groups[2]!!.value

                val endpoint = "cassandra.$region.amazonaws.com"
                val port = 9142

                CassandraDatabase(
                    name = name,
                    keyspace = keyspace,
                    contactPoints = listOf(InetSocketAddress(endpoint, port)),
                    datacenter = region,
                    useAwsKeyspaces = true,
                    context = context
                )
            }
        }
    }

    override val healthCheckFrequency: Duration get() = 30.seconds

    private var sessionLazy: Lazy<CqlSession> = lazy { createSession() }
    private val session: CqlSession get() = sessionLazy.value

    private val tables = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<CassandraTable<*>>>()

    private fun createSession(): CqlSession {
        val configBuilder = DriverConfigLoader.programmaticBuilder()
            .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, JavaDuration.ofSeconds(30))
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, JavaDuration.ofSeconds(30))
            // by Claude - Configurable schema metadata debounce (driver default is 1s, we default to 100ms)
            // The debounce batches multiple schema events into one refresh; lower = faster but more refreshes
            .withDuration(DefaultDriverOption.METADATA_SCHEMA_WINDOW, JavaDuration.ofMillis(schemaDebounceWindow.inWholeMilliseconds))

        if (useAwsKeyspaces) {
            // AWS Keyspaces requires specific driver configuration
            // by Claude - AWS Keyspaces ONLY supports LOCAL_QUORUM consistency level
            configBuilder
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, 3)
                .withBoolean(DefaultDriverOption.METADATA_TOKEN_MAP_ENABLED, false)
                // AWS Keyspaces only supports LOCAL_QUORUM - LOCAL_ONE will fail
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, ConsistencyLevel.LOCAL_QUORUM.name())
                .withString(DefaultDriverOption.REQUEST_SERIAL_CONSISTENCY, ConsistencyLevel.LOCAL_SERIAL.name())
        }

        val configLoader = configBuilder.build()

        val builder: CqlSessionBuilder = CqlSession.builder()
            .withConfigLoader(configLoader)
            .addContactPoints(contactPoints)
            .withLocalDatacenter(datacenter)

        if (useAwsKeyspaces) {
            // AWS Keyspaces: Use SigV4 authentication and TLS
            builder.withAuthProvider(SigV4AuthProvider(datacenter))
            builder.withSslContext(createAwsSslContext())
        } else if (username != null && password != null) {
            builder.withAuthCredentials(username, password)
        }

        val session = builder.build()

        if (!useAwsKeyspaces) {
            // For standard Cassandra, ensure keyspace exists
            // AWS Keyspaces keyspaces are created via Terraform/console, not dynamically
            session.execute("""
                CREATE KEYSPACE IF NOT EXISTS ${keyspace.quoteCql()}
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': $replicationFactor}
            """.trimIndent())
        }

        return session
    }

    private fun createAwsSslContext(): SSLContext {
        // Use the default Java truststore which includes Amazon's root CAs
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustManagerFactory.trustManagers, null)
        return sslContext
    }

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            session // Force lazy initialization
            healthCheck()
        }
    }

    override suspend fun disconnect() {
        if (sessionLazy.isInitialized()) {
            withContext(Dispatchers.IO) {
                session.closeAsync().toCompletableFuture().await()
            }
        }
        sessionLazy = lazy { createSession() }
        tables.clear()
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val result = withContext(Dispatchers.IO) {
                session.executeAsync("SELECT now() FROM system.local").toCompletableFuture().await()
            }
            if (result.one() != null) {
                HealthStatus(HealthStatus.Level.OK)
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Empty result from health check")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message ?: "Unknown error")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> {
        return (tables.getOrPut(serializer to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                CassandraTable(
                    serializer = serializer,
                    session = session,
                    keyspace = keyspace,
                    tableName = name,
                    context = context,
                    // by Claude - pass Keyspaces flag for compatibility handling
                    useAwsKeyspaces = useAwsKeyspaces
                )
            }
        } as Lazy<CassandraTable<T>>).value
    }
}

// Placeholder for test database - will be implemented with TestContainers
private fun testCassandraDatabase(name: String, context: SettingContext): CassandraDatabase {
    // This will be replaced by the actual TestContainers implementation
    throw NotImplementedError("cassandra-test:// requires TestContainers implementation. Use embeddedCassandra() instead.")
}
