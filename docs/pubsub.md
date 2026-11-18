# PubSub

`PubSub` broadcasts messages to every current subscriber of a named channel, within one process or across servers.  A `PubSubChannel<T>` is both a `Flow<T>` (collect to subscribe) and a `FlowCollector<T>` (emit to publish).

Delivery is at most once: there's no queue, no persistence and no replay.  Subscribers only see messages sent while they're collecting.  For durable work, use a [database](database.md) table.

## Modules

| Module | Add it for |
|---|---|
| `pubsub` | `PubSub`, in-process `local` and `debug`.  Multiplatform. |
| `pubsub-redis` | `redis://` across servers.  JVM. |
| `pubsub-aws` | `dynamodb-pubsub://`: DynamoDB polling, for AWS Lambda.  JVM. |
| `pubsub-test` | `PubSubTest`, the shared contract suite. |

## Quick start

```kotlin
@Serializable
data class OrderShipped(val orderId: String)

@Serializable
data class AppSettings(val pubsub: PubSub.Settings = PubSub.Settings("local"))

suspend fun main() = coroutineScope {
    RedisPubSub  // registers redis://
    val settings = Json.decodeFromString<AppSettings>("""{"pubsub": "redis://localhost:6379"}""")
    val pubsub = settings.pubsub("pubsub", TestSettingContext())

    val orders = pubsub.get<OrderShipped>("orders")
    val listener = launch { orders.collect { println("shipped ${it.orderId}") } }
    delay(100)  // let the subscription start
    orders.emit(OrderShipped("A-1001"))
    listener.cancel()
}
```

`pubsub.string("raw-events")` gives a `PubSubChannel<String>` without JSON encoding.

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `local` | `pubsub` | `local` |
| `debug` | `pubsub` | `debug` (like `local`, printing every channel and emit) |
| `redis` | `pubsub-redis` | `redis://:password@cache.internal:6379` |
| `dynamodb-pubsub` | `pubsub-aws` | `dynamodb-pubsub://us-east-1/app-pubsub?pollInterval=50` |

Parameters and quirks: [Settings URL reference: PubSub](settings-url-reference.md#pubsub).  Reference `RedisPubSub` or `DynamoDbPubSub` before parsing settings.

`dynamodb-pubsub` writes each message to a table and subscribers poll it (default every 15 ms).  It suits Lambda, where holding a Redis connection per invocation is slow.  Messages expire after 5 minutes; order is preserved per channel.

## Testing

Use `local`.  To check another implementation against the shared contract, subclass `PubSubTest`:

```kotlin
class MyPubSubTests : PubSubTest() {
    override val pubsub: PubSub = MyPubSub("test", TestSettingContext())
}
```

## Gotchas

- **Subscribe before you emit.**  `collect` in a launched coroutine starts asynchronously; a message emitted before the subscription is active is lost.
- **Slow subscribers drop messages.**  `local` buffers 64 messages per channel and then drops the oldest; `emit` never waits for subscribers.
- **`local` is one process.**  Two server instances need `redis` or `dynamodb-pubsub` to hear each other.
- **TLS Redis**: only `redis` is registered; `rediss://` fails with "No handler".
