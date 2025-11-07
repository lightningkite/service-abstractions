package com.lightningkite.services.cache.dynamodbkmp

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.lightningkite.services.SettingContext
import com.lightningkite.services.cache.Cache
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * AWS DynamoDB implementation of the Cache abstraction using Kotlin Multiplatform SDK.
 *
 * Stores cache entries in a DynamoDB table with automatic table creation and TTL management.
 * This is a KMP-compatible implementation (supports JVM, JS, Native targets).
 *
 * ## Features
 *
 * - **Auto-provisioned tables**: Creates table automatically if it doesn't exist
 * - **TTL support**: Native DynamoDB TTL for automatic expiration (uses `expires` attribute)
 * - **Conditional writes**: Atomic setIfNotExists via DynamoDB condition expressions
 * - **Pay-per-request billing**: Uses on-demand billing mode (no capacity planning)
 * - **Strong consistency**: All reads use consistent reads
 *
 * ## Supported URL Schemes
 *
 * - `dynamodb-kmp://us-east-1/cache-table` - KMP DynamoDB cache
 *
 * Format: `dynamodb-kmp://[region]/[tableName]`
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Production cache in us-east-1
 * Cache.Settings("dynamodb-kmp://us-east-1/prod-cache")
 *
 * // Development cache in eu-west-1
 * Cache.Settings("dynamodb-kmp://eu-west-1/dev-cache")
 * ```
 *
 * ## Implementation Notes
 *
 * - **Serialization**: Values stored as JSON strings in the `value` attribute
 * - **Table schema**: Primary key is `key` (String), with optional `expires` (Number) for TTL
 * - **Auto-provisioning**: Creates table on first access if missing (waits for ACTIVE status)
 * - **TTL enablement**: Automatically enables TTL on the `expires` attribute
 * - **Credentials**: Uses AWS default credential chain (env vars, IAM roles, etc.)
 *
 * ## Important Gotchas
 *
 * - **No CAS**: This implementation doesn't support atomic compareAndSet (uses default retry-based modify)
 * - **Table creation latency**: First access may be slow (~10-30 seconds for table creation)
 * - **TTL delay**: DynamoDB TTL is best-effort, may take minutes to hours to expire items
 * - **get() checks expiration**: Manually filters expired items (don't rely solely on TTL deletion)
 * - **Costs**: Pay-per-request billing charges per operation (economical for low traffic)
 * - **Strong consistency**: All reads use consistent reads (higher cost than eventually consistent)
 *
 * ## Comparison with cache-dynamodb (JVM-only)
 *
 * - **KMP vs JVM**: This module supports all KMP targets, `cache-dynamodb` is JVM-only
 * - **No CAS**: This implementation lacks atomic CAS, JVM version may support it
 * - **SDK**: Uses AWS SDK for Kotlin (KMP), JVM uses AWS SDK for Java v2
 *
 * @property name Service name for logging/metrics
 * @property region AWS region (e.g., "us-east-1")
 * @property tableName DynamoDB table name for cache storage
 * @property context Service context with serializers
 */
public class DynamoDbCacheKmp(
    override val name: String,
    private val region: String,
    private val tableName: String,
    override val context: SettingContext,
) : Cache {

    private val client: DynamoDbClient by lazy {
        DynamoDbClient { this.region = this@DynamoDbCacheKmp.region }
    }

    private fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        serializersModule = context.internalSerializersModule
    }

    private suspend fun ensureReady() {
        try {
            // Ensure table exists
            val desc = client.describeTable { this.tableName = tableName }
            if (desc.table?.tableStatus != TableStatus.Active) {
                while (client.describeTable { this.tableName = tableName }.table?.tableStatus != TableStatus.Active) {
                    delay(100)
                }
            }
        } catch (e: ResourceNotFoundException) {
            client.createTable {
                this.tableName = tableName
                this.billingMode = BillingMode.PayPerRequest
                this.keySchema = listOf(KeySchemaElement { attributeName = "key"; keyType = KeyType.Hash })
                this.attributeDefinitions = listOf(AttributeDefinition { attributeName = "key"; attributeType = ScalarAttributeType.S })
            }
            while (client.describeTable { this.tableName = tableName }.table?.tableStatus != TableStatus.Active) {
                delay(100)
            }
        }
        try {
            val ttl = client.describeTimeToLive { this.tableName = tableName }
            if (ttl.timeToLiveDescription?.timeToLiveStatus == TimeToLiveStatus.Disabled) {
                client.updateTimeToLive {
                    this.tableName = tableName
                    this.timeToLiveSpecification = TimeToLiveSpecification {
                        enabled = true
                        attributeName = "expires"
                    }
                }
                // Wait a short time for TTL enablement to propagate
                delay(250)
            }
        } catch (_: Exception) {
            // best-effort; ignore if not permitted
        }
    }

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        ensureReady()
        val r = client.getItem {
            tableName = this@DynamoDbCacheKmp.tableName
            consistentRead = true
            key = mapOf("key" to AttributeValue { s = key })
        }
        val item = r.item ?: return null
        item["expires"]?.n?.toLongOrNull()?.let { exp ->
            if (System.currentTimeMillis().div(1000L) > exp) return null
        }
        val jsonValue = item["value"]?.s ?: return null
        return json().decodeFromString(serializer, jsonValue)
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        ensureReady()
        val item = buildMap<String, AttributeValue> {
            put("key", AttributeValue { s = key })
            put("value", AttributeValue { s = json().encodeToString(serializer, value) })
            if (timeToLive != null) put("expires", AttributeValue { n = Clock.System.now().plus(timeToLive).epochSeconds.toString() })
        }
        client.putItem {
            tableName = this@DynamoDbCacheKmp.tableName
            this.item = item
        }
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean {
        ensureReady()
        return try {
            val item = buildMap<String, AttributeValue> {
                put("key", AttributeValue { s = key })
                put("value", AttributeValue { s = json().encodeToString(serializer, value) })
                if (timeToLive != null) put("expires", AttributeValue { n = Clock.System.now().plus(timeToLive).epochSeconds.toString() })
            }
            client.putItem {
                tableName = this@DynamoDbCacheKmp.tableName
                this.item = item
                conditionExpression = "attribute_not_exists(#k)"
                expressionAttributeNames = mapOf("#k" to "key")
            }
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        }
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        ensureReady()
        val now = Clock.System.now().epochSeconds
        client.updateItem {
            tableName = this@DynamoDbCacheKmp.tableName
            this.key = mapOf("key" to AttributeValue { s = key })
            // Only update if not expired
            conditionExpression = "attribute_not_exists(#exp) OR #exp > :now"
            updateExpression = buildString {
                append("SET #v = if_not_exists(#v, :z) + :v")
                if (timeToLive != null) append(", #exp = :exp")
            }
            expressionAttributeNames = mapOf(
                "#v" to "value",
                "#exp" to "expires",
            )
            expressionAttributeValues = buildMap {
                put(":now", AttributeValue { n = now.toString() })
                put(":z", AttributeValue { n = "0" })
                put(":v", AttributeValue { n = value.toString() })
                if (timeToLive != null) put(":exp", AttributeValue { n = Clock.System.now().plus(timeToLive).epochSeconds.toString() })
            }
        }
    }

    override suspend fun remove(key: String) {
        ensureReady()
        client.deleteItem {
            tableName = this@DynamoDbCacheKmp.tableName
            this.key = mapOf("key" to AttributeValue { s = key })
        }
    }

    public companion object {
        public fun Cache.Settings.Companion.dynamoDbKmp(region: String, tableName: String): Cache.Settings =
            Cache.Settings("dynamodb-kmp://$region/$tableName")

        init {
            Cache.Settings.register("dynamodb-kmp") { name, url, context ->
                val regex = Regex("""dynamodb-kmp://(?<region>[^/]+)/(?<tableName>.+)""")
                val match = regex.matchEntire(url)
                    ?: throw IllegalStateException("Invalid dynamodb-kmp URL. Expected dynamodb-kmp://[region]/[tableName]")
                val region = match.groups["region"]!!.value
                val table = match.groups["tableName"]!!.value
                DynamoDbCacheKmp(name, region, table, context)
            }
        }
    }
}
