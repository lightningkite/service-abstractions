package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.dynamodb.embeddedDynamo
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Integration tests for DynamoDbPubSub using DynamoDB Local.
 *
 * These tests verify actual DynamoDB operations including:
 * - Table auto-creation
 * - Message emit and collect
 * - Message ordering
 * - Multiple subscribers
 * - Performance characteristics
 *
 * To run against real AWS DynamoDB instead of local mock, set:
 *   DYNAMODB_TEST_REAL=true
 *
 * The 'lk' AWS profile will be used for credentials.
 */
@Ignore
class DynamoDbPubSubIntegrationTest {

    companion object {
        // Check if we should use real DynamoDB
        private val useRealDynamoDB = System.getenv("DYNAMODB_TEST_REAL")?.toBoolean() == true

        // Shared DynamoDB Local instance across all tests (only used if not real)
        private val localDynamoClient by lazy { embeddedDynamo(port = 7998) }

        // Real AWS DynamoDB client using 'lk' profile
        private val realDynamoClient by lazy {
            DynamoDbAsyncClient.builder()
                .credentialsProvider(ProfileCredentialsProvider.create("lk"))
                .region(Region.US_WEST_2)
                .build()
        }

        // Get the appropriate client
        val dynamoClient: DynamoDbAsyncClient
            get() = if (useRealDynamoDB) realDynamoClient else localDynamoClient

        // Track created tables for cleanup
        private val createdTables = mutableSetOf<String>()

        fun trackTable(tableName: String) {
            if (useRealDynamoDB) {
                createdTables.add(tableName)
            }
        }

        // Call this to clean up tables created during tests
        suspend fun cleanupTables() {
            if (useRealDynamoDB && createdTables.isNotEmpty()) {
                println("Cleaning up ${createdTables.size} test tables...")
                for (tableName in createdTables.toList()) {
                    try {
                        realDynamoClient.deleteTable(
                            DeleteTableRequest.builder().tableName(tableName).build()
                        ).await()
                        println("  Deleted table: $tableName")
                        createdTables.remove(tableName)
                    } catch (e: Exception) {
                        println("  Failed to delete table $tableName: ${e.message}")
                    }
                }
            }
        }
    }

    init {
        // Ensure URL scheme is registered
        DynamoDbPubSub
        if (useRealDynamoDB) {
            println("*** RUNNING WITH REAL AWS DYNAMODB (us-west-2, 'lk' profile) ***")
        }
    }

    // Table prefix for real DynamoDB tests (to easily identify and clean up)
    private val tablePrefix = if (useRealDynamoDB) "pubsub-test-" else "test-"

    private fun createPubSub(baseName: String, pollIntervalMs: Long = 20): DynamoDbPubSub {
        val tableName = "$tablePrefix$baseName-${System.currentTimeMillis()}"
        trackTable(tableName)
        val context = TestSettingContext()
        return DynamoDbPubSub(
            name = "test",
            makeClient = { dynamoClient },
            tableName = tableName,
            context = context,
            pollInterval = pollIntervalMs.milliseconds,
        )
    }

    @Serializable
    data class TestMessage(
        val id: String,
        val value: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class ComplexMessage(
        val id: String,
        val nested: NestedData,
        val list: List<Int>,
        val map: Map<String, String>
    )

    @Serializable
    data class NestedData(
        val value: Double,
        val flag: Boolean
    )

    @Test
    fun `table is auto-created on first emit`() = runBlocking {
        val pubsub = createPubSub("autocreate")
        val channel = pubsub.string("test-channel")

        // This should trigger table creation
        channel.emit("hello")

        // If we got here without exception, table was created successfully
    }

    @Test
    fun `emit and collect single message`() = runBlocking {
        val pubsub = createPubSub("single")
        val channel = pubsub.string("test-channel")

        val received = mutableListOf<String>()

        // Start collector in background
        val collectJob = launch {
            channel.collect { message ->
                received.add(message)
                if (received.size >= 1) {
                    cancel() // Stop after receiving one message
                }
            }
        }

        // Give collector time to start polling
        delay(100)

        // Emit message
        channel.emit("test-message-1")

        // Wait for collection
        withTimeout(5.seconds) {
            collectJob.join()
        }

        assertEquals(1, received.size)
        assertEquals("test-message-1", received[0])
    }

    @Test
    fun `emit and collect multiple messages in order`() = runBlocking {
        val pubsub = createPubSub("multiple")
        val channel = pubsub.get("test-channel", TestMessage.serializer())

        val received = mutableListOf<TestMessage>()
        val expectedCount = 5

        // Start collector
        val collectJob = launch {
            channel.collect { message ->
                received.add(message)
                if (received.size >= expectedCount) {
                    cancel()
                }
            }
        }

        delay(100)

        // Emit messages
        repeat(expectedCount) { i ->
            channel.emit(TestMessage(id = "msg-$i", value = i))
            delay(10) // Small delay between messages
        }

        withTimeout(5.seconds) {
            collectJob.join()
        }

        assertEquals(expectedCount, received.size)
        // Verify ordering
        received.forEachIndexed { index, msg ->
            assertEquals("msg-$index", msg.id)
            assertEquals(index, msg.value)
        }
    }

    @Test
    fun `multiple subscribers receive same messages`() = runBlocking {
        val pubsub = createPubSub("multi-sub")
        val channel = pubsub.string("shared-channel")

        val received1 = mutableListOf<String>()
        val received2 = mutableListOf<String>()
        val expectedCount = 3

        // Start two collectors
        val collectJob1 = launch {
            channel.collect { message ->
                received1.add(message)
                if (received1.size >= expectedCount) cancel()
            }
        }

        val collectJob2 = launch {
            channel.collect { message ->
                received2.add(message)
                if (received2.size >= expectedCount) cancel()
            }
        }

        delay(100)

        // Emit messages
        repeat(expectedCount) { i ->
            channel.emit("message-$i")
            delay(10)
        }

        withTimeout(5.seconds) {
            collectJob1.cancelAndJoin()
            collectJob2.cancelAndJoin()
        }

        assertEquals(expectedCount, received1.size)
        assertEquals(expectedCount, received2.size)
        assertEquals(received1, received2)
    }

    @Test
    fun `different channels are isolated`() = runBlocking {
        val pubsub = createPubSub("isolated")

        // Ensure table is fully created before starting the test
        pubsub.healthCheck()

        val channel1 = pubsub.string("channel-1")
        val channel2 = pubsub.string("channel-2")

        val received1 = mutableListOf<String>()
        val received2 = mutableListOf<String>()

        val collectJob1 = launch {
            channel1.collect { message ->
                received1.add(message)
                if (received1.size >= 2) cancel()
            }
        }

        val collectJob2 = launch {
            channel2.collect { message ->
                received2.add(message)
                if (received2.size >= 2) cancel()
            }
        }

        delay(200) // Increased delay to ensure collectors are ready

        // Emit to both channels
        channel1.emit("ch1-msg1")
        channel2.emit("ch2-msg1")
        delay(10)
        channel1.emit("ch1-msg2")
        channel2.emit("ch2-msg2")

        withTimeout(5.seconds) {
            collectJob1.join()
            collectJob2.join()
        }

        assertEquals(listOf("ch1-msg1", "ch1-msg2"), received1)
        assertEquals(listOf("ch2-msg1", "ch2-msg2"), received2)
    }

    @Test
    fun `emit latency is fast`() = runBlocking {
        val pubsub = createPubSub("latency")
        val channel = pubsub.string("perf-channel")

        // Warm up
        channel.emit("warmup")

        // Measure emit latency
        val times = mutableListOf<Long>()
        repeat(10) {
            val time = measureTime {
                channel.emit("test-$it")
            }
            times.add(time.inWholeMilliseconds)
        }

        val avgLatency = times.average()
        val maxLatency = times.max()

        println("Emit latency: avg=${avgLatency}ms, max=${maxLatency}ms")

        // Should be under 100ms for local DynamoDB
        assertTrue(avgLatency < 100, "Average emit latency should be under 100ms, was ${avgLatency}ms")
    }

    @Test
    fun `high frequency message passing works`() = runBlocking {
        val pubsub = createPubSub("highfreq", pollIntervalMs = 10)
        val channel = pubsub.string("highfreq-channel")

        val messageCount = 50
        val received = mutableListOf<String>()

        val collectJob = launch {
            channel.collect { message ->
                received.add(message)
                if (received.size >= messageCount) cancel()
            }
        }

        delay(100)

        // Emit messages at high frequency (simulating audio packets)
        val emitTime = measureTime {
            repeat(messageCount) { i ->
                channel.emit("packet-$i")
                delay(20) // 20ms between packets (like Twilio audio)
            }
        }

        withTimeout(10.seconds) {
            collectJob.join()
        }

        println("Emitted $messageCount messages in ${emitTime.inWholeMilliseconds}ms")
        assertEquals(messageCount, received.size)

        // Verify all messages received (may be out of order due to timing)
        val expectedMessages = (0 until messageCount).map { "packet-$it" }.toSet()
        assertEquals(expectedMessages, received.toSet())
    }

    @Test
    fun `collector only receives messages after starting`() = runBlocking {
        val pubsub = createPubSub("timing")
        val channel = pubsub.string("timing-channel")

        // Emit message BEFORE starting collector
        channel.emit("before-collect")
        delay(100)

        val received = mutableListOf<String>()

        // Now start collector
        val collectJob = launch {
            channel.collect { message ->
                received.add(message)
                if (received.size >= 1) cancel()
            }
        }

        delay(100)

        // Emit message AFTER collector started
        channel.emit("after-collect")

        withTimeout(5.seconds) {
            collectJob.join()
        }

        // Should only receive the message sent after collector started
        assertEquals(1, received.size)
        assertEquals("after-collect", received[0])
    }

    @Test
    fun `round-trip latency with 5 messages`() = runBlocking {
        val pubsub = createPubSub("roundtrip", pollIntervalMs = 10)
        val channel = pubsub.string("latency-channel")

        // Warm up - ensure table exists and collector is ready
        val warmupReceived = CompletableDeferred<Unit>()
        val warmupJob = launch {
            channel.collect {
                warmupReceived.complete(Unit)
                cancel()
            }
        }
        delay(100)
        channel.emit("warmup")
        withTimeout(5.seconds) { warmupReceived.await() }
        warmupJob.join()

        // Measure round-trip latency for 5 messages
        val messageCount = 5
        val latencies = mutableListOf<Long>()

        repeat(messageCount) { i ->
            val receiveDeferred = CompletableDeferred<Long>()
            val sendTime = System.currentTimeMillis()

            val collectJob = launch {
                channel.collect { message ->
                    if (message == "latency-$i") {
                        val receiveTime = System.currentTimeMillis()
                        receiveDeferred.complete(receiveTime - sendTime)
                        cancel()
                    }
                }
            }

            delay(50) // Ensure collector is polling

            channel.emit("latency-$i")

            val latency = withTimeout(5.seconds) { receiveDeferred.await() }
            latencies.add(latency)
            collectJob.join()

            delay(20) // Small gap between measurements
        }

        val avgLatency = latencies.average()
        val minLatency = latencies.min()
        val maxLatency = latencies.max()

        println("=== Round-trip Latency Test (5 messages) ===")
        println("Individual latencies: ${latencies.joinToString("ms, ")}ms")
        println("Average latency: ${avgLatency}ms")
        println("Min: ${minLatency}ms, Max: ${maxLatency}ms")
        println("============================================")

        // With 10ms polling, round-trip should typically be under 50ms on average
        assertTrue(avgLatency < 200, "Average round-trip latency should be under 200ms, was ${avgLatency}ms")
    }

    @Test
    fun `complex serializable types work`() = runBlocking {
        val pubsub = createPubSub("complex")
        val channel = pubsub.get("complex-channel", ComplexMessage.serializer())

        val testMessage = ComplexMessage(
            id = "complex-1",
            nested = NestedData(value = 3.14, flag = true),
            list = listOf(1, 2, 3, 4, 5),
            map = mapOf("key1" to "value1", "key2" to "value2")
        )

        val received = mutableListOf<ComplexMessage>()

        val collectJob = launch {
            channel.collect { message ->
                received.add(message)
                cancel()
            }
        }

        delay(100)
        channel.emit(testMessage)

        withTimeout(5.seconds) {
            collectJob.join()
        }

        assertEquals(1, received.size)
        assertEquals(testMessage, received[0])
    }

    @Test
    fun `zzz cleanup test tables`() = runBlocking {
        // This test runs last (alphabetically) and cleans up all tables created during tests
        cleanupTables()
    }
}
