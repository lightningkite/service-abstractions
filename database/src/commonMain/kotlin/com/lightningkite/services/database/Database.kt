package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.*
import com.lightningkite.services.data.KFile
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Database abstraction providing uniform access to different database systems.
 *
 * Implementations wrap various database backends (MongoDB, PostgreSQL, in-memory, etc.)
 * behind a common interface. Applications can switch databases via configuration without
 * code changes.
 *
 * ## Available Implementations
 *
 * - **InMemoryDatabase** (`ram://`) - In-memory HashMap-based storage, no persistence
 * - **MongoDatabase** (`mongodb://`) - MongoDB with native query translation
 * - **PostgresDatabase** (`postgresql://`) - PostgreSQL via Exposed ORM
 * - **JsonFileDatabase** (`file://`) - JSON file-based storage (dev/testing)
 *
 * ## Configuration
 *
 * Databases are configured via [Settings] using URL strings:
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val database: Database.Settings = Database.Settings("mongodb://localhost:27017/mydb")
 * )
 *
 * val context = SettingContext(...)
 * val db: Database = settings.database("main-db", context)
 * ```
 *
 * ## Usage
 *
 * Access tables using type-safe generic functions:
 *
 * ```kotlin
 * val userTable: Table<User> = db.table<User>()
 * val users = userTable.find(condition = User.path.age gte 18)
 * ```
 *
 * ## Special URL Schemes
 *
 * - `ram` or `ram://` - In-memory database
 * - `ram-preload://path/to/data.json` - Pre-populated in-memory database
 * - `delay://100-500ms/mongodb://...` - Add artificial latency (useful for testing)
 *
 * ## Health Checks
 *
 * Database health is monitored via [healthCheck], which performs a test insert/read
 * to verify connectivity and write permissions.
 *
 * ## Serverless Support
 *
 * Databases support [connect]/[disconnect] lifecycle methods for serverless environments
 * like AWS Lambda where connections should be managed explicitly.
 *
 * @see Table
 * @see InMemoryDatabase
 */
public interface Database : Service {
    /**
     * Settings that define what cache to use and how to connect to it.
     *
     * @param url Defines the type and connection to the cache. Built-in options are local.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "ram"
    ) : Setting<Database> {
        public companion object : UrlSettingParser<Database>() {
            init {
                register("ram") { name, _, context ->
                    InMemoryDatabase(name, context = context)
                }
                register("ram-preload") { name, url, context ->
                    InMemoryDatabase(
                        name,
                        premadeData = url
                            .substringAfter("://", "")
                            .takeUnless { it.isBlank() }
                            ?.let { path ->
                                val file = KFile(path)
                                val meta = file.metadataOrNull()

                                when {
                                    meta == null -> {
                                        KotlinLogging.logger("com.lightningkite.services.database").warn { "Could not extract metadata from file $path" }
                                        null
                                    }
                                    meta.isDirectory -> InMemoryDatabase.PreloadData.JsonFiles(file)
                                    meta.isRegularFile -> {
                                        val json = Json { serializersModule = context.internalSerializersModule }
                                        InMemoryDatabase.PreloadData.InMemory(
                                            json.parseToJsonElement(file.readString()).jsonObject
                                        )
                                    }
                                    else -> null
                                }
                            },
                        context
                    )
                }
                register("delay") { name, url, context ->
                    val x = url.substringAfter("://")
                    val delayString = x.substringBefore("/")
                    val delay = delayString.toLongOrNull()?.let { it.milliseconds..it.milliseconds }
                        ?: delayString.takeIf { it.contains('-') }?.let {
                            Duration.parse(it.substringBefore('-').trim())..
                                    Duration.parse(it.substringAfter('-').trim())
                        }
                        ?: delayString.takeUnless { it.isBlank() }?.let {
                            val duration = Duration.parse(it.trim())
                            duration.times(0.5)..duration
                        }
                        ?: 350.milliseconds..750.milliseconds
                    val wraps = x.substringAfter("/")
                    parse(name, wraps, context).delayed(delay)
                }
            }
        }

        override fun invoke(name: String, context: SettingContext): Database {
            return parse(name, url, context)
        }
    }

    /**
     * Returns a table of type T that will access and manipulate data from a table in the underlying database system.
     */
    public fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T>

    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> table(type: KType, name: String): Table<T> =
        table(context.internalSerializersModule.serializer(type) as KSerializer<T>, name)

    /**
     * Will attempt inserting data into the database to confirm that the connection is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
//        prepareModelsServerCore()
        try {
            val c = table<HealthCheckTestModel>()
            val id = "HealthCheck"
            c.upsertOneById(id, HealthCheckTestModel(id))
            if (c.get(id) == null) throw AssertionError("Assertion Failed")
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message ?: e::class.toString())
        }
    }
}

@GenerateDataClassPaths
@Serializable
public data class HealthCheckTestModel(override val _id: String) : HasId<String>

/**
 * A Helper function for getting a table from a database using generics.
 * This can make table calls much cleaner and less wordy when the types can be inferred.
 */
public inline fun <reified T : Any> Database.table(name: String = T::class.simpleName!!): Table<T> {
    return table(context.internalSerializersModule.serializer<T>(), name)
}

// TODO: API Recommendation - Add transaction support API
//  Many databases support multi-document ACID transactions, but there's no common interface.
//  Consider adding: suspend fun <R> transaction(block: suspend () -> R): R
//  This would allow backends to wrap operations in DB-specific transactions (MongoDB sessions, PostgreSQL BEGIN/COMMIT)
//
// TODO: API Recommendation - Add schema migration support
//  Database.Settings could include schema version tracking and migration hooks.
//  This would help manage schema evolution across deployments.
//  Example: suspend fun migrate(from: Int, to: Int, migrations: List<Migration>)
//
// TODO: API Recommendation - Consider adding read replicas support
//  Add Database.table() variant that specifies read preference (primary vs replica).
//  This would allow read-heavy operations to use read replicas for scaling.
//  Example: fun <T> table(serializer: KSerializer<T>, name: String, readPreference: ReadPreference = Primary)
