package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.get
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.dynamodb.model.ReturnValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("DynamoDbPubSub")

/**
 * DynamoDB-based PubSub implementation optimized for AWS Lambda environments.
 *
 * Unlike [AwsWebSocketPubSub] which requires establishing a WebSocket connection for each
 * emit (slow in Lambda due to connection overhead), this implementation uses DynamoDB
 * PutItem/Query which benefits from AWS SDK's internal connection pooling.
 *
 * ## Performance Characteristics
 *
 * - **emit()**: ~5-20ms (DynamoDB PutItem, no connection overhead)
 * - **collect()**: Polls every [pollInterval] (default 15ms)
 * - **Latency**: Messages are delivered within [pollInterval] + DynamoDB latency
 *
 * ## Use Cases
 *
 * This is ideal for:
 * - Lambda-to-Lambda communication where low latency is critical
 * - High-frequency message passing (e.g., real-time audio forwarding)
 * - Scenarios where WebSocket connection overhead is problematic
 *
 * ## Table Schema
 *
 * The DynamoDB table uses:
 * - `channel` (String, partition key) - The channel name
 * - `seq` (Number, sort key) - Unique sequence for ordering
 * - `message` (String) - The serialized message content
 * - `expires` (Number) - TTL timestamp for automatic cleanup
 *
 * ## Configuration
 *
 * ```kotlin
 * // Using default AWS credentials (IAM role, env vars, etc.)
 * PubSub.Settings("dynamodb-pubsub://us-west-2/my-pubsub-table")
 *
 * // With explicit credentials
 * PubSub.Settings("dynamodb-pubsub://ACCESS:SECRET@us-west-2/my-pubsub-table")
 *
 * // With custom poll interval (in ms)
 * PubSub.Settings("dynamodb-pubsub://us-west-2/my-pubsub-table?pollInterval=10")
 * ```
 *
 * ## Important Notes
 *
 * - Messages are automatically deleted after [messageTtl] (default 5 minutes)
 * - Subscribers only receive messages published after they start collecting
 * - Multiple subscribers on the same channel each receive all messages
 * - Message ordering is preserved per channel
 *
 * @property name Service name for logging/metrics
 * @property makeClient Factory for creating DynamoDB client
 * @property tableName DynamoDB table name
 * @property context Service context with serializers
 * @property pollInterval How often to poll for new messages (default 15ms)
 * @property messageTtl How long messages are kept before TTL deletion (default 5 minutes)
 */
public class DynamoDbPubSub(
    override val name: String,
    public val makeClient: () -> DynamoDbAsyncClient,
    public val tableName: String,
    override val context: SettingContext,
    public val pollInterval: Duration = 15.milliseconds,
    public val messageTtl: Duration = 5.minutes,
    /**
     * When true, uses timestamp-based ordering instead of atomic counter.
     * This reduces emit from 2 DynamoDB operations to 1, roughly halving latency.
     * Trade-off: Messages within the same millisecond may not be strictly ordered.
     * Recommended for high-throughput scenarios like real-time audio where
     * slight reordering is acceptable (handled by jitter buffer).
     */
    public val fastEmit: Boolean = false,
) : PubSub {

    public val client: DynamoDbAsyncClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED, makeClient)

    private val json = Json {
        serializersModule = context.internalSerializersModule
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Maximum backoff delay for polling errors
    private val maxBackoff = 30.seconds

    public companion object {
        init {
            PubSub.Settings.register("dynamodb-pubsub") { name, url, context ->
                // Parse URL: dynamodb-pubsub://[access:secret@]region/tableName[?pollInterval=ms]
                val regex = Regex("""dynamodb-pubsub://(?:(?<access>[^:]+):(?<secret>[^@]+)@)?(?<region>[^/]+)/(?<tableName>[^?]+)(?:\?(?<params>.+))?""")
                val match = regex.matchEntire(url)
                    ?: throw IllegalStateException("Invalid dynamodb-pubsub URL. Expected: dynamodb-pubsub://[access:secret@]region/tableName[?pollInterval=ms]")

                val access = match.groups["access"]?.value ?: ""
                val secret = match.groups["secret"]?.value ?: ""
                val region = match.groups["region"]!!.value
                val tableName = match.groups["tableName"]!!.value
                val params = match.groups["params"]?.value?.split("&")
                    ?.associate { it.substringBefore("=") to it.substringAfter("=") }
                    ?: emptyMap()

                val pollInterval = params["pollInterval"]?.toLongOrNull()?.milliseconds ?: 15.milliseconds
                val fastEmit = params["fastEmit"]?.toBoolean() ?: false

                DynamoDbPubSub(
                    name = name,
                    makeClient = {
                        DynamoDbAsyncClient.builder()
                            .credentialsProvider(
                                if (access.isNotBlank() && secret.isNotBlank()) {
                                    StaticCredentialsProvider.create(object : AwsCredentials {
                                        override fun accessKeyId(): String = access
                                        override fun secretAccessKey(): String = secret
                                    })
                                } else DefaultCredentialsProvider.builder().build()
                            )
                            .httpClient(context[AwsConnections].asyncClient)
                            .region(Region.of(region))
                            .build()
                    },
                    tableName = tableName,
                    context = context,
                    pollInterval = pollInterval,
                    fastEmit = fastEmit,
                )
            }
        }
    }

    @Volatile
    private var initialized = false
    private val initLock = Any()

    private suspend fun ensureReady() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
        }
        // Do the actual initialization outside the lock (it's suspend)
        doInitialize()
        synchronized(initLock) {
            initialized = true
        }
    }

    private suspend fun doInitialize() {
        // First, ensure the table exists and is ACTIVE
        try {
            val tableStatus = client.describeTable { it.tableName(tableName) }.await().table().tableStatus()
            if (tableStatus != TableStatus.ACTIVE) {
                // Table exists but is still being created - wait for it
                waitForTableActive()
            }
        } catch (e: ResourceNotFoundException) {
            // Table doesn't exist - create it
            try {
                client.createTable {
                    it.tableName(tableName)
                    it.billingMode(BillingMode.PAY_PER_REQUEST)
                    it.keySchema(
                        KeySchemaElement.builder().attributeName("channel").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("seq").keyType(KeyType.RANGE).build()
                    )
                    it.attributeDefinitions(
                        AttributeDefinition.builder().attributeName("channel").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("seq").attributeType(ScalarAttributeType.N).build()
                    )
                }.await()
            } catch (e: ResourceInUseException) {
                // Another instance is already creating the table - that's fine
            }
            waitForTableActive()
        }

        // Now the table is ACTIVE - check and enable TTL if needed
        try {
            val ttlStatus = client.describeTimeToLive {
                it.tableName(tableName)
            }.await().timeToLiveDescription().timeToLiveStatus()

            if (ttlStatus == TimeToLiveStatus.DISABLED) {
                client.updateTimeToLive {
                    it.tableName(tableName)
                    it.timeToLiveSpecification {
                        it.enabled(true)
                        it.attributeName("expires")
                    }
                }.await()
            }
        } catch (e: Exception) {
            // TTL configuration failed - log but continue (TTL is nice to have, not critical)
            logger.warn(e) { "Failed to configure TTL on table $tableName - messages will not auto-expire" }
        }
    }

    private suspend fun waitForTableActive() {
        repeat(60) { // Max 60 * 500ms = 30 seconds
            try {
                val status = client.describeTable { it.tableName(tableName) }.await().table().tableStatus()
                if (status == TableStatus.ACTIVE) return
            } catch (e: ResourceNotFoundException) {
                // Table was deleted while we were waiting - unusual but handle it
            }
            delay(500)
        }
        throw IllegalStateException("Timed out waiting for DynamoDB table $tableName to become ACTIVE")
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            ensureReady()
            listOf(HealthStatus(HealthStatus.Level.OK), context[AwsConnections].health).maxBy { it.level }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    override val healthCheckFrequency: Duration = 1.minutes

    override suspend fun disconnect() {
        synchronized(initLock) {
            initialized = false
        }
        // Note: We don't close the client here because it's lazily initialized
        // and the AWS SDK handles connection pooling. The client will be garbage
        // collected when the DynamoDbPubSub instance is no longer referenced.
        // Explicitly closing would prevent reuse after disconnect/reconnect cycles.
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return object : PubSubChannel<T> {
            override suspend fun emit(value: T) {
                ensureReady()
                val message = json.encodeToString(serializer, value)
                val now = System.currentTimeMillis()

                val seq: Long = if (fastEmit) {
                    // Fast path: Use timestamp + random suffix (single DynamoDB operation)
                    // Multiply by 1000 to leave room for random suffix, add random 0-999
                    now * 1000 + Random.nextInt(1000)
                } else {
                    // Strict ordering path: Atomically increment counter (2 DynamoDB operations)
                    val counterResult = client.updateItem {
                        it.tableName(tableName)
                        it.key(mapOf(
                            "channel" to AttributeValue.fromS(key),
                            "seq" to AttributeValue.fromN("0") // seq=0 is reserved for counter
                        ))
                        it.updateExpression("SET #c = if_not_exists(#c, :zero) + :one")
                        it.expressionAttributeNames(mapOf("#c" to "counter"))
                        it.expressionAttributeValues(mapOf(
                            ":zero" to AttributeValue.fromN("0"),
                            ":one" to AttributeValue.fromN("1")
                        ))
                        it.returnValues(ReturnValue.UPDATED_NEW)
                    }.await()

                    counterResult.attributes()["counter"]?.n()?.toLong()
                        ?: throw IllegalStateException("Failed to get counter value")
                }

                logger.trace { "EMIT channel=$key seq=$seq" }

                client.putItem {
                    it.tableName(tableName)
                    it.item(mapOf(
                        "channel" to AttributeValue.fromS(key),
                        "seq" to AttributeValue.fromN(seq.toString()),
                        "message" to AttributeValue.fromS(message),
                        "expires" to AttributeValue.fromN(((now / 1000) + messageTtl.inWholeSeconds).toString())
                    ))
                }.await()
            }

            override suspend fun collect(collector: FlowCollector<T>) {
                ensureReady()

                // Query DynamoDB for current max seq - uses DynamoDB as source of truth, no clock sync needed
                val maxSeqResponse = client.query {
                    it.tableName(tableName)
                    it.keyConditionExpression("channel = :c")
                    it.expressionAttributeValues(mapOf(":c" to AttributeValue.fromS(key)))
                    it.scanIndexForward(false) // Newest first
                    it.limit(1)
                }.await()

                var lastSeq = maxSeqResponse.items().firstOrNull()?.get("seq")?.n() ?: "0"
                var consecutiveErrors = 0

                logger.debug { "COLLECT channel=$key starting lastSeq=$lastSeq (from DynamoDB)" }

                while (coroutineContext.isActive) {
                    try {
                        val response = client.query {
                            it.tableName(tableName)
                            it.keyConditionExpression("channel = :c AND seq > :s")
                            it.expressionAttributeValues(mapOf(
                                ":c" to AttributeValue.fromS(key),
                                ":s" to AttributeValue.fromN(lastSeq)
                            ))
                            it.scanIndexForward(true) // Oldest first
                        }.await()

                        consecutiveErrors = 0 // Reset on success

                        for (item in response.items()) {
                            val message = item["message"]?.s() ?: continue
                            val seq = item["seq"]?.n() ?: continue
                            lastSeq = seq

                            logger.trace { "RECV channel=$key seq=$seq" }

                            try {
                                val value = json.decodeFromString(serializer, message)
                                collector.emit(value)
                            } catch (e: CancellationException) {
                                // Rethrow CancellationException (includes AbortFlowException from first(), take(), etc.)
                                throw e
                            } catch (e: Exception) {
                                // Skip malformed messages (deserialization errors)
                                logger.debug(e) { "Failed to deserialize message channel=$key seq=$seq" }
                            }

                            // Delete message after processing (TTL will clean up anyway, but this reduces storage)
                            client.deleteItem {
                                it.tableName(tableName)
                                it.key(mapOf(
                                    "channel" to AttributeValue.fromS(key),
                                    "seq" to AttributeValue.fromN(seq)
                                ))
                            }.whenComplete { _, error ->
                                if (error != null) {
                                    logger.debug(error) { "Failed to delete message channel=$key seq=$seq (TTL will clean up)" }
                                }
                            }
                        }

                        delay(pollInterval)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveErrors++
                        logger.warn(e) { "Error polling channel=$key (attempt $consecutiveErrors)" }

                        // Exponential backoff: pollInterval * 2^errors, capped at maxBackoff
                        val backoffMs = min(
                            pollInterval.inWholeMilliseconds * (1L shl min(consecutiveErrors, 10)),
                            maxBackoff.inWholeMilliseconds
                        )
                        delay(backoffMs)
                    }
                }
            }
        }
    }

    override fun string(key: String): PubSubChannel<String> {
        return object : PubSubChannel<String> {
            override suspend fun emit(value: String) {
                ensureReady()
                val now = System.currentTimeMillis()

                val seq: Long = if (fastEmit) {
                    // Fast path: Use timestamp + random suffix (single DynamoDB operation)
                    now * 1000 + Random.nextInt(1000)
                } else {
                    // Strict ordering path: Atomically increment counter (2 DynamoDB operations)
                    val counterResult = client.updateItem {
                        it.tableName(tableName)
                        it.key(mapOf(
                            "channel" to AttributeValue.fromS(key),
                            "seq" to AttributeValue.fromN("0") // seq=0 is reserved for counter
                        ))
                        it.updateExpression("SET #c = if_not_exists(#c, :zero) + :one")
                        it.expressionAttributeNames(mapOf("#c" to "counter"))
                        it.expressionAttributeValues(mapOf(
                            ":zero" to AttributeValue.fromN("0"),
                            ":one" to AttributeValue.fromN("1")
                        ))
                        it.returnValues(ReturnValue.UPDATED_NEW)
                    }.await()

                    counterResult.attributes()["counter"]?.n()?.toLong()
                        ?: throw IllegalStateException("Failed to get counter value")
                }

                logger.trace { "EMIT channel=$key seq=$seq" }

                client.putItem {
                    it.tableName(tableName)
                    it.item(mapOf(
                        "channel" to AttributeValue.fromS(key),
                        "seq" to AttributeValue.fromN(seq.toString()),
                        "message" to AttributeValue.fromS(value),
                        "expires" to AttributeValue.fromN(((now / 1000) + messageTtl.inWholeSeconds).toString())
                    ))
                }.await()
            }

            override suspend fun collect(collector: FlowCollector<String>) {
                ensureReady()

                // Query DynamoDB for current max seq - uses DynamoDB as source of truth, no clock sync needed
                val maxSeqResponse = client.query {
                    it.tableName(tableName)
                    it.keyConditionExpression("channel = :c")
                    it.expressionAttributeValues(mapOf(":c" to AttributeValue.fromS(key)))
                    it.scanIndexForward(false) // Newest first
                    it.limit(1)
                }.await()

                var lastSeq = maxSeqResponse.items().firstOrNull()?.get("seq")?.n() ?: "0"
                var consecutiveErrors = 0

                logger.debug { "COLLECT channel=$key starting lastSeq=$lastSeq (from DynamoDB)" }

                while (coroutineContext.isActive) {
                    try {
                        val response = client.query {
                            it.tableName(tableName)
                            it.keyConditionExpression("channel = :c AND seq > :s")
                            it.expressionAttributeValues(mapOf(
                                ":c" to AttributeValue.fromS(key),
                                ":s" to AttributeValue.fromN(lastSeq)
                            ))
                            it.scanIndexForward(true)
                        }.await()

                        consecutiveErrors = 0 // Reset on success

                        for (item in response.items()) {
                            val message = item["message"]?.s() ?: continue
                            val seq = item["seq"]?.n() ?: continue
                            lastSeq = seq

                            logger.trace { "RECV channel=$key seq=$seq" }

                            try {
                                collector.emit(message)
                            } catch (e: CancellationException) {
                                // Rethrow CancellationException (includes AbortFlowException from first(), take(), etc.)
                                throw e
                            }

                            // Delete message after processing (TTL will clean up anyway, but this reduces storage)
                            client.deleteItem {
                                it.tableName(tableName)
                                it.key(mapOf(
                                    "channel" to AttributeValue.fromS(key),
                                    "seq" to AttributeValue.fromN(seq)
                                ))
                            }.whenComplete { _, error ->
                                if (error != null) {
                                    logger.debug(error) { "Failed to delete message channel=$key seq=$seq (TTL will clean up)" }
                                }
                            }
                        }

                        delay(pollInterval)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveErrors++
                        logger.warn(e) { "Error polling channel=$key (attempt $consecutiveErrors)" }

                        // Exponential backoff: pollInterval * 2^errors, capped at maxBackoff
                        val backoffMs = min(
                            pollInterval.inWholeMilliseconds * (1L shl min(consecutiveErrors, 10)),
                            maxBackoff.inWholeMilliseconds
                        )
                        delay(backoffMs)
                    }
                }
            }
        }
    }
}
