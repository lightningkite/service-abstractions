package com.lightningkite.services.database.sql

import com.lightningkite.services.SettingContext
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic SQL implementation of the Database abstraction using Exposed ORM.
 *
 * Unlike the PostgreSQL driver, this uses child tables for collection fields (List, Set, Map)
 * instead of native array types, making it compatible with any JDBC-supported database.
 *
 * ## Supported URL Schemes
 *
 * - `sql-h2:mem:dbname` — H2 in-memory database
 * - `sql-h2:file:/path/to/db` — H2 file database
 * - `sql-sqlite:/path/to/db.sqlite` — SQLite file database
 * - `sql-sqlite::memory:` — SQLite in-memory database
 * - `sql-mysql://user:password@host:port/database` — MySQL
 * - `sql-mariadb://user:password@host:port/database` — MariaDB
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // H2 in-memory (testing)
 * Database.Settings("sql-h2:mem:testdb")
 *
 * // SQLite file
 * Database.Settings("sql-sqlite:/data/app.db")
 *
 * // MySQL
 * Database.Settings("sql-mysql://user:pass@localhost:3306/mydb")
 * ```
 */
public class SqlDatabase(
    override val name: String,
    override val context: SettingContext,
    private val makeDb: () -> Database,
) : com.lightningkite.services.database.Database {

    private var _db = lazy(makeDb)
    public val db: Database get() = _db.value
    internal val tracer by lazy { context.openTelemetry?.getTracer("database-sql") }

    override suspend fun disconnect() {
        if (_db.isInitialized()) TransactionManager.closeAndUnregister(_db.value)
        _db = lazy(makeDb)
    }

    override suspend fun connect() {
        healthCheck()
    }

    public companion object {
        init {
            // H2 database
            com.lightningkite.services.database.Database.Settings.register("sql-h2") { name, url, context ->
                val dbPath = url.removePrefix("sql-h2:")
                SqlDatabase(name = name, context = context) {
                    Database.connect("jdbc:h2:$dbPath", "org.h2.Driver")
                }
            }

            // SQLite database
            com.lightningkite.services.database.Database.Settings.register("sql-sqlite") { name, url, context ->
                val dbPath = url.removePrefix("sql-sqlite:")
                SqlDatabase(name = name, context = context) {
                    Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
                }
            }

            // MySQL database
            com.lightningkite.services.database.Database.Settings.register("sql-mysql") { name, url, context ->
                val destination = url.removePrefix("sql-mysql://")
                Regex("""(?<user>[^:]*):(?<password>[^@]*)@(?<host>.+)""").matchEntire(destination)
                    ?.let { match ->
                        val user = match.groups["user"]!!.value
                        val password = match.groups["password"]!!.value
                        val host = match.groups["host"]!!.value
                        SqlDatabase(name = name, context = context) {
                            Database.connect("jdbc:mysql://$host", "com.mysql.cj.jdbc.Driver", user, password)
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
                        val host = match.groups["host"]!!.value
                        SqlDatabase(name = name, context = context) {
                            Database.connect("jdbc:mariadb://$host", "org.mariadb.jdbc.Driver", user, password)
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
                    tracer,
                )
            }
        } as Lazy<SqlCollection<T>>).value
}
