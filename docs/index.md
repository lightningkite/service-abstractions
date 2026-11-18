# Service Abstractions documentation

Kotlin interfaces for the infrastructure an app depends on (databases, caches, files, email, SMS, AI, payments, ...), each with swappable implementations chosen by a settings URL.  Tests and local runs use in-memory fakes; production uses the real provider; the code doesn't change.

## Learning path

1. **[Basis](basis.md)**: settings URLs, `SettingContext`, registering schemes, service lifecycle.  Everything else builds on it.
2. **[Database](database.md)**, then **[conditions, modifications and optics](database-queries.md)**: models, tables, type-safe queries, KSP setup.
3. The services you need, from the table below.  Every chapter has the same sections: Modules, Quick start, Common tasks, Settings URLs, Testing, Gotchas.
4. **[Settings URL reference](settings-url-reference.md)** when you configure a deployment: every scheme with its parameters and quirks.
5. **[Telemetry](telemetry.md)** and **[Terraform](terraform.md)** when you go to production.

Runnable examples for most areas, all on fakes: [demo/](../demo/README.md) (`./gradlew :demo:runCombinedDemo`).  Upgrading from 1.3: [BREAKING_CHANGES_1.3.md](../BREAKING_CHANGES_1.3.md) and [CHANGELOG.md](../CHANGELOG.md).

## Chapters

| Area | Chapter |
|---|---|
| Settings, context, lifecycle | [basis.md](basis.md) |
| Value types (email, phone, units, files, money) | [data.md](data.md) |
| Database | [database.md](database.md), [database-queries.md](database-queries.md) |
| Cache | [cache.md](cache.md) |
| PubSub | [pubsub.md](pubsub.md) |
| Files and upload scanning | [files.md](files.md) |
| Email, inbound email | [email.md](email.md) |
| SMS, inbound SMS | [sms.md](sms.md) |
| Push notifications | [notifications.md](notifications.md) |
| Human-in-the-loop email/SMS for development | [human-services.md](human-services.md) |
| LLMs | [ai.md](ai.md) |
| Embeddings | [embedding.md](embedding.md) |
| Text-to-speech, speech-to-text | [speech.md](speech.md) |
| Phone calls | [phonecall.md](phonecall.md) |
| Real-time voice agents | [voiceagent.md](voiceagent.md) |
| Subscription payments | [subscription-payments.md](subscription-payments.md) |
| Geocoding | [geocoding.md](geocoding.md) |
| Telemetry | [telemetry.md](telemetry.md) |
| Terraform generation | [terraform.md](terraform.md) |
| Every settings URL | [settings-url-reference.md](settings-url-reference.md) |

## Installing

Every module is published as `com.lightningkite.services:<module>`.  Modules are versioned independently; the version catalog `com.lightningkite.stack:service-abstractions-catalog` pins a set that works together, so prefer it over hand-picked versions:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    }
    versionCatalogs {
        create("sa") { from("com.lightningkite.stack:service-abstractions-catalog:<catalog version>") }
    }
}

// build.gradle.kts
dependencies {
    implementation(sa.database.mongodb)       // com.lightningkite.services:database-mongodb
    implementation(sa.cache.asProvider())     // "cache" is also a prefix of "cache-redis", so asProvider()
    ksp(sa.database.processor)
}
```

Without the catalog: `implementation("com.lightningkite.services:database-mongodb:<version>")`, with each module's version from its `gradle.properties`.

## Modules

**MP** = Kotlin Multiplatform (JVM, Android, JS, iOS, macOS; some modules cover fewer targets), **JVM** = JVM only.  `-test` modules hold shared contract suites for implementation authors.

| Module | | Purpose | Chapter |
|---|---|---|---|
| `basis` | MP | `Setting`, `Service`, `SettingContext`, telemetry API, Terraform primitives | [basis](basis.md) |
| `http-client` | MP | Shared Ktor client for HTTP-based providers | [basis](basis.md) |
| `aws-client` | JVM | Shared AWS SDK HTTP clients and concurrency limit | [basis](basis.md) |
| `webhook-subservice` | MP | `WebhookAdapter` / `HttpAdapter` / `WebSocketAdapter` for inbound webhooks | [basis](basis.md) |
| `test` | MP | Terraform test harness | [terraform](terraform.md) |
| `data-shared` | MP | Validated strings, units, `GeoCoordinate`, `MediaType`, model annotations | [data](data.md) |
| `data` | MP | `Data` / `TypedData` content | [data](data.md) |
| `kfile` | MP | Multiplatform file API | [data](data.md) |
| `currency` | MP | `Cents` | [data](data.md) |
| `kotlin-bytes-format` | MP | Compact binary serialization format | [data](data.md) |
| `database` | MP | `Database`, `Table`, in-memory backend, permissions, vector indexes | [database](database.md) |
| `database-shared` | MP | Conditions, modifications, optics, queries (client-safe) | [database-queries](database-queries.md) |
| `database-processor` | JVM | KSP processor for `@Queryable` | [database-queries](database-queries.md#build-setup) |
| `database-mongodb` | JVM | MongoDB / Atlas / DocumentDB | [database](database.md) |
| `database-postgres` | JVM | PostgreSQL, pgvector | [database](database.md) |
| `database-sql` | JVM | H2, SQLite, MySQL, MariaDB | [database](database.md) |
| `database-sqlite` | MP | On-device SQLite (`native://`) | [database](database.md) |
| `database-jsonfile` | JVM | JSON files, for local development | [database](database.md) |
| `database-mapformat` | MP | Internal row/child-table mapping for SQL drivers | [database](database.md) |
| `database-test` | MP | Database conformance suites | [database](database.md#testing) |
| `cache` | MP | `Cache`, in-memory backend | [cache](cache.md) |
| `cache-redis` | JVM | Redis | [cache](cache.md) |
| `cache-memcached` | JVM | Memcached, ElastiCache | [cache](cache.md) |
| `cache-dynamodb` | JVM | DynamoDB | [cache](cache.md) |
| `cache-test` | MP | Cache contract suite | [cache](cache.md#testing) |
| `pubsub` | MP | `PubSub`, in-process backend | [pubsub](pubsub.md) |
| `pubsub-redis` | JVM | Redis pub/sub | [pubsub](pubsub.md) |
| `pubsub-aws` | JVM | DynamoDB polling pub/sub | [pubsub](pubsub.md) |
| `pubsub-test` | MP | PubSub contract suite | [pubsub](pubsub.md#testing) |
| `files` | MP | `ExternalFileSystem`, local files, `FileScanner` | [files](files.md) |
| `files-client` | MP | `ServerFile` reference type | [files](files.md) |
| `files-s3` | JVM | AWS S3 | [files](files.md) |
| `files-azure-blob` | JVM | Azure Blob Storage | [files](files.md) |
| `files-tika` | JVM | Content-type checking with Apache Tika | [files](files.md#scan-uploads) |
| `files-clamav` | JVM | Virus scanning with ClamAV | [files](files.md#scan-uploads) |
| `files-test` | MP | File system contract suite | [files](files.md#testing) |
| `email` | MP | `EmailService` | [email](email.md) |
| `email-javasmtp` | JVM | SMTP | [email](email.md), [README](../email-javasmtp/README.md) |
| `email-mailgun` | JVM | Mailgun sending | [email](email.md), [README](../email-mailgun/README.md) |
| `email-test` | MP | Placeholder | |
| `email-inbound` | MP | `EmailInboundService` | [email](email.md#receive-email) |
| `email-inbound-imap` | JVM | IMAP polling | [README](../email-inbound-imap/README.md) |
| `email-inbound-mailgun` | JVM | Mailgun routes | [README](../email-inbound-mailgun/README.md) |
| `email-inbound-sendgrid` | JVM | SendGrid Inbound Parse | [README](../email-inbound-sendgrid/README.md) |
| `email-inbound-ses` | JVM | AWS SES via SNS | [README](../email-inbound-ses/README.md) |
| `sms` | MP | `SMS` | [sms](sms.md) |
| `sms-twilio` | JVM | Twilio sending | [sms](sms.md) |
| `sms-aws` | JVM | AWS End User Messaging SMS | [sms](sms.md) |
| `sms-inbound` | MP | `SmsInboundService` | [sms](sms.md#receive-texts) |
| `sms-inbound-twilio` | JVM | Twilio receiving | [sms](sms.md#receive-texts) |
| `sms-test` | MP | Placeholder | |
| `notifications` | MP | `NotificationService` | [notifications](notifications.md) |
| `notifications-fcm` | JVM | Firebase Cloud Messaging | [notifications](notifications.md) |
| `notifications-test` | MP | Placeholder | |
| `human-services` | JVM | `human://` email and SMS dashboard | [human-services](human-services.md) |
| `ai` | MP | `LlmAccess`, prompts, tools | [ai](ai.md) |
| `ai-anthropic` | MP | Anthropic | [ai](ai.md) |
| `ai-openai` | MP | OpenAI and compatible servers | [ai](ai.md) |
| `ai-bedrock` | MP | AWS Bedrock, static credentials | [ai](ai.md) |
| `ai-bedrock-aws-sdk` | JVM | AWS Bedrock, AWS SDK credentials | [ai](ai.md) |
| `ai-ollama` | JVM | Ollama | [ai](ai.md) |
| `ai-embedded` | MP | On-device models | [README](../ai-embedded/README.md) |
| `ai-test` | JVM | LLM contract suites | [ai](ai.md#testing) |
| `embedding` | MP | `EmbeddingService` | [embedding](embedding.md) |
| `embedding-openai` | JVM | OpenAI embeddings | [embedding](embedding.md) |
| `embedding-bedrock` | MP | Bedrock embeddings, static credentials | [embedding](embedding.md) |
| `embedding-bedrock-aws-sdk` | JVM | Bedrock embeddings, AWS SDK credentials | [embedding](embedding.md) |
| `embedding-ollama` | JVM | Ollama embeddings | [embedding](embedding.md) |
| `embedding-test` | JVM | Embedding contract suites | [embedding](embedding.md#testing) |
| `speech` | MP | TTS and STT interfaces | [speech](speech.md) |
| `speech-openai` | JVM | OpenAI TTS / Whisper | [speech](speech.md) |
| `speech-elevenlabs` | JVM | ElevenLabs | [speech](speech.md) |
| `speech-local` | JVM | FreeTTS, Vosk (offline) | [speech](speech.md) |
| `speech-test` | MP | Speech contract suites | [speech](speech.md#testing) |
| `phonecall` | MP | `PhoneCallService` | [phonecall](phonecall.md) |
| `phonecall-twilio` | JVM | Twilio Voice | [phonecall](phonecall.md) |
| `phonecall-test` | MP | Placeholder | |
| `voiceagent` | MP | `VoiceAgentService` | [voiceagent](voiceagent.md) |
| `voiceagent-openai` | JVM | OpenAI Realtime | [voiceagent](voiceagent.md) |
| `voiceagent-phonecall` | JVM | Phone / browser audio bridge | [voiceagent](voiceagent.md) |
| `voiceagent-test` | MP | Voice agent contract suites | [voiceagent](voiceagent.md#testing) |
| `subscription-payments` | MP | `SubscriptionService` | [subscription-payments](subscription-payments.md) |
| `subscription-payments-stripe` | JVM | Stripe | [subscription-payments](subscription-payments.md) |
| `subscription-payments-test` | MP | Subscription contract suite | [subscription-payments](subscription-payments.md#testing) |
| `geocoding` | MP | `Geocoding`, caching, rate limiting | [geocoding](geocoding.md) |
| `geocoding-local` | JVM | Offline US data | [geocoding](geocoding.md) |
| `geocoding-stadia` | JVM | Stadia Maps | [geocoding](geocoding.md) |
| `geocoding-google` | JVM | Google | [geocoding](geocoding.md) |
| `geocoding-aws` | JVM | Amazon Location Service | [geocoding](geocoding.md) |
| `geocoding-azure` | JVM | Azure Maps | [geocoding](geocoding.md) |
| `geocoding-test` | MP | Geocoding contract suite | [geocoding](geocoding.md#testing) |
| `telemetry-shared` | MP | Client-safe telemetry types | [telemetry](telemetry.md) |
| `otel-jvm` | JVM | OpenTelemetry backend | [telemetry](telemetry.md) |
| `telemetry-sentry` | JVM | Sentry error reporting | [telemetry](telemetry.md) |

Not built or published: `database-cassandra` (experimental; commented out of `settings.gradle.kts`), and `database-compiler-plugin` / `database-gradle-plugin` (sources only, not in the build).  The `database-migration`, `pubsub-mqtt*`, `ai-koog` and `cache-dynamodb-kmp` directories hold only build output.
