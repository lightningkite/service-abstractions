package com.lightningkite.services.cache.dynamodb


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
            println("DEBUG PREVIEW: ${try {
                client.getItem {
                    it.tableName(tableName)
                    it.consistentRead(true)
                    it.key(mapOf("key" to AttributeValue.fromS(key)))
                }.await().toString()
            } catch(e: Exception) {
                "nah"
            }}")
            println("NOW: ${now().epochSeconds}")
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
            println("FAILED CONDITIONAL CHECK: ${e.message}")
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