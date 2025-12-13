package com.lightningkite.services.database.postgres

import com.lightningkite.services.SettingContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

/**
 * PostgreSQL implementation of the Database abstraction using Exposed ORM.
 *
 * Translates the type-safe Condition/Modification DSL to SQL queries via the Exposed framework.
 * Supports full PostgreSQL feature set including:
 * - **JSONB columns**: Stores models as JSONB for flexible schema
 * - **Array support**: Native PostgreSQL array types for List/Set fields
 * - **Full-text search**: PostgreSQL tsvector/tsquery for text search
 * - **GiST indexes**: Geospatial queries via PostGIS (when available)
 * - **Connection pooling**: Via Exposed's connection management
 *
 * ## Supported URL Schemes
 *
 * - `postgresql://user:password@host:port/database` - Standard PostgreSQL connection
 * - `postgresql://host:port/database` - Local connection (no auth)
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
 * // Cloud provider (RDS, Cloud SQL, etc.)
 * Database.Settings("postgresql://user:pass@rds-endpoint.region.rds.amazonaws.com:5432/mydb")
 * ```
 *
 * ## Implementation Notes
 *
 * - **Schema management**: Tables created automatically on first access
 * - **Migrations**: Not handled by this library (use Flyway/Liquibase)
 * - **Transactions**: Use Exposed's transaction blocks explicitly
 * - **Serverless**: Supports disconnect/connect for AWS Lambda
 * - **Type mapping**: Kotlin types → PostgreSQL JSONB (flexible but less queryable than native types)
 *
 * ## Performance Considerations
 *
 * - **JSONB queries**: Slower than native column queries, but more flexible
 * - **Indexes**: Add indexes on JSONB fields you query frequently
 * - **Connection pool**: Configure via Exposed settings for high concurrency
 *
 * @property name Service name for logging/metrics
 * @property context Service context with serializers
 * @property makeDb Lazy database connection factory (for serverless disconnect/reconnect)
 */
public class PostgresDatabase(
    override val name: String,
    override val context: SettingContext,
    private val makeDb: () -> Database,
) : com.lightningkite.services.database.Database {
    private var _db = lazy(makeDb)

    public val db: Database get() = _db.value
    internal val tracer by lazy { context.openTelemetry?.getTracer("database-postgres") }

    override suspend fun disconnect() {
        if (_db.isInitialized()) TransactionManager.closeAndUnregister(_db.value)
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
            host: String
        ): com.lightningkite.services.database.Database.Settings = com.lightningkite.services.database.Database.Settings("postgresql://$username:$password@$host")
        init {
            // postgresql://user:password@endpoint/database
            com.lightningkite.services.database.Database.Settings.register("postgresql") { name, url, context ->
                Regex("""postgresql://(?<user>[^:]*)(?<password>[^@]*)@(?<destination>.+)""").matchEntire(url)
                    ?.let { match ->
                        val user = match.groups["user"]!!.value
                        val password = match.groups["password"]!!.value
                        val destination = match.groups["destination"]!!.value
                        if (user.isNotBlank() && password.isNotBlank())
                            PostgresDatabase(name = name, context = context) {
                                Database.connect(
                                    "jdbc:postgresql://$destination",
                                    "org.postgresql.Driver",
                                    user,
                                    password
                                )
                            }
                        else
                            PostgresDatabase(name = name, context = context) {
                                Database.connect(
                                    "jdbc:postgresql://$destination",
                                    "org.postgresql.Driver"
                                )
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
                    tracer
                )
            }
        } as Lazy<PostgresCollection<T>>).value

}