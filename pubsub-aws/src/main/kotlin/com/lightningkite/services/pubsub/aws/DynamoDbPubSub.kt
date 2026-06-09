package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.SettingContext
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.get
import com.lightningkite.services.metricsTrace
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.util.concurrent.atomic.AtomicBoolean
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
     * slight reordering is acceptable (handled by jitter buffer for audio agent).
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
                val regex =
                    Regex("""dynamodb-pubsub://(?:(?<access>[^:]+):(?<secret>[^@]+)@)?(?<region>[^/]+)/(?<tableName>[^?]+)(?:\?(?<params>.+))?""")
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
                            .apply {
                                context[AwsConnections].clientOverrideConfiguration?.let { overrideConfiguration(it) }
                            }
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

    // One-shot lazy initialization: the first caller runs doInitialize(); all others suspend
    // on the same Deferred until it completes (or fails). A failed initialization permanently
    // fails all future callers — retrying would only hammer DynamoDB with the same failing
    // describe/create-table call.
    private val readyDeferred = CompletableDeferred<Unit>()
    private val initStarted = AtomicBoolean(false)

    private suspend fun ensureReady() {
        if (initStarted.compareAndSet(false, true)) {
            try {
                doInitialize()
                readyDeferred.complete(Unit)
            } catch (t: Throwable) {
                readyDeferred.completeExceptionally(t)
                throw t
            }
        }
        readyDeferred.await()
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
                        AttributeDefinition.builder().attributeName("channel").attributeType(ScalarAttributeType.S)
                            .build(),
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
        // Note: We don't reset readyDeferred or close the client here. The AWS SDK
        // handles connection pooling internally, so subsequent operations can reuse
        // the already-initialized state. Explicitly closing would prevent reuse after
        // disconnect/reconnect cycles.
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> =
        channelImpl(
            key = key,
            encode = { json.encodeToString(serializer, it) },
            decode = { json.decodeFromString(serializer, it) },
        )

    override fun string(key: String): PubSubChannel<String> =
        channelImpl(key = key, encode = { it }, decode = { it })

    /**
     * Builds a [PubSubChannel] parameterized by how the in-flight `T` is serialized to / from the
     * DynamoDB `message` String. All DynamoDB I/O (counter increment, putItem, polling query, OTel
     * spans, error backoff) is identical across typed and untyped channels — only the codec differs.
     */
    private fun <T> channelImpl(
        key: String,
        encode: (T) -> String,
        decode: (String) -> T,
    ): PubSubChannel<T> = object : PubSubChannel<T> {
        override suspend fun emit(value: T) {
            ensureReady()
            metricsTrace(
                "publish",
                attributes = MetricAttributes(
                    mapOf(
                        "messaging.system" to "dynamodb",
                        "messaging.destination" to key,
                        "messaging.operation" to "publish",
                    )
                )
            ) {
                val message = encode(value)
                val now = System.currentTimeMillis()

                val seq: Long = if (fastEmit) {
                    // Fast path: Use timestamp + random suffix (single DynamoDB operation)
                    // Multiply by 1000 to leave room for random suffix, add random 0-999
                    now * 1000 + Random.nextInt(1000)
                } else {
                    // Strict ordering path: Atomically increment counter (2 DynamoDB operations)
                    val counterResult = client.updateItem {
                        it.tableName(tableName)
                        it.key(
                            mapOf(
                                "channel" to AttributeValue.fromS(key),
                                "seq" to AttributeValue.fromN("0") // seq=0 is reserved for counter
                            )
                        )
                        it.updateExpression("SET #c = if_not_exists(#c, :zero) + :one")
                        it.expressionAttributeNames(mapOf("#c" to "counter"))
                        it.expressionAttributeValues(
                            mapOf(
                                ":zero" to AttributeValue.fromN("0"),
                                ":one" to AttributeValue.fromN("1")
                            )
                        )
                        it.returnValues(ReturnValue.UPDATED_NEW)
                    }.await()

                    counterResult.attributes()["counter"]?.n()?.toLong()
                        ?: throw IllegalStateException("Failed to get counter value")
                }

                logger.trace { "EMIT channel=$key seq=$seq" }

                client.putItem {
                    it.tableName(tableName)
                    it.item(
                        mapOf(
                            "channel" to AttributeValue.fromS(key),
                            "seq" to AttributeValue.fromN(seq.toString()),
                            "message" to AttributeValue.fromS(message),
                            "expires" to AttributeValue.fromN(((now / 1000) + messageTtl.inWholeSeconds).toString())
                        )
                    )
                }.await()
            }
        }

        override suspend fun collect(collector: FlowCollector<T>) {
            ensureReady()

            // Query DynamoDB for current max seq - uses DynamoDB as source of truth, no clock sync needed.
            // Filter on attribute_exists(message) so the seq=0 counter row (which has `counter`
            // but no `message`) is skipped — otherwise strict-ordering mode bootstraps from the
            // counter row whose seq tracks the latest message and we miss messages.
            val maxSeqResponse = client.query {
                it.tableName(tableName)
                it.keyConditionExpression("channel = :c")
                it.filterExpression("attribute_exists(#m)")
                it.expressionAttributeNames(mapOf("#m" to "message"))
                it.expressionAttributeValues(mapOf(":c" to AttributeValue.fromS(key)))
                it.scanIndexForward(false) // Newest first
                it.limit(1)
            }.await()

            var lastSeq = maxSeqResponse.items().firstOrNull()?.get("seq")?.n() ?: "0"
            var consecutiveErrors = 0

            logger.debug { "COLLECT channel=$key starting lastSeq=$lastSeq (from DynamoDB)" }

            while (coroutineContext.isActive) {
                metricsTrace(
                    "poll",
                    attributes = MetricAttributes(
                        mapOf(
                            "messaging.system" to "dynamodb",
                            "messaging.destination" to key,
                        )
                    )
                ) { pollSpan ->
                    try {
                        val response = client.query {
                            it.tableName(tableName)
                            it.keyConditionExpression("channel = :c AND seq > :s")
                            it.expressionAttributeValues(
                                mapOf(
                                    ":c" to AttributeValue.fromS(key),
                                    ":s" to AttributeValue.fromN(lastSeq)
                                )
                            )
                            it.scanIndexForward(true) // Oldest first
                        }.await()

                        consecutiveErrors = 0 // Reset on success
                        pollSpan.enrich(MetricAttributes(mapOf("messaging.batch.message_count" to response.count().toLong())))

                        for (item in response.items()) {
                            val message = item["message"]?.s() ?: continue
                            val seq = item["seq"]?.n() ?: continue
                            lastSeq = seq

                            logger.trace { "RECV channel=$key seq=$seq" }

                            try {
                                val value = decode(message)
                                collector.emit(value)
                            } catch (e: CancellationException) {
                                // Rethrow CancellationException (includes AbortFlowException from first(), take(), etc.)
                                throw e
                            } catch (e: Exception) {
                                // Skip malformed messages (deserialization errors). The string channel's
                                // decode is the identity function and can't throw, so this only fires for typed channels.
                                logger.debug(e) { "Failed to deserialize message channel=$key seq=$seq" }
                            }
                        }

                        delay(pollInterval)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The poll loop swallows errors to back off rather than rethrow, so the span
                        // would otherwise complete "ok"; report explicitly to keep error telemetry.
                        context.reportException(e)
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
