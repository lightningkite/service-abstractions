package com.lightningkite.services.cache.dynamodb

/**
 * AWS DynamoDB implementation of the Cache abstraction using AWS SDK for Java v2.
 *
 * Stores cache entries in a DynamoDB table with automatic table creation and TTL management.
 * JVM-only.
 *
 * ## Features
 *
 * - **Auto-provisioned tables**: Creates table automatically if it doesn't exist
 * - **TTL support**: Native DynamoDB TTL for automatic expiration (uses `expires` attribute)
 * - **Native type serialization**: Uses DynamoDB's AttributeValue serialization (not JSON strings)
 * - **Pay-per-request billing**: Uses on-demand billing mode (no capacity planning)
 * - **Strong consistency**: All reads use consistent reads
 * - **AWS SDK v2**: Uses modern async AWS SDK with connection pooling
 *
 * ## Supported URL Schemes
 *
 * - `dynamodb://region/tableName` - Using default AWS credentials
 * - `dynamodb://accessKey:secretKey@region/tableName` - Using explicit credentials
 * - `dynamodb-local://` - Embedded DynamoDB Local for testing
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production with default credentials (IAM role, env vars, etc.)
 * Cache.Settings("dynamodb://us-east-1/prod-cache")
 *
 * // Development with explicit credentials
 * Cache.Settings("dynamodb://AKIAIOSFODNN7EXAMPLE:wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY@us-east-1/dev-cache")
 *
 * // Local testing with DynamoDB Local
 * Cache.Settings("dynamodb-local://")
 * ```
 *
 * ## Implementation Notes
 *
 * - **Serialization**: Uses AWS SDK's native AttributeValue serialization (supports nested types)
 * - **Table schema**: Primary key is `key` (String), with optional `expires` (Number) for TTL
 * - **Auto-provisioning**: Creates table on first access if missing (waits for ACTIVE status)
 * - **TTL enablement**: Automatically enables TTL on the `expires` attribute
 * - **Health checks**: Integrates with AwsConnections for connection pool health
 * - **Lazy initialization**: Table creation happens on first operation (not during construction)
 *
 * ## Important Gotchas
 *
 * - **No CAS**: This implementation doesn't expose atomic compareAndSet to users (uses default retry-based modify)
 * - **Table creation latency**: First access may be slow (~10-30 seconds for table creation)
 * - **TTL delay**: DynamoDB TTL is best-effort, may take minutes to hours to expire items
 * - **get() checks expiration**: Manually filters expired items (don't rely solely on TTL deletion)
 * - **Costs**: Pay-per-request billing charges per operation (economical for low traffic)
 * - **Strong consistency**: All reads use consistent reads (higher cost than eventually consistent)
 * - **Async operations**: Uses AWS SDK async client (coroutines via .await())
 *
 * @property name Service name for logging/metrics
 * @property makeClient Lazy factory for creating DynamoDB client (enables disconnect/reconnect)
 * @property tableName DynamoDB table name for cache storage
 * @property context Service context with serializers and AwsConnections
 */
// REVIEW NOTE: Documentation above mentions "Native type serialization" but serialization.kt
// actually converts values via JSON intermediate (encodeToJsonElement -> toDynamoDb). The
// AttributeValue structure matches native DynamoDB types, but the path is Kotlin -> JSON -> AttributeValue. - by Claude

// REVIEW NOTE: The import below (kotlin.text.get) appears to be unused and could be removed - by Claude
import com.lightningkite.services.*
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import kotlin.time.Clock.System.now
import kotlin.time.Duration

public class DynamoDbCache(
    override val name: String,
    public val makeClient: () -> DynamoDbAsyncClient,
    public val tableName: String = "cache",
    override val context: SettingContext,
) : Cache {
    // PUBLICATION mode: multiple threads may race to build the client, but the AWS SDK async
    // client is thread-safe so concurrent init is harmless and avoids a global lock on every access.
    public val client: DynamoDbAsyncClient by lazy(LazyThreadSafetyMode.PUBLICATION, makeClient)

    // Static, low-cardinality span attributes shared by every operation. The cache key is hashed so a
    // high-cardinality value never reaches telemetry.
    private fun spanAttrs(
        key: String,
        timeToLive: Duration? = null,
        cacheValue: Long? = null,
    ): TelemetryAttributes = TelemetryAttributes {
        put(Cache.TelemetryKeys.key, context.telemetrySanitization.hashCacheKey(key))
        put(Cache.TelemetryKeys.system, "dynamodb")
        put(TelemetryKeys.Db.system, "dynamodb")
        put(TelemetryKeys.Db.name, tableName)
        timeToLive?.let { put(Cache.TelemetryKeys.ttl, it.inWholeSeconds) }
        cacheValue?.let { put(Cache.TelemetryKeys.value, it) }
    }

    public companion object {
        // Safety cap for [add]'s live/expired retry loop. Each failed iteration means a concurrent
        // writer flipped the row's live/expired state, so reaching this bound is effectively
        // impossible; it exists only to guarantee termination.
        private const val ADD_MAX_TRIES: Int = 5

        public fun Cache.Settings.Companion.dynamoDbLocal(): Cache.Settings = Cache.Settings("dynamodb-local")
        public fun Cache.Settings.Companion.dynamoDb(
            region: Region,
            tableName: String,
        ): Cache.Settings = Cache.Settings("dynamodb://$region/$tableName")

        public fun Cache.Settings.Companion.dynamoDb(
            accessKey: String,
            secretKey: String,
            region: Region,
            tableName: String,
        ): Cache.Settings = Cache.Settings("dynamodb://$accessKey:$secretKey@$region/$tableName")

        init {
            Cache.Settings.register("dynamodb-local") { name, url, context ->
                DynamoDbCache(name, { embeddedDynamo() }, context = context)
            }
            Cache.Settings.register("dynamodb") { name, url, context ->
                Regex("""dynamodb://(?:(?<access>[^:]+):(?<secret>[^@]+)@)?(?<region>[^/]+)/(?<tableName>.+)""").matchEntire(
                    url
                )?.let { match ->
                    val user = match.groups["access"]?.value ?: ""
                    val password = match.groups["secret"]?.value ?: ""
                    DynamoDbCache(
                        name,
                        {
                            DynamoDbAsyncClient.builder()
                                .credentialsProvider(
                                    if (user.isNotBlank() && password.isNotBlank()) {
                                        StaticCredentialsProvider.create(object : AwsCredentials {
                                            override fun accessKeyId(): String = user
                                            override fun secretAccessKey(): String = password
                                        })
                                    } else DefaultCredentialsProvider.builder().build()
                                )
                                .httpClient(context[AwsConnections].asyncClient)
                                .apply {
                                    context[AwsConnections].clientOverrideConfiguration?.let { overrideConfiguration(it) }
                                }
                                .region(Region.of(match.groups["region"]!!.value))
                                .build()
                        },
                        match.groups["tableName"]!!.value,
                        context
                    )
                }
                    ?: throw IllegalStateException("Invalid dynamodb URL. The URL should match the pattern: dynamodb://[access]:[secret]@[region]/[tableName]")
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        // Read-only credential + reachability probe. DescribeTable exercises the same credentials and
        // network path as real traffic but never mutates data (the inherited default health check
        // writes a key, which we deliberately avoid here).
        val probe = try {
            ready.await()
            client.describeTable { it.tableName(tableName) }.await()
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
        return listOf(probe, context[AwsConnections].health).maxBy { it.level }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun ready() = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        try {
            if (client.describeTimeToLive {
                    it.tableName(tableName)
                }.await().timeToLiveDescription().timeToLiveStatus() == TimeToLiveStatus.DISABLED)
                client.updateTimeToLive {
                    it.tableName(tableName)
                    it.timeToLiveSpecification {
                        it.enabled(true)
                        it.attributeName("expires")
                    }
                }.await()
            while (client.describeTable {
                    it.tableName(tableName)
                }.await().table().tableStatus() != TableStatus.ACTIVE) {
                delay(100)
            }
            Unit
        } catch (e: Exception) {
            client.createTable {
                it.tableName(tableName)
                it.billingMode(BillingMode.PAY_PER_REQUEST)
                it.keySchema(KeySchemaElement.builder().attributeName("key").keyType(KeyType.HASH).build())
                it.attributeDefinitions(
                    AttributeDefinition.builder().attributeName("key").attributeType(ScalarAttributeType.S).build()
                )
            }.await()
            while (client.describeTable {
                    it.tableName(tableName)
                }.await().table().tableStatus() != TableStatus.ACTIVE) {
                delay(100)
            }
            client.updateTimeToLive {
                it.tableName(tableName)
                it.timeToLiveSpecification {
                    it.enabled(true)
                    it.attributeName("expires")
                }
            }.await()
            while (client.describeTable {
                    it.tableName(tableName)
                }.await().table().tableStatus() != TableStatus.ACTIVE) {
                delay(100)
            }
            Unit
        }
    }

    private var ready = ready()

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
        // `cache.hit` is resolved during the operation and promoted to the RED metric via `dimensions`;
        // `enrich` makes it readable both on the span and as the metric dimension at completion.
        telemetryTrace("get", attributes = spanAttrs(key), dimensions = setOf(Cache.TelemetryKeys.hit)) { span ->
            ready.await()
            val r = client.getItem {
                it.tableName(tableName)
                it.consistentRead(true)
                it.key(mapOf("key" to AttributeValue.fromS(key)))
            }.await()
            val result = if (r.hasItem()) {
                val item = r.item()
                item["expires"]?.n()?.toLongOrNull()?.let {
                    if (System.currentTimeMillis().div(1000L) > it) return@let null
                    else serializer.fromDynamo(item["value"]!!, context)
                } ?: serializer.fromDynamo(item["value"]!!, context)
            } else null
            span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.hit, result != null) })
            result
        }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Unit =
        telemetryTrace("set", attributes = spanAttrs(key, timeToLive)) {
            ready.await()
            client.putItem {
                it.tableName(tableName)
                it.item(
                    mapOf(
                        "key" to AttributeValue.fromS(key),
                        "value" to serializer.toDynamo(value, context),
                    ) + (timeToLive?.let {
                        mapOf("expires" to AttributeValue.fromN(now().plus(it).epochSeconds.toString()))
                    } ?: mapOf())
                )
            }.await()
            Unit
        }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean = telemetryTrace("setIfNotExists", attributes = spanAttrs(key, timeToLive), dimensions = setOf(Cache.TelemetryKeys.added)) { span ->
        ready.await()
        try {
            client.putItem {
                it.tableName(tableName)
                it.expressionAttributeNames(mapOf("#k" to "key"))
                it.conditionExpression("attribute_not_exists(#k)")
                it.item(
                    mapOf(
                        "key" to AttributeValue.fromS(key),
                        "value" to serializer.toDynamo(value, context),
                    ) + (timeToLive?.let {
                        mapOf("expires" to AttributeValue.fromN(now().plus(it).epochSeconds.toString()))
                    } ?: mapOf())
                )
            }.await()
            span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.added, true) })
            true
        } catch (e: ConditionalCheckFailedException) {
            span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.added, false) })
            false
        }
    }

    override suspend fun add(key: String, value: Long, timeToLive: Duration?): Long =
        telemetryTrace("add", attributes = spanAttrs(key, timeToLive, cacheValue = value)) {
            ready.await()
            // Both attempts are atomic conditional updates so a concurrent writer can never be
            // overwritten (the previous blind-set fallback could lose a concurrent increment).
            // Each failure means another writer changed the row's live/expired state, so we loop
            // and re-evaluate; exhaustion is effectively impossible (bounded purely as a safety net).
            repeat(ADD_MAX_TRIES) {
                // Attempt A (live increment): increment if the row is absent, has a null TTL, or
                // has not yet expired.
                try {
                    val response = client.updateItem {
                        it.tableName(tableName)
                        it.key(mapOf("key" to AttributeValue.fromS(key)))
                        it.conditionExpression("attribute_not_exists(#exp) OR #exp = :null OR #exp > :now")
                        it.updateExpression("SET #exp = :exp, #v = if_not_exists(#v, :z) + :v")
                        it.expressionAttributeNames(mapOf("#v" to "value", "#exp" to "expires"))
                        it.expressionAttributeValues(
                            mapOf(
                                ":null" to AttributeValue.fromNul(true),
                                ":now" to AttributeValue.fromN(now().epochSeconds.toString()),
                                ":z" to AttributeValue.fromN("0"),
                                ":v" to AttributeValue.fromN(value.toString()),
                                ":exp" to (timeToLive?.let { AttributeValue.fromN(now().plus(it).epochSeconds.toString()) }
                                    ?: AttributeValue.fromNul(true))
                            )
                        )
                        it.returnValues(ReturnValue.ALL_NEW)
                    }.await()
                    return@telemetryTrace response.attributes().getValue("value").n().toLong()
                } catch (_: ConditionalCheckFailedException) {
                    // Row exists and is expired (or was concurrently changed). Fall through to B.
                }

                // Attempt B (expired reset): the row exists with a numeric, already-elapsed TTL,
                // so it is logically absent and the counter must restart at `value`. The
                // attribute_type(#exp, N) guard prevents a null-TTL row from ever matching here.
                try {
                    val response = client.updateItem {
                        it.tableName(tableName)
                        it.key(mapOf("key" to AttributeValue.fromS(key)))
                        it.conditionExpression("attribute_exists(#exp) AND attribute_type(#exp, :nType) AND #exp <= :now")
                        it.updateExpression("SET #v = :v, #exp = :exp")
                        it.expressionAttributeNames(mapOf("#v" to "value", "#exp" to "expires"))
                        it.expressionAttributeValues(
                            mapOf(
                                ":now" to AttributeValue.fromN(now().epochSeconds.toString()),
                                ":nType" to AttributeValue.fromS("N"),
                                ":v" to AttributeValue.fromN(value.toString()),
                                ":exp" to (timeToLive?.let { AttributeValue.fromN(now().plus(it).epochSeconds.toString()) }
                                    ?: AttributeValue.fromNul(true))
                            )
                        )
                        it.returnValues(ReturnValue.ALL_NEW)
                    }.await()
                    return@telemetryTrace response.attributes().getValue("value").n().toLong()
                } catch (_: ConditionalCheckFailedException) {
                    // Someone re-created the row live between A and B; loop back to A.
                }
            }
            throw IllegalStateException("add($key) could not complete in $ADD_MAX_TRIES attempts; another writer kept flipping the row's live/expired state")
        }

    override suspend fun remove(key: String): Unit =
        telemetryTrace("remove", attributes = spanAttrs(key)) {
            ready.await()
            client.deleteItem {
                it.tableName(tableName)
                it.key(mapOf("key" to AttributeValue.fromS(key)))
            }.await()
            Unit
        }

    override suspend fun <T> getAndRemove(key: String, serializer: KSerializer<T>): T? =
        telemetryTrace("getAndDelete", attributes = spanAttrs(key), dimensions = setOf(Cache.TelemetryKeys.hit)) { span ->
            ready.await()
            val response = client.deleteItem {
                it.tableName(tableName)
                it.key(mapOf("key" to AttributeValue.fromS(key)))
                it.returnValues(ReturnValue.ALL_OLD)
            }.await()
            val result = if (response.hasAttributes()) {
                val item = response.attributes()
                item["expires"]?.n()?.toLongOrNull()?.let {
                    if (System.currentTimeMillis().div(1000L) > it) return@let null
                    else serializer.fromDynamo(item["value"]!!, context)
                } ?: serializer.fromDynamo(item["value"]!!, context)
            } else null
            span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.hit, result != null) })
            result
        }

}