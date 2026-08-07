# Service Abstractions

Originally from our [Lightning Server](https://github.com/lightningkite/lightning-server) project, we've decided to
separate the external service abstractions we've built such that others can use them.

You can use the abstractions here to make testing and running servers locally easier, as well as reduce your dependence
on any one specific technology or deployment style.

All the services and their implementations have the following:

- Metrics - the system automatically tracks metrics on the service's performance and sends them to a location of your
  choice.
- Health Checks - the services have built-in ways to check if they are currently connected and are operating safely.

We've done our best to minimize the dependencies in the `basis` package.

## Current Status

Version 1.3 has breaking changes that improve the ergonomics and safety, and is in the process of release.

## Goals

- **Abstract services where possible:** make it possible to switch databases and caches
- **Make local running easy:** for example, you should be able to use `mongodb-file://file-path` to automatically
  download and run MongoDB on your machine and connect to it.
- **Make deployments safe:** generate Terraform for the resource types you need and ensure you can move dependencies
  safely.

## Our dependencies

- [kotlin-logging](https://github.com/oshai/kotlin-logging)
- [KotlinX Serialization](https://github.com/Kotlin/kotlinx.serialization)
- [KotlinX Coroutines](https://github.com/Kotlin/kotlinx.coroutines)

## Settings Sample (server library agnostic)

```kotlin
fun main() {
    val settingsFile = """
        {
            "port": 8941,
            "host": "127.0.0.1",
            "cache": "ram"
        }
    """.trimIndent()
    val context = TestSettingContext()
    val settings = Json.decodeFromString<MyServerSettings>(settingsFile)

    runBlocking {
        val cache = settings.cache("cache", context)
        repeat(5) {
            val currentValue = cache.get<Int>("counter")
            cache.set("counter", (currentValue ?: 0) + 1)
        }
    }
}

@Serializable
data class MyServerSettings(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val cache: Cache.Settings = Cache.Settings(),
)
```

`TestSettingContext()` is a zero-dependency `SettingContext` meant for exactly this kind of
local running/testing. See **[demo/](demo/README.md)** for a runnable version of this sample
plus one for every other subsystem (database, email, sms, files, pubsub, phonecall, speech,
subscription-payments, voiceagent, and more), each using a local/fake implementation so it
needs no credentials.

## Database Sample (server library agnostic)

```kotlin
@Serializable
data class Post(
    override val _id: UUID = UUID.random(),
    val title: String,
    val content: String,
    val author: String,
    val postedAt: Instant,
): HasId<UUID>

fun main(database: Database) {
    val table = database.collection<Post>()

    runBlocking {
        val newPost = table.insertOne(Post(
          title = "My test post", 
          content = "Here's a long story about my cat.", 
          author = "someone@email.com", 
          postedAt = Clock.System.now()
        ))!!
        table.updateOneById(newPost._id, modification {
            title assign "Cat Posting"
        })
        table.find(condition { it.author.eq("someone@gmail.com") }, sort { it.postedAt.ascending() })
          .toList()
          .forEach { println(it) }
    }
}

@Serializable
data class MyServerSettings(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val cache: Cache.Settings = Cache.Settings(),
)
```

## Documentation

Comprehensive user guides are available for all major modules:

### Core Services

- **[Database](docs/database-module.md)** - Type-safe database abstraction with MongoDB, PostgreSQL, and in-memory
  implementations
    - [Query DSL Reference](docs/database-query-dsl.md) - Complete guide to Condition and Modification syntax
- **[Cache](docs/cache-module.md)** - Unified caching interface for Redis, Memcached, DynamoDB, and in-memory
- **[Files](docs/files.md)** - File storage abstraction for local filesystem and AWS S3

### Communication Services

- **[Email](docs/email-module.md)** - Send emails via SMTP (Gmail, SendGrid, Office 365, AWS SES)
- **[SMS](docs/sms-module.md)** - Send text messages via Twilio and other providers
- **[Push Notifications](docs/notifications-module.md)** - Multi-platform push notifications via Firebase Cloud
  Messaging
- **[PubSub](docs/pubsub-module.md)** - Publish-subscribe messaging for real-time event broadcasting

### Additional Resources

- [Database Pipeline Queries](plans/database-pipeline-queries.md) - Pipeline query design notes
- [DB Map Format Architecture](plans/db-map-format-architecture.md) - Database map format design

## Status

Incomplete and in progress. You can use Lightning Server if you want access immediately.

### Roadmap

- [ ] Add a convenient loader for Ktor and potentially other server libraries.
    - Are there even any other coroutine-based server libraries? What else is desired?