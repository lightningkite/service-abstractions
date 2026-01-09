package com.lightningkite.services.database.mongodb

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.UniqueViolationException
import com.mongodb.*
import com.mongodb.event.ConnectionCheckedInEvent
import com.mongodb.event.ConnectionCheckedOutEvent
import com.mongodb.event.ConnectionPoolListener
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.opentelemetry.instrumentation.mongo.v3_1.MongoTelemetry
import kotlinx.serialization.KSerializer
import org.bson.BsonDocument
import org.bson.UuidRepresentation
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * MongoDB implementation of the Database abstraction.
 *
 * Provides native MongoDB query translation for Condition/Modification DSL with:
 * - **Efficient queries**: Translates type-safe queries to native MongoDB aggregation pipeline
 * - **Index support**: Respects @Index annotations for performance
 * - **Atlas Search**: Optional full-text search via MongoDB Atlas Search
 * - **Connection pooling**: Configurable pool sizes (serverless-aware)
 * - **OpenTelemetry**: Automatic instrumentation when available
 *
 * ## Supported URL Schemes
 *
 * - `mongodb://host:port/dbName` - Standard MongoDB connection
 * - `mongodb+srv://host/dbName` - MongoDB Atlas (SRV records)
 * - `mongodb+srv://host/dbName?atlasSearch=true` - With Atlas Search enabled
 * - `mongodb-test://?version=7.0` - Ephemeral test instance (specific version)
 * - `mongodb-file://path/to/folder` - Embedded MongoDB (development only)
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production Atlas
 * Database.Settings("mongodb+srv://user:pass@cluster.mongodb.net/mydb?retryWrites=true")
 *
 * // Local development
 * Database.Settings("mongodb://localhost:27017/dev")
 *
 * // Testing with ephemeral instance
 * Database.Settings("mongodb-test://?version=7.0")
 *
 * // Embedded for CI/local dev
 * Database.Settings("mongodb-file:///tmp/mongo-data")
 * ```
 *
 * ## Performance Considerations
 *
 * - **Serverless**: Connection pool automatically sized for AWS Lambda/serverless (4 connections)
 * - **Atlas Search**: Requires atlas Search indexes configured in Atlas UI
 * - **Indexes**: Ensure @Index annotations match your query patterns
 * - **Aggregation**: Complex queries use aggregation pipeline (efficient for large datasets)
 *
 * @property name Service name for logging/metrics
 * @property databaseName MongoDB database name
 * @property atlasSearch Enable Atlas Search for full-text queries (requires Atlas)
 * @property clientSettings MongoDB client configuration
 * @property context Service context with serializers and observability
 */
public class MongoDatabase(
    override val name: String,
    public val databaseName: String,
    public val atlasSearch: Boolean = false,
    public val clientSettings: MongoClientSettings,
    override val context: SettingContext,
) : Database {

    public companion object {
        private val isServerless: Boolean by lazy {
            System.getenv("FUNCTIONS_WORKER_RUNTIME") != null || System.getenv("AWS_EXECUTION_ENV") != null
        }
        private fun parseParameterString(params: String): Map<String, List<String>> = params
            .takeIf { it.isNotBlank() }
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.map {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
            ?.groupBy { it.first }
            ?.mapValues { it.value.map { it.second } }
            ?: emptyMap()

        public fun Database.Settings.Companion.mongoDb(connectionString: String): Database.Settings = Database.Settings(connectionString)
        public fun Database.Settings.Companion.mongoDbTest(version: String? = null): Database.Settings = Database.Settings("mongodb-test://?version=$version")
        public fun Database.Settings.Companion.mongoDbFile(folder: String, port: Int? = null, databaseName: String? = null): Database.Settings =
            Database.Settings("mongodb-file://$folder?port=$port&databaseName=$databaseName")

        init {
            Database.Settings.register("mongodb") { name, url, context ->
                Regex("""mongodb://.*/(?<databaseName>[^?]+)(?:\?.*)?""")
                    .matchEntire(url)
                    ?.let { match ->
                        val atlasSearch = url.contains("atlasSearch=true")
                        val withoutAtlasSearch =
                            url.replace("?atlasSearch=true&", "?")
                                .replace("&atlasSearch=true", "")
                                .replace("?atlasSearch=true", "")
                        MongoDatabase(
                            name = name,
                            databaseName = match.groups["databaseName"]!!.value,
                            clientSettings = MongoClientSettings.builder()
                                .applyConnectionString(ConnectionString(withoutAtlasSearch))
                                .build(),
                            atlasSearch = atlasSearch,
                            context = context
                        )
                    }
                    ?: throw IllegalStateException("Invalid mongodb URL. The URL should match the pattern: mongodb://[credentials and host information]/[databaseName]?[params]")
            }
            Database.Settings.register("mongodb+srv") { name, url, context ->
                Regex("""mongodb\+srv://.*/(?<databaseName>[^?]+)(?:\?.*)?""")
                    .matchEntire(url)
                    ?.let { match ->
                        val poolMax = if (isServerless) 4 else 100
                        val atlasSearch = url.contains("atlasSearch=true")
                        val withoutAtlasSearch =
                            url.replace("?atlasSearch=true", "").replace("&atlasSearch=true", "")
                        MongoDatabase(
                            name = name,
                            databaseName = match.groups["databaseName"]!!.value,
                            clientSettings = MongoClientSettings.builder()
                                .applyConnectionString(ConnectionString(withoutAtlasSearch))
                                .build(),
                            atlasSearch = atlasSearch,
                            context = context
                        )
                    }
                    ?: throw IllegalStateException("Invalid mongodb URL. The URL should match the pattern: mongodb+srv://[credentials and host information]/[databaseName]?[params]")
            }
            Database.Settings.register("mongodb-test") { name, url, context ->
                Regex("""mongodb-test(?:://(?:\?(?<params>.*))?)?""")
                    .matchEntire(url)
                    ?.let { match ->
                        val params: Map<String, List<String>>? = match.groups["params"]?.value?.let { params ->
                            parseParameterString(params)
                        }
                        MongoDatabase(
                            name = name,
                            databaseName = "default",
                            clientSettings = testMongo(version = (params?.get("version") ?: params?.get("mongoVersion"))?.firstOrNull()),
                            atlasSearch = false,
                            context = context
                        )
                    }
                    ?: throw IllegalStateException("Invalid mongodb-test URL. The URL should match the pattern: mongodb-test://?[params]\nAvailable params are: version")
            }
            Database.Settings.register("mongodb-file") { name, url, context ->
                Regex("""mongodb-file://(?<folder>[^?]+)(?:\?(?<params>.*))?""")
                    .matchEntire(url)
                    ?.let { match ->
                        val folder = match.groups["folder"]!!.value
                        val params: Map<String, List<String>>? = match.groups["params"]?.value?.let { params ->
                            parseParameterString(params)
                        }
                        MongoDatabase(
                            name = name,
                            databaseName = params?.get("databaseName")?.firstOrNull() ?: "default",
                            clientSettings = embeddedMongo(
                                databaseFolder = File(folder),
                                port = params?.get("port")?.firstOrNull()?.toIntOrNull(),
                                version = (params?.get("version") ?: params?.get("mongoVersion"))?.firstOrNull(),
                            ),
                            atlasSearch = false,
                            context = context
                        )
                    }
                    ?: throw IllegalStateException("Invalid mongodb-file URL. The URL should match the pattern: mongodb-file://[FolderPath]?[params]\nAvailable params are: databaseName, version, port")
            }
        }
    }

    private val active = AtomicInteger(0)
    private val poolSize by lazy { if (isServerless) 4 else 100 }
    public val listener: ConnectionPoolListener = object : ConnectionPoolListener {
        override fun connectionCheckedIn(event: ConnectionCheckedInEvent) {
            active.incrementAndGet()
        }

        override fun connectionCheckedOut(event: ConnectionCheckedOutEvent) {
            active.decrementAndGet()
        }
    }

    // You might be asking, "WHY?  WHY IS THIS SO COMPLICATED?"
    // Well, we have to be able to fully disconnect and reconnect existing Mongo databases in order to support AWS's
    // SnapStart feature effectively.  As such, we have to destroy and reproduce all the connections on demand.
    private val telemetry = context.openTelemetry?.let { MongoTelemetry.builder(it).build() }
    private val makeClientWithListener = {
        active.set(0)
        MongoClient.create(
            MongoClientSettings.builder(clientSettings)
                .also { if(telemetry != null) it.addCommandListener(telemetry.newCommandListener()) }
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .applyToConnectionPoolSettings {
                    it.maxSize(poolSize)
                    if (isServerless) {
                        it.maxConnectionIdleTime(15, TimeUnit.SECONDS)
                        it.maxConnectionLifeTime(1L, TimeUnit.MINUTES)
                    }
                }
                .build())
    }
    private var client = lazy(makeClientWithListener)
    private var databaseLazy = lazy { client.value.getDatabase(databaseName) }
    public val database: com.mongodb.kotlin.client.coroutine.MongoDatabase get() = databaseLazy.value
    private var coroutineCollections =
        ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<MongoCollection<BsonDocument>>>()

    override suspend fun disconnect() {
        if (client.isInitialized()) client.value.close()
        client = lazy(makeClientWithListener)
        databaseLazy = lazy { client.value.getDatabase(databaseName) }
        coroutineCollections = ConcurrentHashMap()
    }

    override suspend fun connect() {
        // KEEP THIS AROUND.
        // This initializes the database call at startup.
        healthCheck()
    }

    private val poolHealth: HealthStatus
        get() = when (val amount = active.get() / poolSize.toFloat()) {
            in 0f..<0.7f -> HealthStatus(HealthStatus.Level.OK)
            in 0.7f..<0.95f -> HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%"
            )

            in 0.95f..<1f -> HealthStatus(
                HealthStatus.Level.URGENT,
                additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%"
            )

            else -> HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%"
            )
        }

    override suspend fun healthCheck(): HealthStatus {
        return listOf(super.healthCheck(), poolHealth).maxBy { it.level }
    }

    private val collections = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<MongoTable<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> table(serializer: KSerializer<T>, name: String): Table<T> =
        (collections.getOrPut(serializer to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                MongoTable(serializer, atlasSearch = atlasSearch, object : MongoCollectionAccess {
                    override suspend fun <T> wholeDb(action: suspend com.mongodb.kotlin.client.coroutine.MongoDatabase.() -> T): T {
                        return action(databaseLazy.value)
                    }

                    override suspend fun <T> run(action: suspend MongoCollection<BsonDocument>.() -> T): T =
                        run2(action, 0)

                    suspend fun <T> run2(action: suspend MongoCollection<BsonDocument>.() -> T, tries: Int = 0): T {
                        val it = (coroutineCollections.getOrPut(serializer to name) {
                            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                                databaseLazy.value.getCollection(name, BsonDocument::class.java)
                            }
                        } as Lazy<MongoCollection<BsonDocument>>).value
                        try {
                            return action(it)
                        } catch (e: MongoBulkWriteException) {
                            if (e.writeErrors.all { ErrorCategory.fromErrorCode(it.code) == ErrorCategory.DUPLICATE_KEY })
                                throw UniqueViolationException(
                                    cause = e,
                                    table = it.namespace.collectionName
                                )
                            else throw e
                        } catch (e: MongoSocketException) {
                            if (tries >= 2) throw e
                            else {
                                disconnect()
                                return run2(action, tries + 1)
                            }
                        } catch (e: MongoException) {
                            if (ErrorCategory.fromErrorCode(e.code) == ErrorCategory.DUPLICATE_KEY)
                                throw UniqueViolationException(
                                    cause = e,
                                    table = it.namespace.collectionName
                                )
                            else throw e
                        } catch (e: Exception) {
                            throw e
                        }
                    }
                }, context)
            }
        } as Lazy<MongoTable<T>>).value
}
