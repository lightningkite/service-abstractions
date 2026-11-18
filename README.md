# Service Abstractions

Kotlin Multiplatform interfaces for the infrastructure an app depends on, with swappable implementations: databases, caches, pub/sub, file storage, email, SMS, push notifications, LLMs, embeddings, speech, phone calls, payments and geocoding.  Each service is configured by a URL, so tests run on in-memory fakes and production on the real provider without code changes.  Every service reports traces, metrics and health checks, and many can generate their own Terraform.

Extracted from [Lightning Server](https://github.com/lightningkite/lightning-server), but independent of any server framework.

```kotlin
@Serializable
data class AppSettings(
    val database: Database.Settings = Database.Settings("ram"),
    val cache: Cache.Settings = Cache.Settings("ram"),
    val email: EmailService.Settings = EmailService.Settings("console"),
)

@Queryable
data class Post(
    override val _id: Uuid = Uuid.random(),
    val author: EmailAddress,
    val title: String,
    val likes: Int = 0,
) : HasId<Uuid>

suspend fun main() {
    MongoDatabase; RedisCache  // register the schemes production uses
    val settings = Json.decodeFromString<AppSettings>(File("settings.json").readText())
    val context = TestSettingContext()

    val posts = settings.database("database", context).prepare(DatabaseTableDefinition<Post>())
    val post = posts.insertOne(Post(author = "ada@example.com".toEmailAddress(), title = "Hello"))
    posts.updateOneById(post._id, Modification { it.likes += 1 })
    val popular = posts.find(Post.likes gte 1).toList()

    settings.cache("cache", context).set("last-post", post._id)
}
```

```json
{ "database": "mongodb://localhost/app", "cache": "redis://localhost:6379", "email": "smtp://user:pass@smtp.example.com:587?fromEmail=noreply@example.com" }
```

## Documentation

Start at **[docs/index.md](docs/index.md)**: learning path, installation, and every module with its Gradle coordinates.

- [Basis](docs/basis.md): settings, context, lifecycle
- [Database](docs/database.md) and [queries](docs/database-queries.md)
- [Settings URL reference](docs/settings-url-reference.md)
- [Runnable demos](demo/README.md) on fakes, no credentials needed

## Installing

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    versionCatalogs {
        create("sa") { from("com.lightningkite.stack:service-abstractions-catalog:<catalog version>") }
    }
}
// build.gradle.kts
dependencies {
    implementation(sa.database.mongodb)
    ksp(sa.database.processor)
}
```

Modules are `com.lightningkite.services:<module>`.  See [docs/index.md#installing](docs/index.md#installing) for repositories and versions.

## Changes

[CHANGELOG.md](CHANGELOG.md), and [BREAKING_CHANGES_1.3.md](BREAKING_CHANGES_1.3.md) for upgrading from 1.3.

## License

[LICENSE.txt](LICENSE.txt)
