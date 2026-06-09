package com.lightningkite.services.database.sql

import com.lightningkite.services.MetricUnit
import com.lightningkite.services.SettingContext
import com.lightningkite.services.metricsGauge
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic SQL implementation of the Database abstraction using Exposed ORM.
 *
 * Unlike the PostgreSQL driver, this uses child tables for collection fields (List, Set, Map)
 * instead of native array types, making it compatible with any JDBC-supported database.
 *
 * ## Connection pooling
 *
 * Every connection is served from a HikariCP pool. Pool behaviour is configured via the settings
 * URL query string (see below); JDBC-specific query params are forwarded unchanged to the driver.
 * In-memory databases (H2 `mem:`, SQLite `:memory:`) bypass pooling (single connection) because a
 * pooled in-memory DB exposes a separate private schema per physical connection.
 *
 * ## Supported URL Schemes
 *
 * - `sql-h2://mem:dbname` — H2 in-memory database (pooling bypassed)
 * - `sql-h2://file:/path/to/db` — H2 file database
 * - `sql-sqlite:///path/to/db.sqlite` — SQLite file database
 * - `sql-sqlite://:memory:` — SQLite in-memory database (pooling bypassed)
 * - `sql-mysql://user:password@host:port/database` — MySQL
 * - `sql-mariadb://user:password@host:port/database` — MariaDB
 *
 * ## Pool query parameters
 *
 * Append `?key=value&...` to the URL. Supported keys: `maxPoolSize`, `minIdle`,
 * `connectionTimeout`, `idleTimeout`, `maxLifetime`, `validationTimeout`, `poolName` (timeouts in
 * milliseconds). Any other query params are passed through to the JDBC URL.
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // H2 in-memory (testing)
 * Database.Settings("sql-h2://mem:testdb")
 *
 * // MySQL with a 20-connection pool
 * Database.Settings("sql-mysql://user:pass@localhost:3306/mydb?maxPoolSize=20")
 * ```
 */
public class SqlDatabase(
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

    /** Forces the lazy pool to materialize and returns the live [PooledDatabase]. Test/diagnostics use only. */
    internal fun materializePool(): PooledDatabase = _db.value

    override suspend fun disconnect() {
        if (_db.isInitialized()) {
            val pooled = _db.value
            TransactionManager.closeAndUnregister(pooled.database)
            pooled.dataSource?.close()
        }
        _db = lazy(makeDb)
    }

    override suspend fun connect() {
        healthCheck()
    }

    public companion object {
        init {
            // H2 database — in-memory (`mem:`) bypasses pooling.
            com.lightningkite.services.database.Database.Settings.register("sql-h2") { name, url, context ->
                val split = splitPoolQuery(url.removePrefix("sql-h2://"))
                val jdbcUrl = "jdbc:h2:${split.base}" + (split.jdbcQuery?.let { ";$it" } ?: "")
                SqlDatabase(name = name, context = context) {
                    makePooledDatabase(
                        jdbcUrl = jdbcUrl,
                        driver = "org.h2.Driver",
                        pool = split.pool,
                        bypassPool = split.base.startsWith("mem:"),
                    )
                }
            }

            // SQLite database — `:memory:` bypasses pooling.
            com.lightningkite.services.database.Database.Settings.register("sql-sqlite") { name, url, context ->
                val split = splitPoolQuery(url.removePrefix("sql-sqlite://"))
                val jdbcUrl = "jdbc:sqlite:${split.base}" + (split.jdbcQuery?.let { "?$it" } ?: "")
                SqlDatabase(name = name, context = context) {
                    makePooledDatabase(
                        jdbcUrl = jdbcUrl,
                        driver = "org.sqlite.JDBC",
                        pool = split.pool,
                        // SQLite (file or memory) tolerates only one writer; keep it single-connection.
                        bypassPool = true,
                    )
                }
            }

            // MySQL database
            com.lightningkite.services.database.Database.Settings.register("sql-mysql") { name, url, context ->
                val destination = url.removePrefix("sql-mysql://")
                Regex("""(?<user>[^:]*):(?<password>[^@]*)@(?<host>.+)""").matchEntire(destination)
                    ?.let { match ->
                        val user = match.groups["user"]!!.value
                        val password = match.groups["password"]!!.value
                        val split = splitPoolQuery(match.groups["host"]!!.value)
                        val jdbcUrl = "jdbc:mysql://${split.base}" + (split.jdbcQuery?.let { "?$it" } ?: "")
                        SqlDatabase(name = name, context = context) {
                            makePooledDatabase(jdbcUrl, "com.mysql.cj.jdbc.Driver", user, password, split.pool)
                        }
                    }
                    ?: throw IllegalStateException("Invalid MySQL URL. Expected: sql-mysql://user:password@host:port/database")
            }

            // MariaDB database
            com.lightningkite.services.database.Database.Settings.register("sql-mariadb") { name, url, context ->
                val destination = url.removePrefix("sql-mariadb://")
                Regex("""(?<user>[^:]*):(?<password>[^@]*)@(?<host>.+)""").matchEntire(destination)
                    ?.let { match ->
                        val user = match.groups["user"]!!.value
                        val password = match.groups["password"]!!.value
                        val split = splitPoolQuery(match.groups["host"]!!.value)
                        val jdbcUrl = "jdbc:mariadb://${split.base}" + (split.jdbcQuery?.let { "?$it" } ?: "")
                        SqlDatabase(name = name, context = context) {
                            makePooledDatabase(jdbcUrl, "org.mariadb.jdbc.Driver", user, password, split.pool)
                        }
                    }
                    ?: throw IllegalStateException("Invalid MariaDB URL. Expected: sql-mariadb://user:password@host:port/database")
            }
        }
    }

    private val collections = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<SqlCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(serializer: KSerializer<T>, name: String): SqlCollection<T> =
        (collections.getOrPut(serializer to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                SqlCollection(
                    db,
                    name,
                    serializer,
                    context.internalSerializersModule,
                    context,
                )
            }
        } as Lazy<SqlCollection<T>>).value
}
