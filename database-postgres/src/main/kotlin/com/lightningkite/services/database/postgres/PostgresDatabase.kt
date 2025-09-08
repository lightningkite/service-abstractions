package com.lightningkite.services.database.postgres

import com.lightningkite.services.SettingContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

public class PostgresDatabase(
    override val name: String,
    override val context: SettingContext,
    private val makeDb: () -> Database,
) : com.lightningkite.services.database.Database {
    private var _db = lazy(makeDb)

    public val db: Database get() = _db.value

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
                    context.internalSerializersModule
                )
            }
        } as Lazy<PostgresCollection<T>>).value

}