# Settings URL Reference

Every URL scheme each service accepts, taken from the registration code, not from KDoc.  Paths are relative to the repo root.  `file:line` citations point at the `register(...)` call.

## Cheatsheet

**Preload** is the class that registers the scheme.  Reference it once at startup, before any settings are parsed, or the scheme gives "No handler":
- `X.Companion` means touch the companion object, e.g. `MongoDatabase.Companion`.  This initializes the class and runs its `init {}`.
- `X.ensureRegistered()` / `X.register()` means call that no-op.
- **—** means the scheme is built into the `Settings` type's own module, so nothing needs preloading.

`[...]` marks optional parts.  For the full parameter lists, see each service's section.

| Service | Sample setting | Preload |
|---|---|---|
| **Database** | `ram` | — |
| | `ram-preload://<path>` | — |
| | `delay://<100ms-500ms>/<wrapped db url>` | — |
| | `json-files://<folder>` | `JsonFileDatabase.Companion` |
| | `mongodb://<user>:<pass>@<host>:<port>/<db>` | `MongoDatabase.Companion` |
| | `mongodb+srv://<user>:<pass>@<host>/<db>` | `MongoDatabase.Companion` |
| | `mongodb-test[://?version=<V7_0>]` | `MongoDatabase.Companion` |
| | `mongodb-file://<folder>[?databaseName=<name>&port=<port>&version=<7.0>]` | `MongoDatabase.Companion` |
| | `postgresql://<user>:<pass>@<host>:<port>/<db>` | `PostgresDatabase.Companion` |
| | `sql-h2://<mem:name or file path>` | `SqlDatabase.Companion` |
| | `sql-sqlite://<path or :memory:>` | `SqlDatabase.Companion` |
| | `sql-mysql://<user>:<pass>@<host>:<port>/<db>` (also `sql-mariadb://`) | `SqlDatabase.Companion` |
| | `cassandra://<user>:<pass>@<host>:<port>/<keyspace>[?dc=<dc>&rf=<n>]` | `CassandraDatabase.Companion` |
| | `keyspaces://<region>/<keyspace>` | `CassandraDatabase.Companion` |
| | `native://<location>` | `SqliteDatabase.Companion` |
| **VectorDatabase** | `ram` | `Database.Settings` |
| | `mongodb+srv://<user>:<pass>@<host>/<db>` (also `mongodb://`) | `MongoDatabase.Companion` |
| | `postgresql://<user>:<pass>@<host>:<port>/<db>` | `PostgresDatabase.Companion` |
| **Cache** | `ram` / `ram-unsafe` | — |
| | `redis://[:<pass>@]<host>:<port>[/<db>]` | `RedisCache.Companion` |
| | `memcached://<host>:<port>[,<host>:<port>]` | `MemcachedCache.Companion` |
| | `memcached-aws://<config-endpoint>:<port>` | `MemcachedCache.Companion` |
| | `memcached-test` (spawns local memcached) | `MemcachedCache.Companion` |
| | `dynamodb://[<accessKey>:<secretKey>@]<region>/<table>` | `DynamoDbCache.Companion` |
| | `dynamodb-local` (downloads + runs DynamoDB Local) | `DynamoDbCache.Companion` |
| **PubSub** | `local` / `debug` | — |
| | `redis://[:<pass>@]<host>:<port>[/<db>]` | `RedisPubSub.Companion` |
| | `dynamodb-pubsub://[<accessKey>:<secretKey>@]<region>/<table>` | `DynamoDbPubSub.Companion` |
| **Files** | `file://<path>?serveUrl=<url>` | — |
| | `s3://[<profile>@]<bucket>.s3-<region>.amazonaws.com` | `S3ExternalFileSystem.Companion` |
| | `azure-blob://<account>:<accountKey>@<container>` | `AzureBlobExternalFileSystem.Companion` |
| **FileScanner** | `mime://` | — |
| | `clamav://<host>:<port>/[<platform>]` | `ClamAvFileScanner.Companion` |
| **Email** | `console` / `test` | — |
| | `smtp://<user>:<pass>@<host>:<port>?fromEmail=<addr>[&fromLabel=<name>]` | `JavaSmtpEmailService.Companion` |
| | `mailgun://<apiKey>@<domain>` | `MailgunEmailService.Companion` |
| | `human://<host>:<port>` | `HumanEmailService.Companion` |
| **Email inbound** | `console` / `test` | — |
| | `imaps://<user>:<pass>@<host>[:<port>][/<folder>]` (also `imap://`) | `ImapEmailInboundService.Companion` |
| | `mailgun://<apiKey>@<domain>` | `MailgunEmailInboundService.Companion` |
| | `sendgrid://<verificationKey>@<host>` | `SendGridEmailInboundService.Companion` |
| | `ses://?topicArn=<arn>[&bucket=<bucket>&region=<region>]` | `SesEmailInboundService.Companion` |
| | `human://<host>:<port>` | `HumanEmailInboundService.Companion` |
| **SMS** | `console` / `test` | — |
| | `twilio://<accountSid>:<authToken>@<fromPhone>` | `TwilioSMS.Companion` |
| | `awssms://[<accessKey>:<secretKey>@]<region>?originationIdentity=<id>` | `AwsSms.Companion` |
| | `human://<host>:<port>` | `HumanSmsService.Companion` |
| **SMS inbound** | `console` / `test` | — |
| | `twilio://<accountSid>:<authToken>@<phone>` | `TwilioSmsInboundService.Companion` |
| | `human://<host>:<port>` | `HumanSmsInboundService.Companion` |
| **Notifications** | `console` / `test` | — |
| | `fcm://<path-to-service-account.json>` | `FcmNotificationClient.Companion` |
| **Phone calls** | `console` / `test` | — |
| | `twilio://<accountSid>:<authToken>@<fromPhone>` or `twilio://<accountSid>-<keySid>:<keySecret>@<fromPhone>` | `TwilioPhoneCallService.Companion` |
| **LLM Access** | `anthropic://?apiKey=<key>` | `AnthropicLlmSettings.ensureRegistered()` |
| | `openai://?apiKey=<key>[&baseUrl=<url>]` | `OpenAiLlmSettings.ensureRegistered()` |
| | `ollama://[<model>]?baseUrl=<url>&autoStart=<bool>&autoPull=<bool>` | `OllamaSchemeRegistrar.ensureRegistered()` |
| | `bedrock://[<accessKeyId>:<secretKey>@ or <profile>@]?region=<region>` | `BedrockLlmSettings.ensureRegistered()` |
| | `bedrock-sdk://<model-id>?region=<region>[&profile=<profile>]` | `BedrockSdkLlm.ensureRegistered()` |
| | `embedded://<model-name>?path=<model path or HF id>` | `EmbeddedLlmSettings.ensureRegistered()` |
| **Embeddings** | `openai://<model-id>?apiKey=<key>[&dimensions=<n>]` | `OpenAiEmbeddingSettings.ensureRegistered()` |
| | `ollama://<model>?baseUrl=<url>` | `OllamaEmbeddingSchemeRegistrar.ensureRegistered()` |
| | `bedrock://[<profile>@]<model-id>?region=<region>` | `BedrockEmbeddingSettings.ensureRegistered()` |
| | `bedrock-sdk://<model-id>?region=<region>` | `BedrockSdkEmbedding.ensureRegistered()` |
| **Speech-to-text** | `test` / `console` | — |
| | `openai://<apiKey>@<model>` | `OpenAISpeechToTextService.Companion` |
| | `elevenlabs://<apiKey>@<model>` | `ElevenLabsSpeechToTextService.Companion` |
| | `vosk://[?modelName=<name>]` (also `local://`) | `VoskSpeechToTextService.Companion` |
| **Text-to-speech** | `test` / `console` | — |
| | `openai://<apiKey>@<model>` | `OpenAITextToSpeechService.Companion` |
| | `elevenlabs://<apiKey>@<model>` | `ElevenLabsTextToSpeechService.Companion` |
| | `freetts://` (also `local://`) | `FreeTtsTextToSpeechService.Companion` |
| **Voice agent** | `test` / `console` | — |
| | `openai-realtime://[<model>]?apiKey=<key>` | `OpenAIVoiceAgentService.Companion` |
| **Telemetry** | `noop` | — |
| | `logging` / `logging-nocolor` | `LoggingTelemetryBackend.register()` |
| | `log` / `console` | `OtelTelemetryBackend.register()` |
| | `otlp-grpc://<host>:<port>` (also `otlp-http://`, `otlp-https://`) | `OtelTelemetryBackend.register()` |
| | `dev[://<outputFile>]` / `debounced-dev[://<outputFile>]` | `OtelTelemetryBackend.register()` |
| | `sentry://<key>@<sentry-host>/<projectId>/<wrapped telemetry url>` | `SentryTelemetryBackend.register()` |
| **Geocoding** | `test` | — |
| | `local` | `LocalGeocoding.Companion` |
| | `stadia://<apiKey>[@<host>]` | `StadiaGeocoding.Companion` |
| | `google://<apiKey>` | `GoogleGeocoding.Companion` |
| | `aws-location://[<accessKey>:<secretKey>@]<region>` | `AwsLocationGeocoding.Companion` |
| | `azure-maps://<subscriptionKey>` or `azure-maps://<clientId>?auth=entra` | `AzureMapsGeocoding.Companion` |
| **Subscription payments** | `console` / `test` | — |
| | `stripe://<apiKey>[@<webhookSecret>]` | `StripeSubscriptionService.Companion` |

## How dispatch works

- Each service has a `Settings` type, usually `@JvmInline value class Settings(val url: String)`.  Telemetry is the exception: it's a data class with extra fields (see [Telemetry](#telemetry)).
- `UrlSettingParser.parse` uses `url.substringBefore("://")` as the key (`basis/src/commonMain/kotlin/com/lightningkite/services/UrlSettingParser.kt`).  A URL without `://` uses the whole string as the key, so bare `ram`, `test`, `console`, `noop` all work.
- An unknown scheme throws `IllegalArgumentException("No handler <key> ... available handlers are ...")`.  If a scheme you expect is missing from that list, its module isn't loaded.
- Handlers register in the implementation's companion `init {}`.  Being on the classpath isn't enough: the class must be initialized first.  Some modules expose a no-op you can call at startup for this: `ensureRegistered()` (ai-*, embedding-*) or `register()` (otel-jvm, telemetry-sentry, `LoggingTelemetryBackend`).  For other modules, reference the implementation class or its `Settings.Companion` builder.
- Registering the same scheme twice throws `Error`, so a library can't hijack a scheme.
- The same scheme can mean different things on different services, e.g. `redis://` for Cache and for PubSub.  Each section below covers one service.
- `${VAR}` env-var expansion works only where a section says so.  In most handlers, an unset `${VAR}` stays in the URL as literal text.

## Contents

- Data: [Database](#database), [VectorDatabase](#vectordatabase), [Cache](#cache), [PubSub](#pubsub)
- Files: [ExternalFileSystem](#files-externalfilesystemsettings), [FileScanner](#filescanner-filescannersettings)
- Messaging: [Email](#email-emailservicesettings), [Email inbound](#email-inbound-emailinboundservicesettings), [SMS](#sms-smssettings), [SMS inbound](#sms-inbound-smsinboundservicesettings), [Notifications](#notifications-notificationservicesettings), [Phone calls](#phone-calls-phonecallservicesettings)
- AI: [LLM Access](#llm-access), [Embeddings](#embeddings), [Speech-to-Text](#speech-to-text), [Text-to-Speech](#text-to-speech), [Voice Agent](#voice-agent)
- Other: [Telemetry](#telemetry), [Geocoding](#geocoding), [Subscription payments](#subscription-payments)
- [Known issues found while writing this](#known-issues-found-while-writing-this)

## Database

`Database.Settings(url: String = "ram")` — `database/src/commonMain/kotlin/com/lightningkite/services/database/Database.kt:114`. `invoke(name, context)` = `parse(name, url, context)`.

### `ram://`
Module: `database` (Database.kt:119)
```
ram          (or ram://anything - everything after the scheme is ignored)
```
No parts. Example: `ram`. Data lost on exit.

### `ram-preload://`
Module: `database` (Database.kt:122)
```
ram-preload://<path>
```
| part | required | default | meaning |
|---|---|---|---|
| path (everything after `://`) | no | blank -> empty DB | Directory: `<path>/<tableName>.json` per table (lazy). Regular file: one JSON object `{tableName: [rows]}`, parsed eagerly at construction. |

Surprises: a missing/unreadable path only logs a warning (`Could not extract metadata`) and yields an empty DB (silent fallback). Bare `ram-preload` (no `://`) -> empty DB.
Examples: `ram-preload://./testdata/`, `ram-preload://seed.json`

### `delay://`
Module: `database` (Database.kt:151)
```
delay://<delay>/<wrapped database url>
```
| part | required | default | meaning |
|---|---|---|---|
| delay | no (may be blank) | random 350ms..750ms | `<n>` integer = fixed n ms; `<d1>-<d2>` = range of Kotlin `Duration.parse` strings (e.g. `100ms-500ms`); single `<duration>` (e.g. `200ms`) = range `duration/2..duration`. |
| wrapped url | yes | - | Everything after the first `/` after the delay; parsed recursively with `Database.Settings.parse`. |

Surprises: wraps another DB via `.delayed(range)`. The delay part ends at the first `/`. Invalid duration -> `Duration.parse` throws `IllegalArgumentException`. An integer is checked first, so a bare number is always fixed ms.
Examples: `delay://100ms-500ms/ram`, `delay://250/json-files://./data`

### `sql-h2://`
Module: `database-sql` (SqlDatabase.kt:85; parsing in HikariPool.kt:84)
```
sql-h2://<h2 jdbc path>[?<pool and/or h2 params>]
```
Becomes `jdbc:h2:` + path + (`;` + remaining query if any).
| part | required | default | meaning |
|---|---|---|---|
| path | yes | - | Passed raw after `jdbc:h2:` (`mem:name`, `file:./x`, `~/x`). Starts with `mem:` -> pool bypassed (max 1 connection). |
| `maxPoolSize` | no | Hikari default; forced 1 for `mem:` | int |
| `minIdle` | no | Hikari default | int |
| `connectionTimeout`, `idleTimeout`, `maxLifetime`, `validationTimeout` | no | Hikari default | long, ms |
| `poolName` | no | Hikari default | string |
| other params | no | - | forwarded to the driver |

Surprises: no user/password support (no userinfo parsing). Bad numeric pool values -> `NumberFormatException`. Forwarded params are re-joined with `&` but appended after `;`; H2 expects `;` separators, so with 2+ driver params only the first is in the right form (likely bug; not tested). Pool is lazy (created on first use).
Examples: `sql-h2://mem:test`, `sql-h2://file:./data/app?maxPoolSize=5`

### `sql-sqlite://`
Module: `database-sql` (SqlDatabase.kt:99)
```
sql-sqlite://<path or :memory:>[?<pool and/or sqlite params>]
```
Becomes `jdbc:sqlite:` + path + (`?` + remaining query). Same pool param names as `sql-h2` (above), but always single-connection (`bypassPool = true`; `maxPoolSize` is ignored). No userinfo.
Examples: `sql-sqlite://:memory:`, `sql-sqlite://./data/app.db`, `sql-sqlite://app.db?busy_timeout=5000`

### `sql-mysql://` and `sql-mariadb://`
Module: `database-sql` (SqlDatabase.kt:114, 125; auth parse HikariPool.kt:64)
```
sql-mysql://user:password@host[:port]/database[?<pool and/or driver params>]
sql-mariadb://user:password@host[:port]/database[?...]
```
Becomes `jdbc:mysql://host:port/database?...` (driver `com.mysql.cj.jdbc.Driver`) / `jdbc:mariadb://...` (`org.mariadb.jdbc.Driver`).
| part | required | default | meaning |
|---|---|---|---|
| user:password | yes (must contain `:`) | - | `java.net.URI` userInfo, split on first `:` (URI decodes percent-escapes) |
| host | yes | - | `URI.host` must be non-null (e.g. underscores in hostname -> error) |
| port | no | driver default | |
| /database | by convention | - | `rawPath` verbatim |
| pool params | no | Hikari defaults | `maxPoolSize`, `minIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime`, `validationTimeout` (ms), `poolName` |
| other query | no | - | forwarded to JDBC driver |

Error: `IllegalStateException("Invalid MySQL URL. Expected: sql-mysql://user:password@host:port/database")` (MariaDB analog).
Example: `sql-mysql://app:s3cret@db.internal:3306/app?maxPoolSize=20&useSSL=true`

### `json-files://`
Module: `database-jsonfile` (JsonFileDatabase.kt:144)
```
json-files://<folder>
```
| part | required | default | meaning |
|---|---|---|---|
| folder | yes (blank -> working dir) | - | Starts with `/` -> absolute (`json-files:///tmp/x`). Otherwise resolved against the working directory (`json-files://./data`). Everything after `://` is the path, including any `?`. |

Surprises: one `<table>.json` per table (table name filtered to letters/digits); whole table loaded into memory on first access; migrates old extension-less files. No query params. Helper: `Database.Settings.jsonFile(folder: KFile)`.
Example: `json-files://./local-data`

### `mongodb://`
Module: `database-mongodb` (MongoDatabase.kt:145)
```
mongodb://[user:pass@]host[:port][,host2...]/<databaseName>[?<mongo options>]
```
Regex: `mongodb://.*/(?<databaseName>[^?]+)(?:\?.*)?` (full match required; greedy, so databaseName follows the LAST `/` before any `?`).
| part | required | default | meaning |
|---|---|---|---|
| databaseName | yes | - | |
| `atlasSearch=true` | no | false | Custom flag, stripped before the string goes to the driver; enables Atlas Search. Detected by `url.contains("atlasSearch=true")`. |
| other query | no | - | standard MongoDB connection-string options (`ConnectionString`) |

Surprises: `retryWrites` defaults to **false** (DocumentDB compat) unless URL sets `retryWrites=true`. With TLS and a host containing `.docdb.amazonaws.com`, trusts the embedded RDS CA bundle. Pool max = 4 if env `FUNCTIONS_WORKER_RUNTIME` or `AWS_EXECUTION_ENV` is set, else 100. UUID representation STANDARD. No match -> `IllegalStateException("Invalid mongodb URL...")`.
Helper: `Database.Settings.mongoDb(connectionString)`.
Examples: `mongodb://localhost:27017/mydb`, `mongodb://u:p@h1:27017,h2:27017/app?replicaSet=rs0&atlasSearch=true`

### `mongodb+srv://`
Module: `database-mongodb` (MongoDatabase.kt:164)
```
mongodb+srv://[user:pass@]host/<databaseName>[?<options>]
```
Same as `mongodb://` (incl. `atlasSearch=true`, `retryWrites=false` default). Difference: the `atlasSearch` strip only removes `?atlasSearch=true` / `&atlasSearch=true`; it does not fix a following `&`, so `?atlasSearch=true&x=1` becomes `&x=1` (broken). The `mongodb://` handler handles that case; `+srv` does not (likely bug; not tested).
Example: `mongodb+srv://u:p@cluster0.abcde.mongodb.net/prod?retryWrites=true&atlasSearch=true`

### `mongodb-test://`
Module: `database-mongodb` (MongoDatabase.kt:181; embeddedMongo.kt:19)
```
mongodb-test[://[?version=<v>]]
```
| part | required | default | meaning |
|---|---|---|---|
| `version` (alias `mongoVersion`) | no | `V8_2` | Passed verbatim to `Version.Main.valueOf(...)`: must be the enum name, e.g. `V7_0` (`7.0` fails here, unlike `mongodb-file`). |

Surprises: **starts an embedded mongod** (flapdoodle; downloads binaries) on a free port; temp data folder deleted on JVM exit. databaseName is always `default`. Test-only. Helper `mongoDbTest(version)` emits `?version=null` when null -> `valueOf("null")` throws. Regex only allows `mongodb-test`, `mongodb-test://`, `mongodb-test://?params`.
Examples: `mongodb-test://`, `mongodb-test://?version=V7_0`

### `mongodb-file://`
Module: `database-mongodb` (MongoDatabase.kt:200; embeddedMongo.kt:29)
```
mongodb-file://<folder>[?databaseName=<n>&port=<p>&version=<v>]
```
| part | required | default | meaning |
|---|---|---|---|
| folder | yes (`[^?]+`) | - | `File(folder)`, mkdirs; data dir for embedded mongod. Persistent (not deleted). |
| `databaseName` | no | `default` | |
| `port` | no | 54961 | non-integer silently -> default |
| `version` (alias `mongoVersion`) | no | `V8_2` | `.`->`_`, `V` prefix added if missing (`7.0`, `7_0`, `V7_0`); unknown -> `valueOf` throws |

Surprises: **starts an embedded mongod** on a fixed default port; two on the same port collide. Dev/local use. Helper `mongoDbFile(folder, port, databaseName)` emits literal `port=null` / `databaseName=null` when null (database name becomes "null").
Example: `mongodb-file://./build/mongo?databaseName=app&port=27018`

### `postgresql://`
Module: `database-postgres` (PostgresDatabase.kt:103; parse HikariPool.kt:63)
```
postgresql://[user[:password]@]host[:port]/<database>[?<pool and/or jdbc params>]
```
Becomes `jdbc:postgresql://host:port/database?...`, driver `org.postgresql.Driver`.
| part | required | default | meaning |
|---|---|---|---|
| host | yes | - | `java.net.URI.host` |
| user, password | no | - | Used only if BOTH non-blank; otherwise no credentials set on Hikari (use JDBC `?user=..&password=..`). |
| /database | by convention | - | `rawPath` verbatim |
| pool params | no | Hikari defaults | same 7 keys as MySQL |
| other query | no | - | forwarded to pgjdbc (e.g. `sslmode=require`) |

Error text says credentials are required (`postgresql://[user]:[password]@[destination]`) but the code does not require them. Helper `Database.Settings.postgres(username, password, host)` builds `postgresql://u:p@host` with no database path and no escaping.
Example: `postgresql://app:pw@db.example.com:5432/app?sslmode=require&maxPoolSize=10`

### `cassandra://`
Module: `database-cassandra` (CassandraDatabase.kt:120)
```
cassandra://[user:pass@]host[:port][,host[:port]...]/<keyspace>[?dc=<dc>&rf=<n>]
```
Regex: `cassandra://(?:([^:]+):([^@]+)@)?([^/]+)/([^?]+)(?:\?(.*))?`
| part | required | default | meaning |
|---|---|---|---|
| user:pass | no | none | used only if both present |
| hosts | yes | port 9042 each | comma-separated `host[:port]` |
| keyspace | yes | - | |
| `dc` (alias `datacenter`) | no | `datacenter1` | local datacenter |
| `rf` (alias `replication_factor`) | no | 1 | replication factor; non-int silently -> 1 |

Surprises: **creates the keyspace if missing** (`CREATE KEYSPACE IF NOT EXISTS ... SimpleStrategy`) on session start; no TLS; no URL-decoding; unknown params ignored. Mismatch -> `IllegalArgumentException("Invalid Cassandra URL: <url>")` (leaks the URL incl. password into the message).
Examples: `cassandra://localhost/app`, `cassandra://u:p@c1:9042,c2:9042/app?dc=us-east&rf=3`

### `cassandra-test://`
Module: `database-cassandra` (CassandraDatabase.kt:146, 299)
URL ignored. **Always throws `NotImplementedError("cassandra-test:// requires TestContainers implementation. Use embeddedCassandra() instead.")`**; not usable.

### `keyspaces://`
Module: `database-cassandra` (CassandraDatabase.kt:152)
```
keyspaces://<region>/<keyspace>[?...]
```
| part | required | default | meaning |
|---|---|---|---|
| region | yes | - | AWS region; endpoint `cassandra.<region>.amazonaws.com:9142`; also the datacenter and SigV4 region |
| keyspace | yes | - | |
| query | no | - | matched by regex but ignored |

Surprises: SigV4 auth via default AWS credentials (no userinfo); TLS with default JVM truststore; forces `LOCAL_QUORUM`; keyspace is NOT created (do it in Terraform/console). Error: `IllegalArgumentException("Invalid Keyspaces URL: ...")`.
Example: `keyspaces://us-west-2/myapp`

### `native://`
Module: `database-sqlite` (KMP; SqliteDatabase.kt:48)
```
native://<location>
```
| part | required | default | meaning |
|---|---|---|---|
| location | yes | - | everything after `://`; no query parsing. JVM: file path. iOS/macOS: relative path is inside Application Support (per class KDoc; `databasePath` not read). Android: must be absolute (e.g. `context.getDatabasePath("app.db").path`). Web: file name WITHOUT `/` (`require` fails otherwise), stored in OPFS. |

Surprises: single connection behind a mutex; WAL mode and `busy_timeout=5000` (bundled driver, openSqlite.bundled.kt). Web needs `index.mjs` + `sqlite3.wasm` served under `lightningkite-sqlite/`. Location only validated at first open (lazy).
Examples: `native:///var/data/app.db`, `native://app.db` (web)

## VectorDatabase

`VectorDatabase.Settings(url: String = "ram")` — `database/src/commonMain/kotlin/com/lightningkite/services/database/VectorDatabase.kt`. Each handler below reuses the `Database.Settings` handler of the same scheme and returns that database, which is also a `VectorDatabase`.

| scheme | preload | backend |
|---|---|---|
| `ram` | `Database.Settings` (its companion registers it) | `InMemoryDatabase`, brute-force cosine |
| `mongodb://…`, `mongodb+srv://…` | `MongoDatabase.Companion` | Atlas `$vectorSearch` (or MongoDB 8.2+ with mongot); `mongodb-test`/`mongodb-file` aren't offered, having no mongot |
| `postgresql://…` | `PostgresDatabase.Companion` | pgvector; `prepare` runs `CREATE EXTENSION IF NOT EXISTS vector` |

Surprises: a `VectorDatabase.Settings` and a `Database.Settings` with the same URL each build their own client and pool; nothing shares them. `delay://` isn't registered here.

## Cache

`Cache.Settings(url: String = "ram")` — `cache/src/commonMain/kotlin/com/lightningkite/services/cache/Cache.kt` (class ~:50, registrations :57-58).

### `ram://`
Module: `cache` (Cache.kt:57). No parts; rest ignored. Thread-safe `MapCache`. Example: `ram`

### `ram-unsafe://`
Module: `cache` (Cache.kt:58). No parts; rest ignored. `MapCacheUnsafe`, not thread-safe. Example: `ram-unsafe`

### `redis://`
Module: `cache-redis` (RedisCache.kt:126)
```
redis://[[user:]password@]host[:port][/<db>][?<lettuce options>]
```
The **entire URL is passed unchanged to Lettuce `RedisClient.create(url)`**; the library parses nothing itself, so Lettuce RedisURI syntax applies (I did not verify Lettuce's exact option names). Only the exact scheme `redis` is registered, so `rediss://` (TLS) and `redis-sentinel://` fail with "No handler". The client is built lazily, so a bad URL errors on first connect, not at settings parse. Helper `Cache.Settings.redis(url)` prefixes `redis://`.
Examples: `redis://localhost:6379`, `redis://:pw@cache.internal:6379/2`

### `memcached://`
Module: `cache-memcached` (MemcachedCache.kt:104)
```
memcached://host[:port][,host[:port]...]      (separators: comma or space)
```
| part | required | default | meaning |
|---|---|---|---|
| host list | effectively yes (blank -> empty list, behavior of XMemcached with none not verified) | - | split on `,` or space |
| port | no | 11211 | non-numeric silently -> 11211 |

No query, auth, or path (a path would be glued into the host). IPv6 literals break (split on first `:`). Client lazy. Helper `Cache.Settings.memcached(vararg InetSocketAddress)`.
Example: `memcached://10.0.0.5:11211,10.0.0.6:11211`

### `memcached-test`
Module: `cache-memcached` (MemcachedCache.kt:96; EmbeddedMemcached.kt:50)
URL ignored. **Spawns a local `memcached` process from PATH** when settings are instantiated (blocks up to ~10s polling 127.0.0.1:11211), registers a shutdown hook to kill it, and connects to fixed `127.0.0.1:11211`. Fails if `memcached` is not installed; conflicts with an already-running memcached. Test-only. Example: `memcached-test`

### `memcached-aws://`
Module: `cache-memcached` (MemcachedCache.kt:115)
```
memcached-aws://<config-endpoint-host>[:port]
```
Host required; port default 11211 (non-numeric -> 11211). Uses `AutoDiscoveryCacheClient` (ElastiCache auto-discovery against the config endpoint). No credentials/query.
Example: `memcached-aws://mycache.abc123.cfg.use1.cache.amazonaws.com:11211`

### `dynamodb://`
Module: `cache-dynamodb` (DynamoDbCache.kt:133)
```
dynamodb://[<accessKey>:<secretKey>@]<region>/<tableName>
```
Regex: `dynamodb://(?:(?<access>[^:]+):(?<secret>[^@]+)@)?(?<region>[^/]+)/(?<tableName>.+)`
| part | required | default | meaning |
|---|---|---|---|
| accessKey:secretKey | no | AWS `DefaultCredentialsProvider` chain | static creds only if both non-blank |
| region | yes | - | `Region.of(...)` |
| tableName | yes | - | `.+`: no query parsing; `?x=y` would become part of the table name. (The class's own default `tableName = "cache"` is not used by the URL handler.) |

Surprises: HTTP client/config come from `context[AwsConnections]`. Table readiness (`ready`) is awaited on connect/first use; I did not trace whether the table is auto-created. Error: `IllegalStateException("Invalid dynamodb URL...")`. Helpers: `dynamoDb(region, tableName)`, `dynamoDb(accessKey, secretKey, region, tableName)`.
Example: `dynamodb://us-west-2/app-cache`

### `dynamodb-local`
Module: `cache-dynamodb` (DynamoDbCache.kt:130; testDynamoDb.kt:45)
URL ignored. **Downloads DynamoDB Local on first use** into `~/.dynamodblocal` (from AWS S3) and **starts `java -jar DynamoDBLocal.jar -inMemory -port 7999`**; fixed port 7999, singleton per JVM, killed on shutdown; `IllegalStateException` if not ready in 30s. Dummy creds, region us-west-2. Dev/test only. Example: `dynamodb-local`

`cache-dynamodb-kmp`: contains only `build/`; no source, no registrations.

## PubSub

`PubSub.Settings(url: String = "local")` — `pubsub/src/commonMain/kotlin/com/lightningkite/services/pubsub/PubSub.kt` (class ~:126, registrations :131-132).

### `local://`
Module: `pubsub` (PubSub.kt:131). No parts; rest ignored. In-process only (not shared across instances). Example: `local`

### `debug://`
Module: `pubsub` (PubSub.kt:132). No parts; rest ignored. `DebugPubSub` (KDoc: "console logging implementation"; I did not read it to confirm delivery semantics). Example: `debug`

### `redis://`
Module: `pubsub-redis` (RedisPubSub.kt:228)
```
redis://[[user:]password@]host[:port][/<db>][?<lettuce options>]
```
Same as cache `redis://`: the whole URL is passed to Lettuce `RedisClient.create(url)` (note: created eagerly here, not in a lazy factory); only `redis` is registered (no `rediss`). Helper `PubSub.Settings.redis(url)` prefixes `redis://`.
Example: `redis://localhost:6379`

### `dynamodb-pubsub://`
Module: `pubsub-aws` (DynamoDbPubSub.kt:119)
```
dynamodb-pubsub://[<accessKey>:<secretKey>@]<region>/<tableName>[?pollInterval=<ms>&fastEmit=<bool>]
```
| part | required | default | meaning |
|---|---|---|---|
| accessKey:secretKey | no | AWS default credentials chain | static only if both non-blank |
| region | yes | - | `Region.of` |
| tableName | yes (`[^?]+`) | - | |
| `pollInterval` | no | 15 (ms) | long ms; non-numeric silently -> 15ms |
| `fastEmit` | no | false | `toBoolean()` (anything but "true" -> false). One DynamoDB write per emit instead of two; same-millisecond messages may be unordered. |

Not URL-settable: `messageTtl` (5 min default). Polling-based (reads every `pollInterval`). I did not trace table auto-creation (`ensureReady`/`doInitialize`). Error: `IllegalStateException("Invalid dynamodb-pubsub URL. Expected: ...")`. Unknown params ignored.
Example: `dynamodb-pubsub://us-east-1/app-pubsub?pollInterval=50&fastEmit=true`

### MQTT
`pubsub-mqtt`, `pubsub-mqtt-aws`, `pubsub-mqtt-paho` have no Kotlin sources in this checkout (only `build/`, and `terraform/`/`local/` for paho); no `PubSub.Settings.register` exists for them. Nothing to document.

## Files (`ExternalFileSystem.Settings`)

Field: `url`. Default: `file://<workingDirectory>/local/files?serveUrl=files` (creates the directory as a side effect of building the default).
Signed-URL duration (`signedUrlDuration`) grammar is identical for `file`, `s3`, `azure-blob`: absent = 1 hour; `forever` or `null` = unsigned; all digits = seconds; otherwise ISO-8601 (`Duration.parse`, e.g. `PT30M`).

### `file://`

- Module: `files` (`files/src/commonMain/kotlin/com/lightningkite/services/files/ExternalFileSystem.kt:331`)

```
file://<path>?serveUrl=<relative-or-absolute-url>[&signedUrlDuration=<d>]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<path>` | yes | none | Everything between `://` and the first `?` or `#`. Used verbatim as the root directory (`file:///var/uploads` -> `/var/uploads`; `file://files` -> relative `files`). |
| `serveUrl` | yes | none | Throws `IllegalArgumentException("No serveUrl provided")` if absent. Contains `://` -> used as absolute base. Otherwise -> `${context.publicUrl}/<serveUrl trimmed of '/'>/`. |
| `signedUrlDuration` | no | 1 h | See above. |

Examples:
```
file:///var/uploads?serveUrl=files
file:///var/uploads?serveUrl=https://cdn.example.com/files&signedUrlDuration=PT30M
```
Surprises: query parsed by naive split on `&`/`=`, no percent-decoding (a `serveUrl` containing `&` or `=` breaks). Unknown params ignored. Invalid durations throw from `Duration.parse`.

### `s3://`

- Module: `files-s3` (`files-s3/src/main/kotlin/com/lightningkite/services/files/s3/S3ExternalFileSystem.kt:918`)

```
s3://[<accessKey>:<secretKey>@ | <profile>@]<bucket>.[s3-]<region>.amazonaws.com[/][?signedUrlDuration=<d>]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `accessKey:secretKey@` | no | none | Static credentials, used only if both non-blank. Not URL-decoded. |
| `profile@` | no | none | Named profile via `DefaultCredentialsProvider.builder().profileName(..)`. |
| (neither) | | | `DefaultCredentialsProvider` (env vars, instance profile, etc.). |
| `<bucket>` | yes | none | `[^.]+`, so bucket names containing dots do not match. |
| `<region>` | yes | none | `[^.]+` after optional `s3-`; passed to `Region.of`. |
| host suffix | yes | none | Must literally end `.amazonaws.com` (custom endpoints such as MinIO or LocalStack are not supported by this parser). |
| path | no | none | Only an optional single trailing `/` is allowed; any other path makes the regex fail. |
| `signedUrlDuration` | no | 1 h | See above. Other params ignored (first value wins). |

Mismatch throws `IllegalArgumentException("Invalid S3 URL...")`.
Examples:
```
s3://my-bucket.s3-us-east-1.amazonaws.com
s3://AKIAEXAMPLE:secret@my-bucket.us-east-1.amazonaws.com/?signedUrlDuration=forever
s3://prod-profile@my-bucket.s3-us-east-1.amazonaws.com
```
Surprises: `bucket.s3.amazonaws.com` (no region) parses with region `s3` (invalid region, not rejected at parse time). Whole URL must match (`matchEntire`).

### `azure-blob://`

- Module: `files-azure-blob` (`files-azure-blob/src/main/kotlin/com/lightningkite/services/files/azure/AzureBlobExternalFileSystem.kt:284`)

```
azure-blob://<account>[:<accountKey>]@<container>[?signedUrlDuration=<d>][&endpoint=<url>]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<account>` | yes | none | Text before first `:` of userinfo (userinfo = everything before the LAST `@`). |
| `<accountKey>` | no | none | Shared key used literally (base64, no decoding). If absent -> `DefaultAzureCredentialBuilder` (managed identity, env, az login; user-assigned identity picked via env var `AZURE_CLIENT_ID`). |
| `<container>` | yes | none | After the last `@`, trailing `/` trimmed; must be non-empty and contain no `/`. |
| `endpoint` | no | `https://<account>.blob.core.windows.net` | Override for Azurite or sovereign clouds. |
| `signedUrlDuration` | no | 1 h | See above. `forever`/`null` = unsigned (public container). |

No `@` in authority -> `IllegalArgumentException("Invalid Azure Blob URL...")`. Bad container -> `IllegalArgumentException`.
Examples:
```
azure-blob://myacct:BASE64KEY==@uploads?signedUrlDuration=PT15M
azure-blob://myacct@uploads
azure-blob://devstoreaccount1:KEY@uploads?endpoint=http://127.0.0.1:10000/devstoreaccount1
```
Surprises: params not percent-decoded at parse time; the account key may end in `=` safely because only the query is split on `=`. A container name cannot contain `@`. Note `endpoint=http://...?` values containing `&` would split.

## FileScanner (`FileScanner.Settings`)

Field: `url`. Default: `file://files` (a scheme NOT registered on `FileScanner`; invoking the default throws unknown-scheme. Ambiguous whether intentional; looks like a copy-paste bug). Source: `files/src/commonMain/kotlin/com/lightningkite/services/files/FileScanner.kt:33`.

### `mime://`

- Module: `files` (`FileScanner.kt:38`)

```
mime://
```
No parts; everything after the scheme is ignored. Builds `CheckMimeFileScanner(name, context)`.
Example: `mime://`

### `clamav://`

- Module: `files-clamav` (`files-clamav/src/main/kotlin/com/lightningkite/services/files/clamav/ClamAvFileScanner.kt:117`)

```
clamav://<host>[:<port>]/[<platform>][?timeoutSeconds=<n>]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<host>` | yes | none | `[^:/]+`. |
| `<port>` | no | 3310 | clamd TCP port. |
| `/` after host | yes | none | The slash is mandatory in the regex (`clamav://host:3310` alone does NOT match). |
| `<platform>` | no | `JVM_PLATFORM` | `Platform.valueOf(..)`; KDoc (line 33) lists `UNIX`, `JVM_PLATFORM`, `WINDOWS`. Enum itself is in an external lib, not checked. Invalid name throws `IllegalArgumentException` from `valueOf`. |
| `timeoutSeconds` | no | 30 | Per-scan timeout; non-numeric silently falls back to 30. Other params ignored. |

Mismatch throws `IllegalStateException("Invalid ClamAV...")`.
Examples:
```
clamav://localhost:3310/UNIX
clamav://clamd.internal/?timeoutSeconds=60
```
Surprises: omitted platform -> `JVM_PLATFORM`, but the helper `FileScanner.Settings.clamav(...)` defaults platform to `UNIX` and always emits `?timeoutSeconds=`. Timeout is fail-closed (throws `FileScanException`).


## Email (`EmailService.Settings`)

Field: `url`. Default: `console`. Source: `email/src/commonMain/kotlin/com/lightningkite/services/email/EmailService.kt:126`.

### `console://`
- Module: `email` (`EmailService.kt:126`). Format: `console` (tail ignored). Logs emails. Example: `console`

### `test://`
- Module: `email` (`EmailService.kt:127`). Format: `test` (tail ignored). Collects in memory; test-only. Example: `test`

### `smtp://`

- Module: `email-javasmtp` (`email-javasmtp/src/main/kotlin/com/lightningkite/services/email/javasmtp/JavaSmtpEmailService.kt:142`)

```
smtp://[<username>:<password>@]<host>:<port>?fromEmail=<addr>[&fromLabel=<name>][&insecure=true]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `username:password@` | no | none (no auth) | Username `[^:]+`; password `.+` (greedy, may contain `:`/`@`). Not URL-decoded. Auth only enabled if both present. |
| `<host>` | yes | none | `[^:@]+`. |
| `<port>` | yes | none | Digits; regex requires it. |
| `fromEmail` | yes (in practice) | none | Default sender. Regex does not require it, but code does `params["fromEmail"]!!`, so absence = `NullPointerException`, not a clear error. |
| `fromLabel` | no | `context.projectName` | Sender display name. |
| `insecure` | no | `false` | Only the exact value `true` disables TLS. Default requires TLS: implicit SSL on port 465, STARTTLS (required) on every other port. |

Mismatch throws `IllegalStateException("Invalid SMTP URL...")`.
Examples:
```
smtp://apikey:SG.xxx@smtp.sendgrid.net:587?fromEmail=support@example.com
smtp://relay.internal:25?fromEmail=noreply@example.com&insecure=true
```
Surprises: query values are not URL-decoded (`+` stays `+`; a space in `fromLabel` must be a literal space). The helper `EmailService.Settings.smtp(..., fromLabel = null)` emits literal `fromLabel=null`, so the sender label becomes "null".

### `mailgun://`

- Module: `email-mailgun` (`email-mailgun/src/main/kotlin/com/lightningkite/services/email/mailgun/MailgunEmailService.kt:32`)

```
mailgun://<apiKey>@<domain>
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<apiKey>` | yes | none | `[^@]+`. |
| `<domain>` | yes | none | `.+` (rest of string, including any `?...`). Used as `https://api.mailgun.net/v3/<domain>/messages` and the `noreply@<domain>` fallback sender. |

Mismatch throws `IllegalStateException("Invalid Mailgun URL...")`.
Example: `mailgun://key-0123456789abcdef@mg.example.com`
Surprises: no query params; US endpoint (`api.mailgun.net`) is hard-coded (no EU option).

### `human://`

- Module: `human-services` (`human-services/src/main/kotlin/com/lightningkite/services/human/HumanEmailService.kt:49`; parsing in `human-services/src/main/kotlin/com/lightningkite/services/human/util.kt:27`)

```
human://[<host>][:<port>]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<host>` | no | n/a | Ignored entirely. |
| `<port>` | no | 8800 | Dashboard port. Text after first `:` up to first `/`, `toIntOrNull`. |

Example: `human://localhost:8800`
Surprises: dev tool (human-operated web dashboard; outbound emails appear in it). An unparsable port (e.g. `human://localhost:abc` or `human://localhost:8800?x=1`) silently falls back to 8800. Panels are shared per port across the human email/SMS services (`HumanServiceRegistry`).

## Email inbound (`EmailInboundService.Settings`)

Field: `url`. Default: `console`. Source: `email-inbound/src/commonMain/kotlin/com/lightningkite/services/email/EmailService.kt:92`.

### `console://`
- Module: `email-inbound` (`EmailService.kt:92`). Tail ignored. Example: `console`

### `test://`
- Module: `email-inbound` (`EmailService.kt:93`). Tail ignored; test-only. Example: `test`

### `imap://` and `imaps://`

- Module: `email-inbound-imap` (`email-inbound-imap/src/main/kotlin/com/lightningkite/services/email/imap/ImapEmailInboundService.kt:136` imap, `:113` imaps)

```
imap://<username>:<password>@<host>[:<port>][/<folder>]
imaps://<username>:<password>@<host>[:<port>][/<folder>]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<username>` | yes | none | `[^:]+`, URL-decoded (use `%40` for `@` in usernames). |
| `<password>` | yes | none | `[^@]+`, URL-decoded (so a literal `%` or `@` must be percent-encoded). |
| `<host>` | yes | none | `[^:@]+`. |
| `<port>` | no | 143 (`imap`), 993 (`imaps`) | |
| `<folder>` | no | `INBOX` | Everything after the first `/`; blank -> `INBOX`. Not URL-decoded. |

`imap` = plain connect then required STARTTLS (`requireStartTls` defaults true). `imaps` = implicit SSL with server identity check. Mismatch throws `IllegalStateException`.
Examples:
```
imaps://user%40gmail.com:app-password@imap.gmail.com:993/INBOX
imap://bot:pw@mail.internal/Support
```
Surprises: the regex has a `?params` group but the greedy `folder` group swallows any `?...`, so query params are never parsed and `?x` would become part of the folder name. Pull-only (no webhook). The `Settings.imap(...)` helper does not URL-encode credentials.

### `mailgun://` (inbound)

- Module: `email-inbound-mailgun` (`email-inbound-mailgun/src/main/kotlin/com/lightningkite/services/email/mailgun/MailgunEmailInboundService.kt:84`). Class is `@Untested`.

```
mailgun://<apiKey>@<domain>
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<apiKey>` | yes | none | Parsed via `java.net.URI`: first `:`-separated segment of `userInfo`; blank/missing -> `IllegalArgumentException`. Used as webhook signing key (HMAC verification). |
| `<domain>` | no | `""` | `URI.host`; informational only. |

Example: `mailgun://key-0123456789abcdef@mg.example.com`
Surprises: registered scheme is `mailgun`, NOT `mailgun-inbound` (class KDoc says `mailgun-inbound://`; stale). Because it uses `java.net.URI`, a URL ending `mailgun://KEY@` (empty host; this is what `tf.kt:66` emits) gives `userInfo == null` on the local JDK (verified with a scratch program), which then throws "Mailgun API key is required". A non-empty host appears to be needed in practice. Not verified end-to-end against a full run.

### `sendgrid://`

- Module: `email-inbound-sendgrid` (`email-inbound-sendgrid/src/main/kotlin/com/lightningkite/services/email/sendgrid/SendGridEmailInboundService.kt:93`). `@Untested`.

```
sendgrid://<verificationKey>@<host>
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<verificationKey>` | yes | none | `java.net.URI.userInfo` (percent-decoded): the base64 ECDSA public key for webhook signature verification. Missing/blank -> `IllegalArgumentException`. |

Example: `sendgrid://MFkwEwYHKoZIzj0CAQYI%2Bxyz%3D@x`
Surprises: the documented/helper format `sendgrid://<URL-encoded key>@` (empty host; `sendgrid()` helper line ~121) parses with `userInfo == null` under `java.net.URI` (verified on the local JDK for the analogous `KEY@` form), so it would throw "verification key is required". `tf.kt:62` emits plain `sendgrid://` which always throws (key mandatory). Likely bug; only works if a host is appended. Not verified end-to-end.

### `ses://`

- Module: `email-inbound-ses` (`email-inbound-ses/src/main/kotlin/com/lightningkite/services/email/ses/SesEmailInboundService.kt:93`)

```
ses://?topicArn=<arn>[&bucket=<bucket>&region=<region>]
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `topicArn` | yes | none | SNS topic ARN; only messages from this topic are accepted. Missing -> `IllegalArgumentException`. |
| `bucket` | no | none | S3 bucket where the receipt rule stores raw mail (for >150 KB mail, up to 40 MB). |
| `region` | iff `bucket` | none | S3 region for the bucket; `bucket` without `region` throws `IllegalArgumentException`. |

Example: `ses://?topicArn=arn:aws:sns:us-east-1:123456789012:inbound-mail&bucket=my-mail&region=us-east-1`
Surprises: everything before the first `?` is ignored; params split on `&` and first `=`, no URL-decoding. S3 client uses ambient AWS credentials (`AwsConnections`); no creds in URL.

### `human://` (inbound)

- Module: `human-services` (`human-services/src/main/kotlin/com/lightningkite/services/human/HumanEmailInboundService.kt:91`). Same format and port rules as email `human://` above (`human://[host][:port]`, port default 8800, host ignored). Dev tool: a person types inbound emails into the dashboard.
Example: `human://localhost:8800`


## SMS (`SMS.Settings`)

Field: `url`. Default: `console`. Source: `sms/src/commonMain/kotlin/com/lightningkite/services/sms/SMS.kt:106`.

### `console://`
- Module: `sms` (`SMS.kt:106`). Tail ignored. Example: `console`

### `test://`
- Module: `sms` (`SMS.kt:109`). Tail ignored; test-only. Example: `test`

### `twilio://`

- Module: `sms-twilio` (`sms-twilio/src/main/kotlin/com/lightningkite/services/sms/twilio/TwilioSMS.kt:185`)

```
twilio://<accountSid>:<authToken>@<fromPhoneNumber>
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<accountSid>` | yes | none | `[^:]+`. Basic-auth user. |
| `<authToken>` | yes | none | `[^@]+`. Basic-auth password. |
| `<fromPhoneNumber>` | yes (code-level) | none | `.+`, E.164 sender. Regex makes `@phone` optional, but then throws `IllegalArgumentException("Twilio phone number not provided...")`. |

Example: `twilio://ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:your_auth_token@+15551234567`
Surprises: only Account SID + Auth Token (no API-key variant here, unlike phone calls). No URL-decoding; `+` kept literally. No query params.

### `awssms://`

- Module: `sms-aws` (`sms-aws/src/main/kotlin/com/lightningkite/services/sms/aws/AwsSms.kt:222`)

```
awssms://[<accessKey>:<secretKey>@]<region>?originationIdentity=<id>
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `accessKey:secretKey@` | no | default AWS credential chain | Static keys used only if both present (`[^:]+`, `[^@]+`; not decoded). |
| `<region>` | yes | none | `[a-zA-Z0-9-]+`. |
| `originationIdentity` | yes | none | `.+` to end of string (phone number, pool ID, or sender ID). Must be the only query param and last (no `&` handling; extra params become part of the identity). |

Mismatch throws `IllegalArgumentException("Invalid AWS SMS URL...")`.
Examples:
```
awssms://us-east-1?originationIdentity=+15551234567
awssms://AKIAEXAMPLE:secret@us-east-1?originationIdentity=pool-0123456789abcdef
```
Surprises: uses AWS End User Messaging SMS (PinpointSmsVoiceV2). Health check returns WARNING in sandbox mode.

### `human://`
- Module: `human-services` (`human-services/src/main/kotlin/com/lightningkite/services/human/HumanSmsService.kt:48`). Same as email `human://`: `human://[host][:port]`, port default 8800, host ignored. Example: `human://localhost:8800`

## SMS inbound (`SmsInboundService.Settings`)

Field: `url`. Default: `console`. Source: `sms-inbound/src/commonMain/kotlin/com/lightningkite/services/sms/SmsInboundService.kt:83`.

### `console://`
- Module: `sms-inbound` (`SmsInboundService.kt:83`). Tail ignored. Example: `console`

### `test://`
- Module: `sms-inbound` (`SmsInboundService.kt:84`). Tail ignored; test-only. Example: `test`

### `twilio://` (inbound)

- Module: `sms-inbound-twilio` (`sms-inbound-twilio/src/main/kotlin/com/lightningkite/services/sms/twilio/TwilioSmsInboundService.kt:327`)

```
twilio://<accountSid>:<authToken>@<phoneNumber>
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<accountSid>` | yes | none | `[^:]+`. |
| `<authToken>` | yes | none | `[^@]+`. |
| `<phoneNumber>` | yes | none | `.+`, E.164 number to receive on (looked up to a phone-number SID via the Twilio API when configuring the webhook). |

Mismatch throws `IllegalArgumentException("Invalid Twilio URL...")`.
Example: `twilio://ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:your_auth_token@+15551234567`
Surprises: unlike outbound, `@phone` is required by the regex itself.

### `human://` (inbound)
- Module: `human-services` (`human-services/src/main/kotlin/com/lightningkite/services/human/HumanSmsInboundService.kt:89`). `human://[host][:port]`, port default 8800, host ignored. Example: `human://localhost:8800`


## Notifications (`NotificationService.Settings`)

Field: `url`. NO default value (required). Source: `notifications/src/commonMain/kotlin/com/lightningkite/services/notifications/NotificationService.kt:146`.

### `console://`
- Module: `notifications` (`NotificationService.kt:146`). Tail ignored. Example: `console`

### `test://`
- Module: `notifications` (`NotificationService.kt:147`). Tail ignored; test-only. Example: `test`

### `fcm://`

- Module: `notifications-fcm` (`notifications-fcm/src/main/kotlin/com/lightningkite/services/notifications/fcm/FcmNotificationClient.kt:152`)

```
fcm://<path-to-service-account.json>
fcm://{ ...inline service-account JSON... }
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| everything after `://` | yes | `""` | If it starts with `{`, treated as inline JSON. Otherwise treated as a file path (`fcm:///etc/secrets/fb.json` -> `/etc/secrets/fb.json`; `fcm://rel/path.json` -> relative) and read from disk. |

Errors: file missing -> `IllegalArgumentException("FCM credentials file not found at '...'")`. JSON decoded by `FcmServiceAccount.fromJson` (`FcmServiceAccount.kt:77`): required `project_id`, `client_email`, `private_key` (PEM PKCS#8 RSA); optional `token_uri` (defaults to `FcmServiceAccount.DEFAULT_TOKEN_URI`). Missing fields -> kotlinx.serialization exception.
Examples:
```
fcm:///etc/secrets/firebase-adminsdk.json
fcm://{"project_id":"my-app","client_email":"svc@my-app.iam.gserviceaccount.com","private_key":"-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"}
```
Surprises: no query params; any `?`/`#` becomes part of the path or JSON. Talks to FCM HTTP v1 directly (no Firebase Admin SDK).


## Phone calls (`PhoneCallService.Settings`)

Field: `url`. Default: `test`. Source: `phonecall/src/commonMain/kotlin/com/lightningkite/services/phonecall/PhoneCallService.kt:123`.

### `test://`
- Module: `phonecall` (`PhoneCallService.kt:123`). Tail ignored; in-memory, test-only. Example: `test`

### `console://`
- Module: `phonecall` (`PhoneCallService.kt:124`). Tail ignored; logs to console. Example: `console`

### `twilio://`

- Module: `phonecall-twilio` (`phonecall-twilio/src/main/kotlin/com/lightningkite/services/phonecall/twilio/TwilioPhoneCallService.kt:1270`)

Two forms, tried in this order (API-key first):
```
twilio://<accountSid>-<keySid>:<keySecret>@<fromPhoneNumber>      (API key)
twilio://<accountSid>:<authToken>@<fromPhoneNumber>               (auth token)
```

| Part | Required | Default | Meaning |
|---|---|---|---|
| `<accountSid>` | yes | none | API-key form: `[^/:]+` before a `-`. Auth-token form: `[^:]+`. |
| `<keySid>` | API-key form | none | `[^:]+` after the hyphen (Twilio `SK...`). |
| `<keySecret>` / `<authToken>` | yes | none | `[^@]+`. |
| `<fromPhoneNumber>` | yes | none | `.+`, default sender in E.164. |

Mismatch of both throws `IllegalArgumentException("Invalid Twilio URL...")`.
Examples:
```
twilio://ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:your_auth_token@+15551234567
twilio://ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx-SKxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:keySecret@+15551234567
```
Surprises: the separator between account and key SID is a hyphen (`AC...-SK...`); the code comment and error message say `/` (`account/keySid`), which is stale. An auth-token URL whose accountSid contains `-` would be misparsed as API-key form (Twilio SIDs normally have none). `twilioApiKey()` helper emits the hyphen form.

## LLM Access

Type: `LlmAccess.Settings(val url: String)` (`@JvmInline` value class, `ai/src/commonMain/.../interfaces.kt:50`). No default URL. No built-in schemes in module `ai` (no `test`/`console`); all come from provider modules. URLs configure access only (credentials/endpoint); the model is chosen per call via `LlmModelId`, so a model in the URL is ignored unless stated.

### `anthropic://`

Module: `ai-anthropic`. `ai-anthropic/src/main/kotlin/com/lightningkite/services/ai/anthropic/Settings.kt:38`

```
anthropic://?apiKey=<key>&baseUrl=<url>&version=<header>&maxTokens=<int>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority (`anthropic://<x>`) | no | ignored | legacy model id, ignored |
| `apiKey` | one of this / env | env `ANTHROPIC_API_KEY` | API key; `${VAR}` expanded. Throws `IllegalArgumentException` if neither present |
| `baseUrl` | no | `https://api.anthropic.com` | API root; `${VAR}` expanded; not trimmed of trailing `/` |
| `version` | no | `2023-06-01` | `anthropic-version` header; `${VAR}` expanded |
| `maxTokens` | no | `4096` | default max output tokens; non-int silently falls back to default |

Examples:
```
anthropic://?apiKey=sk-ant-xxxx
anthropic://?apiKey=${ANTHROPIC_API_KEY}&maxTokens=8192
```
Surprises: no URL-decoding. Registration is guarded by `supports()` (safe to load twice).

### `openai://`

Module: `ai-openai`. `ai-openai/src/main/kotlin/com/lightningkite/services/ai/openai/Settings.kt:66`

```
openai://?apiKey=<key>&baseUrl=<url>&organization=<org>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority | no | ignored | legacy model id, ignored |
| `apiKey` | one of this / env | env `OPENAI_API_KEY` | `${VAR}` expanded (unset var THROWS here). Throws if neither present |
| `baseUrl` | no | `https://api.openai.com/v1` | any OpenAI-compatible `/chat/completions` server (LM Studio, vLLM, Groq, OpenRouter...); trailing `/` trimmed; `${VAR}` expanded |
| `organization` | no | none | `OpenAI-Organization` header; `${VAR}` expanded |

Examples:
```
openai://?apiKey=sk-xxxx
openai://?apiKey=lm-studio&baseUrl=http://localhost:1234/v1
```
Surprises: keys and values ARE URL-decoded (`URLDecoder`, so a literal `+` becomes a space; encode as `%2B`). `${VAR}` with unset var throws `IllegalArgumentException("Environment variable $VAR not set")` (differs from other schemes). Not guarded by `supports()` before register.

### `ollama://`

Module: `ai-ollama`. `ai-ollama/src/main/kotlin/com/lightningkite/services/ai/ollama/Settings.kt:25`

```
ollama://[<model>]?baseUrl=<url>&autoStart=<bool>&autoPull=<bool>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority = `<model>` | only if `autoStart` or `autoPull` is true | empty | model to start/pull at instantiation; otherwise unused. Missing -> `IllegalArgumentException` |
| `baseUrl` | no | `http://localhost:11434` | server URL; `${VAR}` expanded |
| `autoStart` | no | `false` | start local `ollama serve` if down (`toBooleanStrictOrNull`: only `true`/`false`, anything else = false) |
| `autoPull` | no | `false` | pull model if not installed |

Examples:
```
ollama://?baseUrl=http://gpu-box:11434
ollama://llama3.2?autoStart=true&autoPull=true
```
Surprises: instantiation blocks (`runBlocking`) while starting server / downloading model. An `ollama://` scheme is also registered on `EmbeddingService.Settings` with different semantics; see that section. `ai-ollama/.../tf.kt:34` also references `supports("ollama")` (terraform helper, not a registration).

### `bedrock://`

Module: `ai-bedrock` (KMP; JVM profile support). `ai-bedrock/src/commonMain/kotlin/com/lightningkite/services/ai/bedrock/Settings.kt:109` (registered from `BedrockLlmAccess` companion init)

```
bedrock://[<accessKeyId>:<secretKey>@ | <profile>@][<ignored-model>]?region=<region>&downloadUrlAttachments=<bool>
```

| part | required? | default | meaning |
|---|---|---|---|
| userinfo `<key>:<secret>@` | no | default chain | static creds; each side `${VAR}` expanded (unset left literal) |
| userinfo `<profile>@` (no `:`) | no | -- | named profile from `~/.aws/credentials` via `loadProfileCredentials` (JVM only; others throw). Static keys only, no SSO/role |
| (no `@`) | -- | env chain | env `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` (+ optional `AWS_SESSION_TOKEN`); if either missing throws `LlmException.Auth`. No IMDS/container-role lookup |
| text after `@` / authority w/o `@` | no | ignored | legacy model id |
| `region` | no | env `AWS_REGION`, else `us-east-1` | `${VAR}` expanded |
| `downloadUrlAttachments` | no | `false` | server fetches URL attachments (SSRF risk; trusted prompts only) |

Examples:
```
bedrock://?region=us-west-2
bedrock://${AWS_ACCESS_KEY_ID}:${AWS_SECRET_ACCESS_KEY}@?region=us-west-2
bedrock://my-profile@?region=eu-west-1
```
Surprises: credentials are resolved eagerly at instantiation into a non-refreshing static provider (expiring creds won't refresh; use `bedrock-sdk`). Secret keys containing `/`, `+`, `@`, `:` are not decoded or escaped and can break parsing (split on first `@`/`:`).

### `bedrock-sdk://`

Module: `ai-bedrock-aws-sdk` (JVM). `ai-bedrock-aws-sdk/src/main/kotlin/com/lightningkite/services/ai/bedrock/awssdk/AwsSdkCredentials.kt:91`

```
bedrock-sdk://<model-id>?region=<region>&profile=<profile>&downloadUrlAttachments=<bool>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority = `<model-id>` | **yes** (non-empty, `require`) | -- | validated but otherwise unused (model chosen per call) |
| `region` | no | env `AWS_REGION`, else `us-east-1` | no `${VAR}` expansion here |
| `profile` | no | AWS SDK `DefaultCredentialsProvider` chain | uses SDK `ProfileCredentialsProvider` (`aws login`, `credential_process`; SSO/role need extra SDK deps) |
| `downloadUrlAttachments` | no | `false` | as above |

Examples:
```
bedrock-sdk://anthropic.claude-3-5-haiku-20241022-v1:0?region=us-east-1
bedrock-sdk://any?profile=dev&region=us-west-2
```
Surprises: model id is required even though ignored (unlike `bedrock://`). Credentials refresh per request. No credentials ever in URL. Guarded by `supports()`.

### `embedded://`

Module: `ai-embedded` (KMP: native/Android/iOS/JS). `ai-embedded/src/commonMain/kotlin/com/lightningkite/services/ai/embedded/Settings.kt:29`

```
embedded://<model-name>?path=<model path or HF id>&threads=<int>&contextSize=<int>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority = `<model-name>` | **yes** | -- | blank -> `IllegalArgumentException("Embedded URL is missing a model name")` |
| `path` | doc says required; handler does NOT enforce | null | model file path (native) or HuggingFace id/URL (web). Android/iOS engines throw `IllegalStateException("modelPath is required...")` at engine use; other engines not checked |
| `threads` | no | `4` | non-int silently -> default |
| `contextSize` | no | `4096` | non-int silently -> default |

Examples:
```
embedded://gemma-2b?path=/data/models/gemma-2b.tflite
embedded://phi-3-mini?path=Xenova/phi-3-mini&contextSize=2048
```
Surprises: here the model name in the authority is used (unlike other LLM schemes). No `${VAR}` expansion, no URL-decoding. Ambiguous: whether JVM/native/js engines tolerate missing `path` (not traced).

---

## Embeddings

Type: `EmbeddingService.Settings(val url: String)` (`embedding/src/commonMain/.../interfaces.kt:45`). No default URL, no built-in schemes in module `embedding`.

### `openai://`

Module: `embedding-openai`. `embedding-openai/src/main/kotlin/com/lightningkite/services/embedding/openai/Settings.kt:33`

```
openai://<model-id>?apiKey=<key>&baseUrl=<url>&organization=<org>&dimensions=<n>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority = `<model-id>` | **yes** (`require` non-blank) | -- | e.g. `text-embedding-3-small` |
| `apiKey` | one of this / env | env `OPENAI_API_KEY` | `${VAR}` expanded; unset var THROWS. Throws if neither |
| `baseUrl` | no | `https://api.openai.com/v1` | trailing `/` trimmed; `${VAR}` expanded |
| `organization` | no | none | header; `${VAR}` expanded |
| `dimensions` | no | none | truncation for v3 models; non-int silently ignored |

Examples:
```
openai://text-embedding-3-small?apiKey=sk-xxxx
openai://text-embedding-3-large?apiKey=${OPENAI_API_KEY}&dimensions=256
```
Surprises: keys/values URL-decoded (same as LLM `openai`).

### `ollama://`

Module: `embedding-ollama`. `embedding-ollama/src/main/kotlin/com/lightningkite/services/embedding/ollama/Settings.kt:30`

```
ollama://<model>?baseUrl=<url>&autoStart=<bool>&autoPull=<bool>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority = `<model>` | **yes** | -- | missing -> `IllegalArgumentException`. Only used for autoStart/autoPull; real model chosen per call |
| `baseUrl` | no | `http://localhost:11434` | `${VAR}` expanded |
| `autoStart` | no | `false` | |
| `autoPull` | no | `false` | |

Examples: `ollama://nomic-embed-text`, `ollama://nomic-embed-text?baseUrl=${OLLAMA_BASE_URL}&autoPull=true`.
Surprise: model required here even without auto flags (unlike LLM `ollama://`). Whether the model is otherwise used by the service is not traced (`OllamaEmbeddingService` only receives `name`, `baseUrl`, `context`).

### `bedrock://`

Module: `embedding-bedrock` (KMP). `embedding-bedrock/src/commonMain/kotlin/com/lightningkite/services/embedding/bedrock/Settings.kt:73`

```
bedrock://[<accessKeyId>:<secretKey>@ | <profile>@]<model-id>?region=<region>&dimensions=<n>
```

| part | required? | default | meaning |
|---|---|---|---|
| userinfo `<key>:<secret>@` | no | default chain | `${VAR}` expanded per side (unset left literal) |
| userinfo `<profile>@` | no | -- | `loadProfileCredentials` (JVM only) |
| (none) | -- | env chain | `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`(/`AWS_SESSION_TOKEN`); throws if missing (same helper pattern as LLM bedrock; message-level not re-verified here) |
| `<model-id>` | **yes** | -- | empty -> `IllegalArgumentException` |
| `region` | no | env `AWS_REGION`, else `us-east-1` | `${VAR}` expanded |
| `dimensions` | no | model default | `toInt()`: non-numeric THROWS `NumberFormatException` |

Examples:
```
bedrock://amazon.titan-embed-text-v2:0?region=us-east-1&dimensions=1024
bedrock://my-profile@cohere.embed-english-v3?region=us-west-2
```
Surprise: model id is in the URL here, unlike LLM `bedrock://`. Credentials resolved eagerly (non-refreshing).

### `bedrock-sdk://`

Module: `embedding-bedrock-aws-sdk` (JVM). `embedding-bedrock-aws-sdk/src/main/kotlin/com/lightningkite/services/embedding/bedrock/awssdk/AwsSdkCredentials.kt:70`

```
bedrock-sdk://<model-id>?region=<region>&dimensions=<n>&profile=<profile>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority = `<model-id>` | **yes** (`require`) | -- | |
| `region` | **yes unless env** | env `AWS_REGION` | throws `IllegalArgumentException` if neither (NO `us-east-1` fallback, unlike other bedrock schemes) |
| `dimensions` | no | model default | `toInt()`, non-numeric throws |
| `profile` | no | SDK `DefaultCredentialsProvider` | SDK `ProfileCredentialsProvider` |

Example: `bedrock-sdk://amazon.titan-embed-text-v2:0?region=us-east-1&profile=dev`.
Surprise: params parsed with a custom splitter (`substringBefore/After("=")`): a bare key with no `=` maps to itself (e.g. `?foo` -> `foo=foo`), harmless but different from other handlers. Guarded by `supports()`.

---

## Speech-to-Text

Type: `SpeechToTextService.Settings(val url: String = "test")` (`speech/src/commonMain/.../SpeechToTextService.kt:~128`). Default `"test"`. Note: scheme extraction means a bare `test` / `console` (no `://`) also resolves, since `substringBefore("://")` returns the whole string. KDoc in this file mentions `openai-stt://`, `elevenlabs-stt://` but the code registers plain `openai`/`elevenlabs` (see below; the KDoc is stale/misleading).

### `test` / `console`

Module: `speech`. `SpeechToTextService.kt:135` (`test` -> `TestSpeechToTextService`), `:136` (`console` -> `ConsoleSpeechToTextService`). No params; rest of URL ignored. Examples: `test`, `console://`.

### `openai://`

Module: `speech-openai`. `speech-openai/src/main/kotlin/com/lightningkite/services/speech/openai/OpenAISpeechToTextService.kt:217` (parse at `:243-283`)

```
openai://<apiKey>@<model>              (standard)
openai://?apiKey=<key>&model=<model>   (legacy)
```

| part | required? | default | meaning |
|---|---|---|---|
| userinfo `<apiKey>@` | yes in standard form | -- | `${VAR}` expanded; blank or still-`${...}` -> `IllegalArgumentException` |
| `<model>` after `@` | no | `whisper-1` | text up to `?` |
| `apiKey` (legacy) | one of this / env | env `OPENAI_API_KEY` | `${VAR}` expanded (unset left literal, NOT checked in legacy path) |
| `model` (legacy) | no | `whisper-1` | |

Examples:
```
openai://${OPENAI_API_KEY}@whisper-1
openai://?apiKey=sk-xxxx&model=whisper-1
```
Surprises: form chosen by `contains("@") && !startsWith("?")` over the whole post-scheme string. `openai://whisper-1` (no `@`, no `?`) takes the legacy path: model in the host is IGNORED (default used), key from env. An `@` anywhere (even in a query value) triggers the standard path. No `baseUrl`. Registered in the companion object of `OpenAISpeechToTextService`: the class must be loaded first.

### `elevenlabs://`

Module: `speech-elevenlabs`. `speech-elevenlabs/src/main/kotlin/com/lightningkite/services/speech/elevenlabs/ElevenLabsSpeechToTextService.kt:313` (parse `:340-380`)

```
elevenlabs://<apiKey>@<model>              (standard)
elevenlabs://?apiKey=<key>&model=<model>   (legacy)
```
Same rules as OpenAI STT above with: default model `scribe_v1`; env fallback `ELEVENLABS_API_KEY`. Examples: `elevenlabs://${ELEVENLABS_API_KEY}@scribe_v1`, `elevenlabs://?apiKey=xi-xxxx`. Surprise: scheme is `elevenlabs`, NOT `elevenlabs-stt` (KDoc/error messages in code say `elevenlabs-stt://`; the registration key is `elevenlabs`). Same TTS/STT scheme name is fine since they're different parser types.

### `vosk://` and `local://`

Module: `speech-local` (JVM). `speech-local/src/main/kotlin/com/lightningkite/services/speech/local/VoskSpeechToTextService.kt:301` (`vosk`), `:309` (`local`, identical alias)

```
vosk://?modelPath=<dir>&modelName=<name>&modelsDir=<dir>
```

| param | required? | default | meaning |
|---|---|---|---|
| `modelPath` | no | none | existing Vosk model directory; takes precedence |
| `modelName` | no | `vosk-model-small-en-us-0.15` | model auto-downloaded if not present |
| `modelsDir` | no | `./local/vosk-models` | download/cache directory |

Examples: `vosk://`, `local://?modelName=vosk-model-small-de-0.15`.
Surprises: auto-downloads a model (network + disk) on first use. No `${VAR}` expansion, no decoding. Authority ignored.

---

## Text-to-Speech

Type: `TextToSpeechService.Settings(val url: String = "test")` (`speech/src/commonMain/.../TextToSpeechService.kt:~125`). Default `"test"`; bare `test`/`console` (no `://`) resolve. KDoc mentions `openai-tts://` which is NOT what the code registers.

### `test` / `console`

Module: `speech`. `TextToSpeechService.kt:132` / `:133`. No params; rest ignored.

### `openai://`

Module: `speech-openai`. `speech-openai/src/main/kotlin/com/lightningkite/services/speech/openai/OpenAITextToSpeechService.kt:293` (parse `:313-353`)

Same format and rules as OpenAI STT: `openai://<apiKey>@<model>` or legacy `openai://?apiKey=<key>&model=<model>`; default model `tts-1` (also e.g. `tts-1-hd`); env fallback `OPENAI_API_KEY`. Examples: `openai://${OPENAI_API_KEY}@tts-1-hd`, `openai://?apiKey=sk-xxxx`. Same surprises (`openai://tts-1` ignores the model and needs env key).

### `elevenlabs://`

Module: `speech-elevenlabs`. `speech-elevenlabs/src/main/kotlin/com/lightningkite/services/speech/elevenlabs/ElevenLabsTextToSpeechService.kt:378` (parse `:395-430`)

Same format and rules as OpenAI STT: `elevenlabs://<apiKey>@<model>` or `elevenlabs://?apiKey=<key>&model=<model>`; default model `eleven_multilingual_v2`; env fallback `ELEVENLABS_API_KEY`. Example: `elevenlabs://${ELEVENLABS_API_KEY}@eleven_multilingual_v2`.

### `freetts://` and `local://`

Module: `speech-local` (JVM). `speech-local/src/main/kotlin/com/lightningkite/services/speech/local/FreeTtsTextToSpeechService.kt:199` (`freetts`), `:202` (`local`)

No params, rest of URL ignored. Examples: `freetts://`, `local://`. Both return `FreeTtsTextToSpeechService`.

---

## Voice Agent

Type: `VoiceAgentService.Settings(val url: String = "test")` (`voiceagent/src/commonMain/.../VoiceAgentService.kt:~129`). Default `"test"`; bare `test`/`console` resolve. Builder `Settings.openaiRealtime(model, apiKey)` emits `openai-realtime://<model>[?apiKey=...]`. `voiceagent-phonecall` and `voiceagent-test` contain no scheme registrations.

### `test` / `console`

Module: `voiceagent`. `VoiceAgentService.kt:147` / `:148`. No params; rest ignored.

### `openai-realtime://`

Module: `voiceagent-openai`. `voiceagent-openai/src/main/kotlin/com/lightningkite/services/voiceagent/openai/OpenAIVoiceAgentService.kt:105`

```
openai-realtime://[<model>]?apiKey=<key>
```

| part | required? | default | meaning |
|---|---|---|---|
| authority = `<model>` | no | `gpt-4o-realtime-preview` | text between `://` and `?` |
| `apiKey` | one of this / env | env `OPENAI_API_KEY` | `${VAR}` expanded (unset left literal). Throws `IllegalArgumentException` if neither |

Examples:
```
openai-realtime://gpt-4o-realtime-preview?apiKey=sk-xxxx
openai-realtime://?apiKey=${OPENAI_API_KEY}
```
Surprises: model IS in the URL here. No baseUrl. Registered in the companion of `OpenAIVoiceAgentService` (class must be loaded). No URL-decoding.

---


## Telemetry

`TelemetryBackend.Settings` is a `@Serializable data class` (`basis/src/commonMain/.../telemetry/TelemetryBackend.kt:~55-107`), implements `HasUrl`. Default `url = "noop"`. Fields beyond `url` (apply to the `otel-jvm` schemes `log`, `console`, `otlp-*`; `noop`, `logging*` ignore them; `dev`/`debounced-dev` use only `spanLimits`):

| field | type | default | meaning |
|---|---|---|---|
| `batching` | `BatchingRules?` | `BatchingRules()` | base batching; `null` makes the derived ones default to `null` (Simple processors, export immediately) |
| `metricReportBatching` | `BatchingRules?` | `batching` | only `frequency` is used, as metric export interval (null falls back to 5s) |
| `traceReportBatching` | `BatchingRules?` | `batching` | span batch processor; null = SimpleSpanProcessor |
| `logReportBatching` | `BatchingRules?` | `batching` with `maxQueueSize`x10, `maxSize`x10 (null if batching null) | log batch processor; null = SimpleLogRecordProcessor |
| `batchingLimits` | `BatchingRules` | `BatchingRules()` | declared; I found no reader in `otel-jvm` (appears unused) |
| `spanLimits` | `SpanLimitSettings` | see below | OTel `SpanLimits` |
| `logLimits` | `LogLimits` | `maxBodyLength=8192, maxStackTraceDepth=50` | truncation in `SafeLogRecordExporter` |
| `sampling` | `Sampling?` | `null` (always on) | `ratio: Double = 1.0`, `parentBased: Boolean = true`; remote parents never decide sampling (`followLocalParents`) |
| `maxSpansPerSecond` | `Int?` | `null` | rate limit span export |
| `maxLogsPerSecond` | `Int?` | `null` | rate limit log export |

`BatchingRules`: `frequency=5.minutes`, `maxQueueSize=2048`, `maxSize=512`, `exportTimeout=30.seconds`.
`SpanLimitSettings`: `maxAttributeValueLength=1024`, `maxNumberOfAttributes=128`, `maxNumberOfEvents=128`, `maxNumberOfLinks=128`, `maxNumberOfAttributesPerEvent=32`, `maxNumberOfAttributesPerLink=32`.

JSON form: `{"url":"otlp-grpc://collector:4317","sampling":{"ratio":0.1}}`.

`otel-jvm/.../OpenTelemetrySettings.kt` (`OpenTelemetrySettings`, own `HasUrlSettingParser`, default url `log`, extra `serviceName`) is `@Deprecated` (line 130) in favour of `TelemetryBackend.Settings`.

Basis `LoggingSettings.kt` (`basis/src/*/.../LoggingSettings.kt`) is NOT a URL setting. It is a `@Serializable` data class: `LoggingSettings(default: ContextSettings? = ContextSettings(null, true, Level.INFO, false), logger: Map<String, ContextSettings>? = null)`. `ContextSettings(filePattern: String? = "local/logs/logfile-%d{yyyy-MM-dd}.log", toConsole: Boolean, level: Level, additive: Boolean)` (Logback file patterns; `logger` is keyed by package name; I did not read the defaults of the last three fields).

### `noop://`
Module: `basis`. `basis/src/commonMain/.../telemetry/TelemetryBackend.kt:108`
```
noop
```
No parts. Everything discarded. Default setting.

### `logging://` and `logging-nocolor://`
Module: `basis` (JVM only). `basis/src/jvmMain/.../LoggingTelemetryBackend.kt:51-52`
```
logging
logging-nocolor
```
No parts; anything after `://` is ignored. `logging-nocolor` only turns off ANSI color. Prints span trees to stdout. Needs the `LoggingTelemetryBackend.register()` call.

### `log://`
Module: `otel-jvm`. `otel-jvm/.../OtelTelemetryBackend.kt:322`
```
log
```
No parts (suffix ignored). OTel SDK with `LoggingSpanExporter`, `LoggingMetricExporter`, `SystemOutLogRecordExporter`; honours all `Settings` fields. Service name = `context.projectName`.

### `console://`
Module: `otel-jvm`. `OtelTelemetryBackend.kt:335`
```
console
```
No parts. Same as `log` but with the module's own `Print*Exporter`s; honours all `Settings` fields.

### `otlp-grpc://`
Module: `otel-jvm`. `OtelTelemetryBackend.kt:348`
```
otlp-grpc://<host>:<port>
```
| part | required | default | meaning |
|---|---|---|---|
| everything after `://` | no | `localhost:4317` | endpoint; `http://` is always prepended (plaintext gRPC) |

Example: `otlp-grpc://localhost:4317`. Surprise: no TLS option, no auth headers, remainder not validated.

### `otlp-http://`
Module: `otel-jvm`. `OtelTelemetryBackend.kt:361`
```
otlp-http://<host>:<port>
```
| part | required | default | meaning |
|---|---|---|---|
| everything after `://` | no | `localhost:4318` | base; `http://` prepended; `/v1/traces`, `/v1/metrics`, `/v1/logs` appended |

Example: `otlp-http://otel-collector:4318`. Surprise: a path in the URL becomes a prefix of `/v1/...`; no headers/auth.

### `otlp-https://`
Module: `otel-jvm`. `OtelTelemetryBackend.kt:374`
```
otlp-https://<host>[:port]
```
| part | required | default | meaning |
|---|---|---|---|
| everything after `://` | effectively yes | none | base; `https://` prepended; `/v1/{traces,metrics,logs}` appended |

Example: `otlp-https://otlp.example.com`. Surprise: unlike the other two there is no default; bare `otlp-https` yields endpoint `https:///v1/traces` (not validated).

### `dev://`
Module: `otel-jvm`. `OtelTelemetryBackend.kt:387`
```
dev[://[<outputFile>][?color=<bool>&log_delay=<ms>&metric_frequency=<duration>]]
```
| part | required | default | meaning |
|---|---|---|---|
| path (before `?`) | no | none (stdout) | `File(path)` to write output to |
| `color` | no | on | only the literal `false` (case-insensitive) disables color |
| `log_delay` | no | `0` | ms to delay logs for span correlation; unparsable becomes 0 silently |
| `metric_frequency` | no | `60s` | Kotlin `Duration.parseOrNull` (e.g. `30s`, `PT30S`); unparsable becomes 60s silently |

Examples: `dev`, `dev://./local/telemetry.log?color=false&metric_frequency=30s`.
Surprises: always samples everything; ignores batching/sampling/rate-limit fields (uses `spanLimits` only). Query parser is naive (split on `&`/`=`, no URL decoding). The code has a `dev:<path>` fallback (no `//`), but `parse()` dispatches on the text before `://`, so `dev:foo` has no handler; only reachable by calling the factory directly. Service name defaults to `"dev"` when `projectName` is blank.

### `debounced-dev://`
Module: `otel-jvm`. `OtelTelemetryBackend.kt:410`
```
debounced-dev[://[<outputFile>][?color=..&debounce=<ms>&debounce_min=<n>&log_delay=<ms>&metric_frequency=<duration>]]
```
Same as `dev` plus:

| param | required | default | meaning |
|---|---|---|---|
| `debounce` | no | `null` | debounce window in ms (`toLongOrNull`; invalid = null) |
| `debounce_min` | no | `1` | minimum repeat count (`toIntOrNull`; invalid = 1) |

What a `null` window does is defined in `DebouncedDevSpanExporter`, which I did not read (ambiguous here). Example: `debounced-dev://?debounce=2000&debounce_min=3`.

### `sentry://`
Module: `telemetry-sentry`. `telemetry-sentry/.../SentryTelemetryBackend.kt:82`
```
sentry://<key>@<sentry-host>/<numericProjectId>/<wrapped telemetry URL>
```
| part | required | default | meaning |
|---|---|---|---|
| `<key>@<host>/<projectId>` | yes | | DSN; rebuilt as `https://<key>@<host>/<projectId>` (always https) |
| `<projectId>` | yes | | first all-digit path segment (must be at index > 0); everything after it is the wrapped URL |
| wrapped URL | yes (non-empty) | | any other registered TelemetryBackend URL, e.g. `otlp-grpc://localhost:4317` or `noop`; resolved with `settings.copy(url = wrapped)`, so all other Settings fields carry through |

Example: `sentry://abc123@o0.ingest.sentry.io/4500000/otlp-grpc://localhost:4317`.
Error: `IllegalArgumentException("Expected sentry://<key>@<host>/<projectId>/<wrapped telemetry URL>")`. Behaviour: delegates everything to the wrapped backend; additionally sends `reportError` and (max 10 per batch) client-ingested errors to Sentry. Sentry environment = `context.projectName`. Registers a JVM shutdown hook. Gotcha: the first all-digit segment wins, so a numeric host/key segment before the project id would mis-split.

## Geocoding

`Geocoding.Settings` is a `@JvmInline @Serializable value class Settings(val url: String = "test")` (`geocoding/src/commonMain/.../Geocoding.kt:172`), plain `UrlSettingParser`. Default `test`. The KDoc also lists `nominatim://` (`geocoding-nominatim`) but no such module or registration exists in the repo: stale doc.

### `test://`
Module: `geocoding`. `Geocoding.kt:175`
```
test
```
No parts (suffix ignored). `TestGeocoding`: empty programmable table.

### `local://`
Module: `geocoding-local`. `geocoding-local/.../LocalGeocoding.kt:348`
```
local
```
No parts (suffix ignored). Offline US lookup from bundled data. Helper `Geocoding.Settings.local()`.

### `stadia://`
Module: `geocoding-stadia`. `geocoding-stadia/.../StadiaGeocoding.kt:143`. Working-tree diff vs HEAD only removes `@Untested` from the class; the handler is unchanged.
```
stadia://<apiKey>[@<host>]
```
| part | required | default | meaning |
|---|---|---|---|
| `apiKey` (before first `@`) | yes | | sent as the `api_key` query param |
| `host` (after first `@`) | no | `api.stadiamaps.com` | requests go to `https://<host>/geocoding/v1/<path>` |

Examples: `stadia://sk_XXXX`, `stadia://sk_XXXX@stadia-proxy.example.com`.
Error: blank after `stadia://` throws `IllegalArgumentException("Invalid Stadia URL...")`. Surprises: the key travels in the query string; a key containing `@` splits wrongly; the error message shows `[apiKey]` as optional but blank is rejected.

### `google://`
Module: `geocoding-google`. `geocoding-google/.../GoogleGeocoding.kt:202`
```
google://<apiKey>
```
| part | required | default | meaning |
|---|---|---|---|
| `apiKey` (entire remainder) | yes | | Google Maps Platform key (Geocoding API and Places API (New)) |

Example: `google://AIzaXXXX`. Error: blank key throws `IllegalArgumentException("Invalid Google URL...")`. No query/host parsing: the whole remainder, including any `?` or `@`, is the key.

### `aws-location://`
Module: `geocoding-aws`. `geocoding-aws/.../AwsLocationGeocoding.kt:162`
```
aws-location://[<accessKey>:<secretKey>@]<region>[/][?intendedUse=Storage|SingleUse]
```
| part | required | default | meaning |
|---|---|---|---|
| `accessKey:secretKey@` | no | default AWS credential chain | static creds; both must be present |
| `region` | yes | | AWS region (`[^/?]+`) |
| `intendedUse` | no | `SingleUse` | `Storage` = results may be stored (`storesResults=true`); any other value throws |

Examples: `aws-location://us-east-1`, `aws-location://AKIAXXXX:SECRET@us-west-2?intendedUse=Storage`.
Errors: non-matching URL throws `IllegalArgumentException("Invalid Amazon Location URL...")`; unknown `intendedUse` throws. Uses `context[AwsConnections]` for the HTTP client. Surprise: whole-URL regex, so any other query param fails the match; secrets are not URL-decoded and cannot contain `@`.

### `azure-maps://`
Module: `geocoding-azure`. `geocoding-azure/.../AzureMapsGeocoding.kt:197`
```
azure-maps://<subscriptionKey>
azure-maps://<accountClientId>?auth=entra
```
| part | required | default | meaning |
|---|---|---|---|
| credential (before `?`) | yes | | subscription key, or the Azure Maps account client id when `auth=entra` |
| `auth` | no | subscription key auth | only accepted value is `entra` (`DefaultAzureCredential`, scope `https://atlas.microsoft.com/.default`) |

Examples: `azure-maps://XXXXsubkey`, `azure-maps://00000000-0000-0000-0000-000000000000?auth=entra`.
Errors: blank credential, or any query other than exactly `auth=entra`, throws `IllegalArgumentException`.

## Subscription payments

`SubscriptionService.Settings` is a `@JvmInline @Serializable value class Settings(val url: String = "console")` (`subscription-payments/src/commonMain/.../SubscriptionService.kt:~104`). Default `console`.

### `console://`
Module: `subscription-payments`. `SubscriptionService.kt:109`
```
console
```
No parts. Prints operations to the console (`ConsoleSubscriptionService`).

### `test://`
Module: `subscription-payments`. `SubscriptionService.kt:110`
```
test
```
No parts. In-memory (`TestSubscriptionService`).

### `stripe://`
Module: `subscription-payments-stripe`. `subscription-payments-stripe/.../StripeSubscriptionService.kt:526`
```
stripe://<apiKey>[@<webhookSecret>]
```
| part | required | default | meaning |
|---|---|---|---|
| `apiKey` (`[^@]+`) | yes | | `sk_test_...` / `sk_live_...` |
| `webhookSecret` (rest after first `@`) | no | env `STRIPE_WEBHOOK_SECRET` | `whsec_...` signing secret |

Examples: `stripe://sk_test_XXXX@whsec_XXXX`, `stripe://sk_test_XXXX` (with the env var set).
Errors: regex mismatch throws `IllegalArgumentException("Invalid Stripe URL...")`; no secret in URL and no env var throws `IllegalArgumentException("Missing STRIPE_WEBHOOK_SECRET environment variable")`. Surprise: reads `System.getenv` directly, not through `SettingContext`; a blank secret (`stripe://key@`) falls back to the env var too.



## Known issues found while writing this

These came from reading the code.  Unless a line says otherwise, none of them was reproduced.

- **FileScanner:** `FileScanner.Settings` defaults to `file://files`, but `file` isn't registered on `FileScanner`.  Using the default throws.
- **H2:** `sql-h2` appends extra JDBC params after `;`, but joins them with `&`.  H2 expects `;` between params.
- **MongoDB Atlas search:** `mongodb+srv` strips `atlasSearch=true` but leaves the following `&` behind.  The `mongodb://` handler cleans this up.
- **`mongodb-test`:** `version` must be the enum name, e.g. `V7_0`.  `mongodb-file` also accepts `7.0`.
- **`cassandra-test://`:** always throws `NotImplementedError`.
- **Redis:** only `redis` is registered, so `rediss://` (TLS) and sentinel URLs fail with "No handler".
- **SendGrid and Mailgun inbound:** `sendgrid://KEY@` and `mailgun://KEY@` have an empty host, so `java.net.URI.userInfo` is `null` and the handler throws "key required".  A scratch JDK check confirmed that `URI` behaviour, but the handlers weren't run.  The `tf.kt` files emit these broken forms.  The Mailgun inbound scheme is `mailgun`, not the `mailgun-inbound` its KDoc says.
- **SMTP:** a missing `fromEmail` throws a bare `NullPointerException` from `!!`.
- **IMAP:** the `?params` regex group never matches, because the greedy folder group consumes it.
- **Twilio phone calls:** the API-key separator is `-`, but the comment and the error message say `/`.
- **S3:** `bucket.s3.amazonaws.com` (no region) parses with region `s3`.
- **Speech:** the KDoc names `openai-stt://`, `elevenlabs-stt://` and `openai-tts://`, but the registered schemes are `openai` and `elevenlabs`.  `openai://whisper-1` (no `@` or `?`) takes a legacy path that ignores the model.
- **Bedrock region:** `bedrock-sdk` for embeddings throws without a region.  The LLM version falls back to `us-east-1`.
- **Geocoding:** the `Geocoding.Settings` KDoc mentions `nominatim://`, which doesn't exist.

Not verified: whether `dynamodb` and `dynamodb-pubsub` create their tables; and what `debounced-dev` does when `debounce` is unset.
