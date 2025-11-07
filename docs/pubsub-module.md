# PubSub Module - User Guide

**Module:** `pubsub`
**Package:** `com.lightningkite.services.pubsub`
**Purpose:** Publish-subscribe messaging for real-time event broadcasting within and across application instances

---

## Overview

The PubSub module provides publish-subscribe messaging for decoupled communication. Multiple subscribers can listen to messages on a channel, and all receive every published message (fan-out pattern).

### Key Features

- **Fan-out messaging** - All subscribers receive every message
- **Multiple backends** - Local (in-process), Redis (distributed), Debug (console)
- **Type-safe channels** - Full serialization support
- **Fire-and-forget** - Publishers don't wait for subscribers
- **No persistence** - Messages are not stored (at-most-once delivery)

---

## Quick Start

### 1. Configure PubSub

```kotlin
@Serializable
data class ServerSettings(
    val pubsub: PubSub.Settings = PubSub.Settings("redis://localhost:6379")
)

val context = SettingContext(...)
val pubsub: PubSub = settings.pubsub("messaging", context)
```

**Supported URL schemes:**
- `local` - In-memory pub/sub within single JVM process (default)
- `debug` - Prints all messages to console
- `redis://host:port` - Redis Pub/Sub for cross-instance messaging (requires `pubsub-redis` module)

### 2. Get a Channel

```kotlin
// Typed channel
val orderChannel = pubsub.get<OrderEvent>("orders")

// String channel (no serialization)
val logChannel = pubsub.string("logs")
```

### 3. Subscribe to Messages

```kotlin
// Launch a coroutine to collect messages
scope.launch {
    orderChannel.collect { event ->
        println("Received order: ${event.orderId}")
        processOrder(event)
    }
}
```

**Important:** `collect` suspends forever. Use a coroutine scope tied to your application lifecycle.

### 4. Publish Messages

```kotlin
// Emit to all subscribers
orderChannel.emit(OrderEvent(
    orderId = "12345",
    status = "shipped",
    timestamp = Clock.System.now()
))
```

---

## Understanding PubSub Semantics

### At-Most-Once Delivery

Messages are **not queued**. If no subscribers are listening when you publish, the message is lost.

```kotlin
val channel = pubsub.get<String>("notifications")

// No subscribers yet - this message is LOST
channel.emit("Hello")

// Now subscribe
scope.launch {
    channel.collect { msg ->
        println(msg)  // Will NOT receive "Hello" from above
    }
}

// This message IS received by the subscriber
channel.emit("World")  // Prints: World
```

### Fan-Out to All Subscribers

Every subscriber receives every message:

```kotlin
val channel = pubsub.get<String>("events")

// Subscriber 1
scope.launch {
    channel.collect { msg ->
        logger.info("Logger received: $msg")
    }
}

// Subscriber 2
scope.launch {
    channel.collect { msg ->
        database.saveEvent(msg)
    }
}

// Subscriber 3
scope.launch {
    channel.collect { msg ->
        websocket.broadcast(msg)
    }
}

// All 3 subscribers receive this
channel.emit("User logged in")
```

### Fire-and-Forget

Publishing doesn't wait for subscribers or confirm delivery:

```kotlin
// Returns immediately, doesn't block
orderChannel.emit(order)

// No way to know if anyone received it
```

---

## Use Cases

### 1. Real-Time Notifications

Broadcast updates to all connected clients:

```kotlin
@Serializable
data class UserNotification(
    val userId: String,
    val message: String,
    val type: String
)

val notificationChannel = pubsub.get<UserNotification>("user-notifications")

// Publisher (backend service)
fun notifyUser(userId: String, message: String) {
    notificationChannel.emit(UserNotification(
        userId = userId,
        message = message,
        type = "info"
    ))
}

// Subscriber (WebSocket handler)
scope.launch {
    notificationChannel.collect { notification ->
        val connections = websocketManager.getConnectionsForUser(notification.userId)
        connections.forEach { it.send(notification) }
    }
}
```

### 2. Cache Invalidation

Notify all instances to invalidate cache:

```kotlin
@Serializable
data class CacheInvalidation(val key: String, val timestamp: Instant)

val cacheChannel = pubsub.get<CacheInvalidation>("cache-invalidation")

// When data changes
fun updateUser(user: User) {
    database.updateUser(user)

    // Tell all instances to invalidate cache
    cacheChannel.emit(CacheInvalidation(
        key = "user:${user.id}",
        timestamp = Clock.System.now()
    ))
}

// All instances listen and invalidate
scope.launch {
    cacheChannel.collect { invalidation ->
        localCache.remove(invalidation.key)
    }
}
```

### 3. Event Broadcasting

Notify multiple systems of domain events:

```kotlin
@Serializable
sealed class OrderEvent {
    @Serializable
    data class Created(val orderId: String, val userId: String) : OrderEvent()

    @Serializable
    data class Shipped(val orderId: String, val trackingNumber: String) : OrderEvent()

    @Serializable
    data class Cancelled(val orderId: String, val reason: String) : OrderEvent()
}

val orderEvents = pubsub.get<OrderEvent>("order-events")

// Publisher
fun shipOrder(orderId: String, trackingNumber: String) {
    database.updateOrderStatus(orderId, "shipped")
    orderEvents.emit(OrderEvent.Shipped(orderId, trackingNumber))
}

// Subscriber 1: Email notifications
scope.launch {
    orderEvents.collect { event ->
        when (event) {
            is OrderEvent.Shipped -> emailService.sendShippingNotification(event)
            is OrderEvent.Cancelled -> emailService.sendCancellationEmail(event)
            else -> {}
        }
    }
}

// Subscriber 2: Analytics
scope.launch {
    orderEvents.collect { event ->
        analytics.track(event)
    }
}

// Subscriber 3: Inventory management
scope.launch {
    orderEvents.collect { event ->
        when (event) {
            is OrderEvent.Created -> inventory.reserve(event.orderId)
            is OrderEvent.Cancelled -> inventory.release(event.orderId)
            else -> {}
        }
    }
}
```

### 4. Logging Aggregation

Collect logs from multiple sources:

```kotlin
val logChannel = pubsub.string("application-logs")

// Multiple services publish logs
fun logError(service: String, error: String) {
    logChannel.emit("[$service] ERROR: $error")
}

// Centralized log collector
scope.launch {
    logChannel.collect { logLine ->
        logAggregator.append(logLine)
    }
}
```

---

## Local vs Distributed PubSub

### Local PubSub (`local`)

**When to use:**
- Single server deployment
- Development/testing
- Within a single JVM process

**Limitations:**
- Only works within one process
- Lost on application restart
- Can't communicate across servers

**Example:**
```kotlin
val pubsub = PubSub.Settings("local")("pubsub", context)
```

### Redis PubSub (`redis://`)

**When to use:**
- Multiple server instances
- Microservices architecture
- Horizontal scaling

**Benefits:**
- Messages broadcast across all instances
- Works across different servers
- Redis handles routing

**Example:**
```kotlin
val pubsub = PubSub.Settings("redis://localhost:6379")("pubsub", context)
```

**Requirements:**
- Redis server running
- All instances connected to same Redis
- Reliable network between instances

---

## PubSub vs Other Patterns

### When to Use PubSub

✅ **Good for:**
- Real-time event notifications
- Cache invalidation across instances
- WebSocket message broadcasting
- Logging and monitoring events
- Temporary, ephemeral messages

❌ **Not good for:**
- Guaranteed delivery (use message queue)
- Durable storage (use database)
- Task distribution (use work queue)
- Request-response (use HTTP/RPC)
- Ordered messages (use Kafka/message queue)

### PubSub vs Database

| Feature | PubSub | Database |
|---------|--------|----------|
| Persistence | No | Yes |
| Delivery guarantee | At-most-once | Guaranteed |
| Query history | No | Yes |
| Speed | Very fast | Fast |
| Use case | Real-time events | Long-term storage |

### PubSub vs Message Queue

| Feature | PubSub | Message Queue |
|---------|--------|---------------|
| Subscribers | Multiple (fan-out) | Single (competing consumers) |
| Delivery | At-most-once | At-least-once |
| Persistence | No | Yes |
| Order | No guarantee | Often guaranteed |
| Use case | Broadcasting | Task processing |

### PubSub vs Cache

| Feature | PubSub | Cache |
|---------|--------|-------|
| Data storage | No | Yes |
| Read access | No (consume once) | Yes (multiple reads) |
| Expiration | Immediate | TTL-based |
| Use case | Event notification | Shared state |

---

## Channel Naming Strategies

### Flat Naming

```kotlin
pubsub.get<Event>("user-events")
pubsub.get<Event>("order-events")
pubsub.get<Event>("payment-events")
```

### Hierarchical Naming

```kotlin
pubsub.get<Event>("events/users")
pubsub.get<Event>("events/orders")
pubsub.get<Event>("events/payments")
```

### User-Specific Channels

```kotlin
fun getUserChannel(userId: String) = pubsub.get<Notification>("user/$userId/notifications")

// Publish to specific user
getUserChannel("user-123").emit(notification)

// Subscribe for specific user
scope.launch {
    getUserChannel("user-123").collect { notification ->
        sendToUser(notification)
    }
}
```

### Wildcard Patterns (Redis)

Redis supports pattern matching (if using Redis PubSub implementation):

```kotlin
// Subscribe to all user channels
// Note: Requires Redis-specific implementation
redisPubSub.psubscribe("user/*/notifications") { channel, message ->
    // Handle message from any user channel
}
```

---

## Error Handling

### Subscriber Errors

Errors in subscribers don't affect publishers or other subscribers:

```kotlin
scope.launch {
    channel.collect { event ->
        try {
            processEvent(event)
        } catch (e: Exception) {
            logger.error("Failed to process event", e)
            // Other subscribers still receive the event
        }
    }
}
```

### Publisher Errors

Publishers rarely fail (fire-and-forget), but connection issues can cause exceptions:

```kotlin
try {
    channel.emit(event)
} catch (e: Exception) {
    logger.error("Failed to publish event", e)
    // Message is lost
}
```

### Handling Lost Messages

Since PubSub doesn't guarantee delivery, critical operations should have fallbacks:

```kotlin
// Publish cache invalidation
try {
    cacheChannel.emit(CacheInvalidation("user:123"))
} catch (e: Exception) {
    logger.warn("Failed to broadcast cache invalidation")
}

// Always set TTL as backup
cache.set("user:123", user, ttl = 5.minutes)
```

---

## Performance Considerations

### Message Size

Keep messages small. Large messages impact performance:

```kotlin
// BAD - 1MB serialized message
data class HugeEvent(val data: ByteArray, val metadata: Map<String, String>)

// GOOD - Reference to data
data class CompactEvent(val dataId: String, val timestamp: Instant)
```

### High-Frequency Publishing

PubSub can handle high message rates, but subscribers must keep up:

```kotlin
// Publisher: 1000 msg/sec
repeat(1000) {
    channel.emit(event)
}

// Subscriber must process fast enough
scope.launch {
    channel.collect { event ->
        processQuickly(event)  // Must be fast or use buffering
    }
}
```

### Slow Subscribers

Slow subscribers can cause backpressure. Use buffering:

```kotlin
scope.launch {
    channel
        .buffer(1000)  // Buffer up to 1000 messages
        .collect { event ->
            slowDatabaseWrite(event)
        }
}
```

---

## Testing

### Debug Mode

Print all messages to console:

```kotlin
val pubsub = PubSub.Settings("debug")("pubsub", context)

val channel = pubsub.get<String>("test")
channel.emit("Hello")
// Console output: [PubSub] Channel 'test': Hello
```

### Local Mode for Unit Tests

```kotlin
@Test
fun testEventBroadcast() = runTest {
    val pubsub = PubSub.Settings("local")("test-pubsub", testContext)
    val channel = pubsub.get<String>("events")

    val received = mutableListOf<String>()

    // Subscribe
    val job = launch {
        channel.collect { msg -> received.add(msg) }
    }

    // Publish
    channel.emit("Event 1")
    channel.emit("Event 2")

    // Wait and verify
    delay(100)
    assertEquals(listOf("Event 1", "Event 2"), received)

    job.cancel()
}
```

---

## Common Patterns

### Request-Response (Anti-Pattern)

PubSub is **not designed** for request-response. Use HTTP or RPC instead.

```kotlin
// BAD - Don't do this
val requestChannel = pubsub.get<Request>("requests")
val responseChannel = pubsub.get<Response>("responses")

// This is fragile and complicated
requestChannel.emit(Request(id = "123", data = "..."))
val response = withTimeout(5000) {
    responseChannel.first { it.requestId == "123" }
}

// GOOD - Use proper request-response
val response = httpClient.post("/api/endpoint", request)
```

### Event Sourcing (Partial)

PubSub can publish events, but shouldn't be the event store:

```kotlin
// Save to database first (single source of truth)
database.insert(event)

// Then broadcast
eventChannel.emit(event)

// Subscribers update their views
scope.launch {
    eventChannel.collect { event ->
        updateReadModel(event)
    }
}
```

### Circuit Breaker

Protect slow subscribers:

```kotlin
var consecutiveFailures = 0

scope.launch {
    channel.collect { event ->
        if (consecutiveFailures > 10) {
            logger.warn("Circuit breaker open, skipping event")
            return@collect
        }

        try {
            processEvent(event)
            consecutiveFailures = 0
        } catch (e: Exception) {
            consecutiveFailures++
            logger.error("Processing failed", e)
        }
    }
}
```

---

## Troubleshooting

### Messages Not Received

**Check:**
1. Is subscriber running before publisher?
2. Are they using the same channel name?
3. Is serialization working correctly?
4. Are errors being silently caught?

**Debug:**
```kotlin
// Add logging
scope.launch {
    channel.collect { event ->
        println("Received: $event")
        processEvent(event)
    }
}
```

### Messages Lost

**Cause:** PubSub is at-most-once delivery

**Solutions:**
- Use message queue for guaranteed delivery
- Add persistence layer (save to database)
- Implement retry logic
- Accept that some messages may be lost

### High Memory Usage

**Cause:** Subscribers can't keep up with publishers

**Solutions:**
- Add buffering with limits
- Make subscribers faster
- Reduce publishing rate
- Use multiple subscriber instances

### Redis Connection Issues

**Symptoms:** Messages not crossing instances

**Check:**
1. All instances connected to same Redis?
2. Network connectivity between instances and Redis?
3. Redis server running?

---

## See Also

- [PubSub.kt](../pubsub/src/commonMain/kotlin/com/lightningkite/services/pubsub/PubSub.kt) - Interface documentation
- [RedisPubSub.kt](../pubsub-redis/src/main/kotlin/com/lightningkite/services/pubsub/redis/RedisPubSub.kt) - Redis implementation
- [database-module.md](./database-module.md) - Persistent storage alternative
- [cache-module.md](./cache-module.md) - Shared state alternative
