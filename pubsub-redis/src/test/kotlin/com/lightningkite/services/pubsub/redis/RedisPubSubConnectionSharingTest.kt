package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.PubSubChannel
import io.lettuce.core.KillArgs
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import org.junit.AfterClass
import org.junit.BeforeClass
import redis.embedded.RedisServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that [RedisPubSub] multiplexes all subscriptions over one shared Redis connection.
 *
 * Before the sharing fix, every `collect()` call ran `client.connectPubSub()`, so N collectors meant
 * N sockets (each with a TCP + TLS handshake). These tests assert against Redis' own view of the
 * world -- `CLIENT LIST`/`connected_clients` for sockets and `PUBSUB NUMSUB`/`PUBSUB CHANNELS` for
 * subscriptions -- rather than against implementation internals.
 */
class RedisPubSubConnectionSharingTest {

    companion object {
        private const val PORT = 16381
        lateinit var server: RedisServer
        lateinit var client: RedisClient
        lateinit var admin: StatefulRedisConnection<String, String>

        @JvmStatic
        @BeforeClass
        fun startUp() {
            server = RedisServer.builder()
                .port(PORT)
                .setting("bind 127.0.0.1")
                .setting("daemonize no")
                .setting("appendonly no")
                .setting("maxmemory 64M")
                .build()
            server.start()
            client = RedisClient.create("redis://127.0.0.1:$PORT")
            admin = client.connect()
        }

        @JvmStatic
        @AfterClass
        fun shutDown() {
            admin.close()
            client.shutdown()
            server.stop()
        }
    }

    private fun connectedClients(): Int =
        admin.sync().info("clients")
            .lineSequence()
            .first { it.startsWith("connected_clients:") }
            .substringAfter(":")
            .trim()
            .toInt()

    /** How many Redis connections are subscribed to [channel], per Redis itself. */
    private fun subscriberCount(channel: String): Long =
        admin.sync().pubsubNumsub(channel)[channel] ?: 0L

    private fun activeChannels(): Set<String> = admin.sync().pubsubChannels().toSet()

    /**
     * Polls until [check] passes. Subscription setup and teardown are asynchronous, so polling a
     * condition beats sleeping a guessed interval -- it is both faster and far less flaky.
     *
     * [what] is evaluated on failure, so it can report the state that actually caused the timeout.
     */
    private suspend fun awaitUntil(timeoutMs: Long = 5_000, what: () -> String, check: () -> Boolean) {
        val result = withTimeoutOrNull(timeoutMs) {
            while (!check()) delay(10)
            true
        }
        assertTrue(result == true, "Timed out after ${timeoutMs}ms waiting for: ${what()}")
    }

    private suspend fun awaitSubscribers(channel: String, count: Long) =
        awaitUntil(
            what = { "PUBSUB NUMSUB $channel == $count, but it is ${subscriberCount(channel)}" },
            check = { subscriberCount(channel) == count },
        )

    private fun channelName(label: String) = "$label-${System.nanoTime()}"

    /** Marker published purely to prove a collector is live; never asserted on as real traffic. */
    private val probe = "__ready_probe__"

    /**
     * Starts [count] collectors on [channel] and returns their inboxes, blocking until every one has
     * proven itself live by receiving a probe.
     *
     * `PUBSUB NUMSUB` cannot serve as this barrier: with a shared connection it reports 1 the moment
     * the *first* collector subscribes, while the others are still attaching. Publishing before they
     * have all attached would silently drop messages -- Redis pub/sub has no replay -- and make these
     * tests flaky. Probes are re-published in a loop because collectors attach at staggered times.
     */
    private suspend fun CoroutineScope.startCollectors(
        channel: PubSubChannel<String>,
        count: Int,
    ): Pair<List<Channel<String>>, List<Job>> {
        val inboxes = List(count) { Channel<String>(Channel.UNLIMITED) }
        val ready = Channel<Int>(Channel.UNLIMITED)
        val jobs = inboxes.mapIndexed { index, inbox ->
            launch {
                channel.collect { message ->
                    if (message == probe) ready.trySend(index) else inbox.send(message)
                }
            }
        }
        val live = mutableSetOf<Int>()
        withTimeout(20_000) {
            while (live.size < count) {
                channel.emit(probe)
                withTimeoutOrNull(100) { while (live.size < count) live.add(ready.receive()) }
            }
        }
        return inboxes to jobs
    }

    // ---------------------------------------------------------------- connection sharing

    /**
     * The core regression test for the original bug: many collectors on one channel used to open one
     * Redis connection each.
     */
    @Test
    fun manyCollectorsOnOneChannelShareOneConnection() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("share-same")
        val channel = pubsub.string(name)
        val baseline = connectedClients()

        val (_, collectors) = startCollectors(channel, 50)

        val delta = connectedClients() - baseline
        // Old behavior: +50 connections. New behavior: +1 (the shared subscribe connection).
        assertTrue(
            delta <= 2,
            "50 collectors should share one connection; observed +$delta connections (baseline=$baseline)"
        )
        assertEquals(
            1L, subscriberCount(name),
            "Redis should see exactly one subscriber connection for $name regardless of collector count"
        )

        collectors.forEach { it.cancel() }
        awaitSubscribers(name, 0L)
    }

    /** Distinct channels multiplex over the same connection too -- Lettuce supports it natively. */
    @Test
    fun manyDistinctChannelsShareOneConnection() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val baseline = connectedClients()
        val names = (1..30).map { channelName("share-distinct-$it") }

        val collectors = names.map { name ->
            launch { pubsub.string(name).collect { } }
        }
        names.forEach { awaitSubscribers(it, 1L) }

        val delta = connectedClients() - baseline
        assertTrue(
            delta <= 2,
            "30 channels should share one connection; observed +$delta connections (baseline=$baseline)"
        )
        val active = activeChannels()
        names.forEach { assertTrue(it in active, "Channel $it should be subscribed on the shared connection") }

        collectors.forEach { it.cancel() }
        names.forEach { awaitSubscribers(it, 0L) }
    }

    // ---------------------------------------------------------------- delivery correctness

    /** Sharing one connection must not cost any subscriber its messages. */
    @Test
    fun everyCollectorReceivesEveryMessage() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("fanout")
        val channel = pubsub.string(name)
        val collectorCount = 20
        val messageCount = 10

        val (inboxes, jobs) = startCollectors(channel, collectorCount)

        repeat(messageCount) { channel.emit("msg-$it") }

        withTimeout(10_000) {
            inboxes.forEachIndexed { index, inbox ->
                val received = (1..messageCount).map { inbox.receive() }
                assertEquals(
                    (0 until messageCount).map { "msg-$it" }, received,
                    "Collector $index should receive every message in publish order"
                )
            }
        }

        jobs.forEach { it.cancel() }
        awaitSubscribers(name, 0L)
    }

    /**
     * A shared connection receives messages for *all* subscribed channels, so each collector must
     * filter to its own. A leak here would cross-deliver between unrelated channels.
     */
    @Test
    fun channelsDoNotLeakIntoEachOther() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val nameA = channelName("isolation-a")
        val nameB = channelName("isolation-b")
        val channelA = pubsub.string(nameA)
        val channelB = pubsub.string(nameB)

        val inboxA = Channel<String>(Channel.UNLIMITED)
        val jobA = launch { channelA.collect { inboxA.send(it) } }
        val jobB = launch { channelB.collect { } }
        awaitSubscribers(nameA, 1L)
        awaitSubscribers(nameB, 1L)

        channelB.emit("for-b-only")
        // Give a wrong-channel message every chance to show up before declaring it absent.
        assertNull(
            withTimeoutOrNull(500) { inboxA.receive() },
            "Collector on $nameA must not receive messages published to $nameB"
        )

        channelA.emit("for-a")
        assertEquals("for-a", withTimeout(2_000) { inboxA.receive() })

        jobA.cancel(); jobB.cancel()
        awaitSubscribers(nameA, 0L)
        awaitSubscribers(nameB, 0L)
    }

    /** Typed channels must survive the shared-connection path unchanged. */
    @Test
    fun typedChannelsRoundTripOverSharedConnection() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("typed-shared")
        val channel = pubsub.get(name, Int.serializer())

        val inbox = Channel<Int>(Channel.UNLIMITED)
        val job = launch { channel.collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)

        repeat(5) { channel.emit(it * 100) }
        val received = withTimeout(5_000) { (1..5).map { inbox.receive() } }
        assertEquals(listOf(0, 100, 200, 300, 400), received)

        job.cancel()
        awaitSubscribers(name, 0L)
    }

    // ---------------------------------------------------------------- refcount lifecycle

    /** UNSUBSCRIBE must wait for the *last* collector, not the first one to leave. */
    @Test
    fun unsubscribesOnlyAfterLastCollectorLeaves() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("refcount")
        val channel = pubsub.string(name)

        val (inboxes, jobs) = startCollectors(channel, 4)
        val inbox = inboxes.first()
        val survivor = jobs.first()
        val leaving = jobs.drop(1)

        leaving.forEach { it.cancel() }
        leaving.forEach { it.join() }
        delay(300) // Let any erroneous UNSUBSCRIBE reach Redis before asserting it did not happen.

        assertEquals(
            1L, subscriberCount(name),
            "Channel must stay subscribed while one collector remains"
        )
        assertTrue(name in activeChannels(), "$name should still be in PUBSUB CHANNELS")

        // The surviving collector must still actually work, not just show up in Redis' bookkeeping.
        channel.emit("still-alive")
        assertEquals("still-alive", withTimeout(2_000) { inbox.receive() })

        survivor.cancel()
        awaitSubscribers(name, 0L)
        assertTrue(name !in activeChannels(), "$name should be unsubscribed once the last collector leaves")
    }

    /** The refcount must reset cleanly so a channel can be subscribed again after full teardown. */
    @Test
    fun resubscribesAfterAllCollectorsLeave() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("resubscribe")
        val channel = pubsub.string(name)

        repeat(3) { round ->
            val inbox = Channel<String>(Channel.UNLIMITED)
            val job = launch { channel.collect { inbox.send(it) } }
            awaitSubscribers(name, 1L)

            channel.emit("round-$round")
            assertEquals(
                "round-$round", withTimeout(2_000) { inbox.receive() },
                "Round $round should deliver after re-subscribing"
            )

            job.cancel()
            awaitSubscribers(name, 0L)
        }
    }

    /**
     * Hammers the refcount with overlapping subscribe/cancel churn across several channels. A racy
     * refcount would either strand a subscription (channel left subscribed with no collectors) or
     * unsubscribe out from under a live collector.
     */
    @Test
    fun concurrentSubscribeCancelChurnLeavesNoStrandedSubscriptions() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val baseline = connectedClients()
        val names = (1..4).map { channelName("churn-$it") }

        val churn = (1..120).map { i ->
            launch {
                val name = names[i % names.size]
                val job = launch { pubsub.string(name).collect { } }
                delay((i % 20).toLong())
                job.cancel()
                job.join()
            }
        }
        churn.forEach { it.join() }

        names.forEach { awaitSubscribers(it, 0L) }
        val active = activeChannels()
        names.forEach {
            assertTrue(it !in active, "Churn stranded a subscription on $it with no collectors left")
        }
        val delta = connectedClients() - baseline
        assertTrue(
            delta <= 2,
            "Churn should not leak connections; observed +$delta connections (baseline=$baseline)"
        )
    }

    /**
     * After the churn above, a channel must still be usable -- proving the refcount landed at a
     * genuine zero rather than drifting negative or positive.
     */
    @Test
    fun channelStillWorksAfterChurn() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("churn-then-use")
        val channel = pubsub.string(name)

        repeat(30) {
            val job = launch { channel.collect { } }
            job.cancel()
            job.join()
        }
        awaitSubscribers(name, 0L)

        val inbox = Channel<String>(Channel.UNLIMITED)
        val job = launch { channel.collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)
        channel.emit("works")
        assertEquals("works", withTimeout(2_000) { inbox.receive() })

        job.cancel()
        awaitSubscribers(name, 0L)
    }

    // ---------------------------------------------------------------- ordering and robustness

    /**
     * Races a continuous publisher against collector startup. A collector that attached its message
     * routing *after* issuing SUBSCRIBE would intermittently miss traffic arriving in that window.
     *
     * Deliberately does not wait for `PUBSUB NUMSUB` before publishing: doing so would close the
     * very window under test and make the assertion vacuous.
     */
    @Test
    fun noMessageLostWhileSubscriptionIsBeingEstablished() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        repeat(25) { round ->
            val name = channelName("race-$round")
            val channel = pubsub.string(name)

            // Publishes continuously, including throughout the collector's attach sequence.
            val publisher = launch {
                while (isActive) {
                    channel.emit("tick")
                    delay(2)
                }
            }
            val received = CompletableDeferred<String>()
            val job = launch {
                channel.collect { if (!received.isCompleted) received.complete(it) }
            }

            assertEquals(
                "tick",
                withTimeout(10_000) { received.await() },
                "Round $round: a collector must not miss a steady message stream while attaching"
            )
            publisher.cancel(); job.cancel()
            awaitSubscribers(name, 0L)
        }
    }

    /** Per-collector buffering means one stalled collector must not hold up the others. */
    @Test
    fun slowCollectorDoesNotStallOthers() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("slow-collector")
        val channel = pubsub.string(name)

        // Both collectors answer probes so the readiness barrier can see them; the slow one only
        // wedges once real traffic starts, which is the condition under test.
        val fastInbox = Channel<String>(Channel.UNLIMITED)
        val ready = Channel<Int>(Channel.UNLIMITED)
        val slowWedged = CompletableDeferred<Unit>()
        val slow = launch {
            channel.collect {
                if (it == probe) ready.trySend(0)
                else {
                    if (!slowWedged.isCompleted) slowWedged.complete(Unit)
                    delay(60_000) // Effectively wedged for the duration of the test.
                }
            }
        }
        val fast = launch {
            channel.collect { if (it == probe) ready.trySend(1) else fastInbox.send(it) }
        }
        val live = mutableSetOf<Int>()
        withTimeout(20_000) {
            while (live.size < 2) {
                channel.emit(probe)
                withTimeoutOrNull(100) { while (live.size < 2) live.add(ready.receive()) }
            }
        }

        repeat(20) { channel.emit("m$it") }
        withTimeout(2_000) { slowWedged.await() }

        val received = withTimeout(5_000) { (1..20).map { fastInbox.receive() } }
        assertEquals(
            (0 until 20).map { "m$it" }, received,
            "Fast collector should drain fully while the slow one is wedged"
        )

        slow.cancel(); fast.cancel()
        awaitSubscribers(name, 0L)
    }

    /**
     * Cross-channel coupling check: a collector wedged on one channel must not block delivery on a
     * *different* channel. Both channels share the single underlying `observeChannels()` stream, so
     * if that shared stream propagates a slow subscriber's backpressure, channel B starves when
     * channel A wedges. This is the narrower and untested half of the slow-collector behavior.
     */
    @Test
    fun slowCollectorOnOneChannelDoesNotBlockAnother() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val nameA = channelName("wedge-a")
        val nameB = channelName("fast-b")
        val channelA = pubsub.string(nameA)
        val channelB = pubsub.string(nameB)

        val fastInboxB = Channel<String>(Channel.UNLIMITED)
        val ready = Channel<Int>(Channel.UNLIMITED)
        val slowWedged = CompletableDeferred<Unit>()
        val slowA = launch {
            channelA.collect {
                if (it == probe) ready.trySend(0)
                else {
                    if (!slowWedged.isCompleted) slowWedged.complete(Unit)
                    delay(60_000) // Effectively wedged for the duration of the test.
                }
            }
        }
        val fastB = launch {
            channelB.collect { if (it == probe) ready.trySend(1) else fastInboxB.send(it) }
        }

        // Both channels must be live and not cross-deliver the probe.
        val live = mutableSetOf<Int>()
        withTimeout(20_000) {
            while (live.size < 2) {
                channelA.emit(probe)
                channelB.emit(probe)
                withTimeoutOrNull(100) { while (live.size < 2) live.add(ready.receive()) }
            }
        }

        // Wedge A with real traffic, then confirm B is unaffected.
        repeat(20) { channelA.emit("wedgeA$it") }
        withTimeout(2_000) { slowWedged.await() }

        repeat(20) { channelB.emit("b$it") }
        val receivedB = withTimeout(5_000) { (1..20).map { fastInboxB.receive() } }
        assertEquals(
            (0 until 20).map { "b$it" }, receivedB,
            "Channel B must keep receiving while channel A's collector is wedged"
        )

        slowA.cancel(); fastB.cancel()
        awaitSubscribers(nameA, 0L)
        awaitSubscribers(nameB, 0L)
        pubsub.disconnect()
    }

    /** Volume check: the shared connection must not drop messages under a burst. */
    @Test
    fun highVolumeDeliversEveryMessageInOrder() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("volume")
        val channel = pubsub.string(name)
        val count = 2_000

        val inbox = Channel<String>(Channel.UNLIMITED)
        val job = launch { channel.collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)

        repeat(count) { channel.emit(it.toString()) }
        val received = withTimeout(30_000) { (1..count).map { inbox.receive().toInt() } }
        assertEquals((0 until count).toList(), received, "All $count messages should arrive in order")

        job.cancel()
        awaitSubscribers(name, 0L)
    }

    /**
     * `publish` reports how many subscriber connections Redis delivered to. With sharing that is 1
     * for any number of local collectors -- the very number the old implementation inflated.
     */
    @Test
    fun publishReportsOneSubscriberConnectionForManyCollectors() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("numsub")
        val channel = pubsub.string(name)

        val (_, jobs) = startCollectors(channel, 10)

        assertEquals(
            1L, admin.sync().publish(name, "probe"),
            "Redis should report delivery to exactly one subscriber connection"
        )

        jobs.forEach { it.cancel() }
        awaitSubscribers(name, 0L)
    }

    /**
     * A channel's cached subscription must be evicted once its last collector leaves, so a long-lived
     * connection using a high cardinality of channel keys does not retain objects in [RedisPubSub.channels]
     * without bound. Asserted directly on the cache because a retained but unsubscribed entry is not
     * visible to Redis (`PUBSUB` reports only live subscriptions).
     */
    @Test
    fun channelCacheEntryIsEvictedWhenLastCollectorLeaves() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("evict")
        val channel = pubsub.string(name)

        assertEquals(0, pubsub.channels.size, "No entry before any collector attaches")

        val inbox = Channel<String>(Channel.UNLIMITED)
        val job = launch { channel.collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)
        assertEquals(1, pubsub.channels.size, "One entry while a collector is attached")
        channel.emit("x")
        assertEquals("x", withTimeout(2_000) { inbox.receive() })

        job.cancel()
        awaitUntil(
            what = { "cache entry evicted after last collector leaves (size=${pubsub.channels.size})" },
            check = { pubsub.channels.isEmpty() },
        )
        pubsub.disconnect()
    }

    /**
     * A long-lived shared connection has to survive the network dropping. Lettuce re-issues
     * SUBSCRIBE for every tracked channel on reconnect and listeners live on the connection object,
     * so a killed connection should heal itself without any collector noticing.
     *
     * This is strictly better than the old per-collector connections, where a drop silently ended
     * that collector's subscription for good.
     */
    @Test
    fun subscriptionSurvivesConnectionDrop() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("reconnect")
        val channel = pubsub.string(name)

        val (inboxes, jobs) = startCollectors(channel, 3)
        channel.emit("before-kill")
        inboxes.forEach { assertEquals("before-kill", withTimeout(5_000) { it.receive() }) }

        // Drops this service's pub/sub sockets; the admin connection is NORMAL type and survives.
        admin.sync().clientKill(KillArgs.Builder.typePubsub())

        // Lettuce reconnects and replays SUBSCRIBE on its own.
        awaitUntil(
            timeoutMs = 20_000,
            what = { "$name re-subscribed after the connection drop" },
            check = { subscriberCount(name) == 1L },
        )

        // Publishing may race the reconnect, so retry until the healed subscription delivers.
        val delivered = withTimeoutOrNull(20_000) {
            while (true) {
                channel.emit("after-kill")
                val got = withTimeoutOrNull(250) { inboxes.first().receive() }
                if (got == "after-kill") return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        assertTrue(delivered == true, "Collector should receive again once Lettuce re-subscribes")

        jobs.forEach { it.cancel() }
        awaitSubscribers(name, 0L)
        pubsub.disconnect()
    }

    /**
     * Regression test: a collector that outlives a [RedisPubSub.disconnect] must not, on its own
     * cleanup, send UNSUBSCRIBE to the *replacement* connection and kill a newer collector's live
     * subscription. Refcounts keyed only by channel name did exactly that.
     */
    @Test
    fun collectorOutlivingDisconnectDoesNotKillALaterSubscription() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("stale-unsubscribe")
        val channel = pubsub.string(name)

        val staleInbox = Channel<String>(Channel.UNLIMITED)
        val stale = launch { channel.collect { staleInbox.send(it) } }
        awaitSubscribers(name, 1L)

        // Drops the connection the first collector attached to and replaces the epoch.
        pubsub.disconnect()

        // A fresh collector on the same channel gets a new connection and a new subscription.
        val liveInbox = Channel<String>(Channel.UNLIMITED)
        val live = launch { channel.collect { liveInbox.send(it) } }
        awaitSubscribers(name, 1L)

        // Now retire the pre-disconnect collector. Its cleanup must be scoped to the dead epoch.
        stale.cancel()
        stale.join()
        delay(300) // Give a wrongly-routed UNSUBSCRIBE time to land before asserting it did not.

        assertEquals(
            1L, subscriberCount(name),
            "Cleanup of a pre-disconnect collector must not unsubscribe the current connection"
        )
        channel.emit("still-delivered")
        assertEquals(
            "still-delivered", withTimeout(5_000) { liveInbox.receive() },
            "The post-disconnect collector must still receive after the stale one is cancelled"
        )

        live.cancel()
        awaitSubscribers(name, 0L)
        pubsub.disconnect()
    }

    /**
     * [RedisPubSub.disconnect] releases the shared socket; live collectors then need their own
     * coroutine scope cancellation to end (disconnect does not reach into their flows). A disconnect
     * that happens under live collectors must not break reconnect or a later collector.
     */
    @Test
    fun disconnectReleasesSocketsUnderLiveCollectors() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val name = channelName("disconnect-live")
        val baseline = connectedClients()
        val channel = pubsub.string(name)

        // Some collectors active on the same service when disconnect() is called.
        val (_, preDisconnectJobs) = startCollectors(channel, 3)

        pubsub.disconnect()
        awaitUntil(
            what = { "shared socket released (baseline=$baseline, now=${connectedClients()})" },
            check = { connectedClients() <= baseline },
        )

        // The service is not permanently disabled: a fresh collector gets a new subscription.
        val inbox = Channel<String>(Channel.UNLIMITED)
        val job = launch { channel.collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)
        channel.emit("after-reconnect")
        assertEquals("after-reconnect", withTimeout(2_000) { inbox.receive() })

        job.cancel()
        preDisconnectJobs.forEach { it.cancel() }
        awaitSubscribers(name, 0L)
        pubsub.disconnect()
    }

    /**
     * Regression test: a failed SUBSCRIBE must not leave a phantom refcount behind. If it did, every
     * later collector on that channel would see a non-zero count, skip SUBSCRIBE, and silently
     * receive nothing forever.
     */
    @Test
    fun failedSubscribeDoesNotPoisonTheChannel() = runBlocking {
        // A client pointed at a closed port so the first attach cannot succeed.
        val deadClient = RedisClient.create("redis://127.0.0.1:${PORT + 50}")
        val name = channelName("poisoned")
        try {
            val broken = RedisPubSub("broken", TestSettingContext(), deadClient)
            val failure = runCatching {
                withTimeout(20_000) { broken.string(name).collect { } }
            }
            assertTrue(
                failure.isFailure,
                "Collecting against an unreachable Redis must fail loudly, not hang or return quietly"
            )
        } finally {
            deadClient.shutdown()
        }

        // The same channel name on a healthy service must be completely unaffected.
        val healthy = RedisPubSub("healthy", TestSettingContext(), client)
        val channel = healthy.string(name)
        val inbox = Channel<String>(Channel.UNLIMITED)
        val job = launch { channel.collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)
        channel.emit("not-poisoned")
        assertEquals("not-poisoned", withTimeout(5_000) { inbox.receive() })

        job.cancel()
        awaitSubscribers(name, 0L)
        healthy.disconnect()
    }

    // ---------------------------------------------------------------- lifecycle

    /** [RedisPubSub.disconnect] must release the shared sockets and still allow later reuse. */
    @Test
    fun disconnectReleasesConnectionsAndServiceStaysUsable() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val baseline = connectedClients()

        pubsub.connect()
        awaitUntil(
            what = { "shared connection open (baseline=$baseline, now=${connectedClients()})" },
            check = { connectedClients() >= baseline + 1 },
        )

        pubsub.disconnect()
        awaitUntil(
            what = { "shared connections closed (baseline=$baseline, now=${connectedClients()})" },
            check = { connectedClients() <= baseline },
        )

        // Rebuilds lazily: the service must not be permanently disabled by disconnect().
        val name = channelName("after-disconnect")
        val channel = pubsub.string(name)
        val inbox = Channel<String>(Channel.UNLIMITED)
        val job = launch { channel.collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)
        channel.emit("reconnected")
        assertEquals("reconnected", withTimeout(2_000) { inbox.receive() })

        job.cancel()
        awaitSubscribers(name, 0L)
        pubsub.disconnect()
    }

    /** Idempotency is required of [com.lightningkite.services.Service.disconnect]. */
    @Test
    fun disconnectIsIdempotent() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val baseline = connectedClients()
        pubsub.disconnect() // Never connected.
        assertEquals(baseline, connectedClients(), "Disconnect before connect should open nothing")

        pubsub.connect()
        awaitUntil(
            what = { "shared connection open (baseline=$baseline, now=${connectedClients()})" },
            check = { connectedClients() >= baseline + 1 },
        )
        pubsub.disconnect()
        awaitUntil(
            what = { "connections released (baseline=$baseline, now=${connectedClients()})" },
            check = { connectedClients() <= baseline },
        )
        pubsub.disconnect() // Second call must not reopen or throw.
        assertEquals(baseline, connectedClients(), "Repeated disconnect should not reopen connections")

        assertEquals(
            com.lightningkite.services.data.HealthStatus.Level.OK,
            pubsub.healthCheck().level,
            "Health check should rebuild the connection after disconnect"
        )
        pubsub.disconnect()
    }

    /**
     * Connection sharing is per service instance: one instance's [RedisPubSub.disconnect] must not
     * disturb another instance's live collectors, and messages still cross between them via Redis.
     */
    @Test
    fun instancesAreIndependent() = runBlocking {
        val name = channelName("cross-instance")
        val publisher = RedisPubSub("publisher", TestSettingContext(), client)
        val subscriber = RedisPubSub("subscriber", TestSettingContext(), client)

        val inbox = Channel<String>(Channel.UNLIMITED)
        val job = launch { subscriber.string(name).collect { inbox.send(it) } }
        awaitSubscribers(name, 1L)

        publisher.string(name).emit("across")
        assertEquals("across", withTimeout(2_000) { inbox.receive() })

        // Tearing down the publisher must leave the subscriber's connection and subscription intact.
        publisher.disconnect()
        assertEquals(
            1L, subscriberCount(name),
            "Disconnecting one instance must not drop another instance's subscription"
        )
        publisher.string(name).emit("after-publisher-disconnect")
        assertEquals(
            "after-publisher-disconnect", withTimeout(5_000) { inbox.receive() },
            "Subscriber should keep working after an unrelated instance disconnected"
        )

        job.cancel()
        awaitSubscribers(name, 0L)
        subscriber.disconnect()
    }
}
