package com.lightningkite.services.cache.dynamodb

/**
 * AWS DynamoDB implementation of the Cache abstraction using AWS SDK for Java v2.
 *
 * Stores cache entries in a DynamoDB table with automatic table creation and TTL management.
 * This is the JVM-only implementation (for KMP support, see cache-dynamodb-kmp).
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
 * ## Comparison with cache-dynamodb-kmp
 *
 * - **JVM-only**: This module is JVM/Android only, KMP version supports all targets
 * - **Native types**: Uses AttributeValue serialization, KMP version uses JSON strings
 * - **SDK**: Uses AWS SDK for Java v2, KMP uses AWS SDK for Kotlin
 * - **AwsConnections**: Integrates with shared HTTP client pool for better resource management
 *
 * @property name Service name for logging/metrics
 * @property makeClient Lazy factory for creating DynamoDB client (enables disconnect/reconnect)
 * @property tableName DynamoDB table name for cache storage
 * @property context Service context with serializers and AwsConnections
 */

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.get
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import kotlin.text.get
import kotlin.time.Clock.System.now
import kotlin.time.Duration

public class DynamoDbCache(
    override val name: String,
    public val makeClient: () -> DynamoDbAsyncClient,
    public val tableName: String = "cache",
    override val context: SettingContext,
) : Cache {
    public val client: DynamoDbAsyncClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED, makeClient)

    public companion object {
        public fun Cache.Settings.Companion.dynamoDbLocal(): Cache.Settings = Cache.Settings("dynamodb-local")
        public fun Cache.Settings.Companion.dynamoDb(
            region: Region,
            tableName: String
        ): Cache.Settings = Cache.Settings("dynamodb://$region/$tableName")
        public fun Cache.Settings.Companion.dynamoDb(
            accessKey: String,
            secretKey: String,
            region: Region,
            tableName: String
        ): Cache.Settings = Cache.Settings("dynamodb://$accessKey:$secretKey@$region/$tableName")
        init {
            Cache.Settings.register("dynamodb-local") { name, url, context ->
                DynamoDbCache(name, { embeddedDynamo() }, context = context)
            }
            Cache.Settings.register("dynamodb") { name, url, context ->
                Regex("""dynamodb://(?:(?<access>[^:]+):(?<secret>[^@]+)@)?(?<region>[^/]+)/(?<tableName>.+)""").matchEntire(url)?.let { match ->
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
        return listOf(super.healthCheck(), context[AwsConnections].health).maxBy { it.level }
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

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        ready.await()
        val r = client.getItem {
            it.tableName(tableName)
            it.consistentRead(true)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
        }.await()
        if (r.hasItem()) {
            val item = r.item()
            item["expires"]?.n()?.toLongOrNull()?.let {
                if (System.currentTimeMillis().div(1000L) > it) return null
            }
            return serializer.fromDynamo(item["value"]!!, context)
        } else return null
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        ready.await()
        client.putItem {
            it.tableName(tableName)
            it.item(mapOf(
                "key" to AttributeValue.fromS(key),
                "value" to serializer.toDynamo(value, context),
            ) + (timeToLive?.let {
                mapOf("expires" to AttributeValue.fromN(now().plus(it).epochSeconds.toString()))
            } ?: mapOf()))
        }.await()
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean {
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
        } catch (e: ConditionalCheckFailedException) {
            return false
        }
        return true
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        ready.await()
        try {
            client.updateItem {
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
            }.await()
        } catch(e: ConditionalCheckFailedException) {
            set(key, value, Int.serializer(), timeToLive)
        }
    }

    override suspend fun remove(key: String) {
        ready.await()
        client.deleteItem {
            it.tableName(tableName)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
        }.await()
    }

}