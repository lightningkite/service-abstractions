package com.lightningkite.services.database.postgres

import com.lightningkite.services.MetricUnit
import com.lightningkite.services.SettingContext
import com.lightningkite.services.metricsGauge
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * PostgreSQL implementation of the Database abstraction using Exposed ORM.
 *
 * Translates the type-safe Condition/Modification DSL to SQL queries via the Exposed framework.
 * Supports full PostgreSQL feature set including:
 * - **JSONB columns**: Stores models as JSONB for flexible schema
 * - **Array support**: Native PostgreSQL array types for List/Set fields
 * - **Full-text search**: PostgreSQL tsvector/tsquery for text search
 * - **GiST indexes**: Geospatial queries via PostGIS (when available)
 * - **Connection pooling**: A HikariCP pool backs every connection; tune it via URL query params.
 *
 * ## Supported URL Schemes
 *
 * - `postgresql://user:password@host:port/database` - Standard PostgreSQL connection
 * - `postgresql://host:port/database` - Local connection (no auth)
 *
 * Append `?key=value&...` to configure the pool. Supported keys: `maxPoolSize`, `minIdle`,
 * `connectionTimeout`, `idleTimeout`, `maxLifetime`, `validationTimeout`, `poolName` (timeouts in
 * milliseconds). Any other query params are forwarded to the PostgreSQL JDBC URL.
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production with authentication
 * Database.Settings("postgresql://myuser:mypass@db.example.com:5432/production")
 *
 * // Local development
 * Database.Settings("postgresql://localhost:5432/dev")
 *
 * // Larger pool for high concurrency
 * Database.Settings("postgresql://user:pass@host:5432/mydb?maxPoolSize=30")
 * ```
 *
 * ## Implementation Notes
 *
 * - **Schema management**: Tables created automatically on first access
 * - **Migrations**: Not handled by this library (use Flyway/Liquibase)
 * - **Connection pool**: HikariCP; the pool is closed on [disconnect] and rebuilt on reconnect.
 * - **Serverless**: Supports disconnect/connect for AWS Lambda
 * - **Type mapping**: Kotlin types → PostgreSQL JSONB (flexible but less queryable than native types)
 *
 * ## Performance Considerations
 *
 * - **JSONB queries**: Slower than native column queries, but more flexible
 * - **Indexes**: Add indexes on JSONB fields you query frequently
 * - **Connection pool**: Size via the `maxPoolSize` URL query param for high concurrency.
 *
 * @property name Service name for logging/metrics
 * @property context Service context with serializers
 * @property makeDb Lazy pooled-database factory (for serverless disconnect/reconnect)
 */
public class PostgresDatabase(
    override val name: String,
    override val context: SettingContext,
    private val makeDb: () -> PooledDatabase,
) : com.lightningkite.services.database.Database {
    private var _db = lazy(makeDb)

    public val db: org.jetbrains.exposed.sql.Database get() = _db.value.database

    // Point-in-time count of busy connections; sampled by the exporter, so guard the lazy pool.
    private val poolActiveGauge = metricsGauge("sql.pool.active", MetricUnit.Occurrences, emptySet()) {
        if (_db.isInitialized()) _db.value.dataSource?.hikariPoolMXBean?.activeConnections?.toLong() ?: 0L
        else 0L
    }

    override suspend fun disconnect() {
        collections.values.forEach { if (it.isInitialized()) it.value.close() }
        collections.clear()
        if (_db.isInitialized()) {
            val pooled = _db.value
            TransactionManager.closeAndUnregister(pooled.database)
            pooled.dataSource?.close()
        }
        _db = lazy(makeDb)
    }

    override suspend fun connect() {
        // KEEP THIS AROUND.
        // This initializes the database call at startup.
        healthCheck()
    }

    public companion object {
        public fun com.lightningkite.services.database.Database.Settings.Companion.postgres(
            username: String,
            password: String,
            host: String,
        ): com.lightningkite.services.database.Database.Settings =
            com.lightningkite.services.database.Database.Settings("postgresql://$username:$password@$host")

        init {
            // postgresql://user:password@endpoint/database
            com.lightningkite.services.database.Database.Settings.register("postgresql") { name, url, context ->
                Regex("""postgresql://(?<user>[^:]*)(?<password>[^@]*)@(?<destination>.+)""").matchEntire(url)
                    ?.let { match ->
                        val user = match.groups["user"]!!.value
                        val password = match.groups["password"]!!.value
                        val split = splitPoolQuery(match.groups["destination"]!!.value)
                        val jdbcUrl = "jdbc:postgresql://${split.base}" + (split.jdbcQuery?.let { "?$it" } ?: "")
                        if (user.isNotBlank() && password.isNotBlank())
                            PostgresDatabase(name = name, context = context) {
                                makePooledDatabase(jdbcUrl, "org.postgresql.Driver", user, password, split.pool)
                            }
                        else
                            PostgresDatabase(name = name, context = context) {
                                makePooledDatabase(jdbcUrl, "org.postgresql.Driver", pool = split.pool)
                            }
                    }
                    ?: throw IllegalStateException("Invalid Postgres Url. The URL should match the pattern: postgresql://[user]:[password]@[destination]")
            }
        }
    }

    private val collections = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<PostgresCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(serializer: KSerializer<T>, name: String): PostgresCollection<T> =
        (collections.getOrPut(serializer to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                PostgresCollection(
                    db,
                    name,
                    serializer,
                    context.internalSerializersModule,
                    context,
                )
            }
        } as Lazy<PostgresCollection<T>>).value

}
