# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Service Abstractions is a Kotlin Multiplatform library that provides abstract interfaces for common backend infrastructure services (databases, caches, file systems, email, SMS, etc.) with multiple implementations. This allows applications to switch between different service providers (e.g., MongoDB vs PostgreSQL, Redis vs Memcached) via configuration without code changes.

**Key Dependencies:**
- kotlin-logging
- KotlinX Serialization
- KotlinX Coroutines
- KotlinX DateTime

## Build and Test Commands

### Building
```bash
# Build all modules
./gradlew build

# Build a specific module
./gradlew :database:build
./gradlew :cache:build
```

### Testing
```bash
# Run all tests
./gradlew allTests

# Run JVM tests only (fastest for local development)
./gradlew jvmTest

# Run tests for a specific module
./gradlew :database:jvmTest
./gradlew :cache:jvmTest

# Run specific test class
./gradlew :database-mongodb:jvmTest --tests "MongodbConditionTests"

# Run all verification tasks
./gradlew check
```

### Platform-Specific Tests
```bash
# JavaScript tests
./gradlew jsTest

# iOS tests
./gradlew iosSimulatorArm64Test

# macOS tests
./gradlew macosArm64Test
```

### Publishing (Internal)
```bash
# Publish to local Maven
./gradlew publishToMavenLocal
```

## Architecture Overview

### Settings System

The project uses a **Settings-based configuration pattern** where services are instantiated from serializable configuration objects:

```kotlin
@Serializable
data class ServerSettings(
    val database: Database.Settings = Database.Settings("mongodb://localhost"),
    val cache: Cache.Settings = Cache.Settings("redis://localhost:6379")
)

// Instantiate services
val context = SettingContext(...)
val db = settings.database("main-db", context)
val cache = settings.cache("app-cache", context)
```

**Key Components:**
- `Setting<T>`: Functional interface that creates service instances
- `SettingContext`: Context object passed to all services during instantiation (contains SerializersModule, OpenTelemetry, shared resources)
- `UrlSettingParser`: Registry pattern that maps URL schemes to service factories

### Service Abstraction Pattern

All infrastructure services implement the `Service` interface:

```kotlin
interface Service {
    val name: String
    val context: SettingContext
    suspend fun connect()
    suspend fun disconnect()  // Important for serverless (AWS Lambda, SnapStart)
    val healthCheckFrequency: Duration
    suspend fun healthCheck(): HealthStatus
}
```

### Module Structure

- **basis/**: Core interfaces (`Setting`, `Service`, `SettingContext`, `HealthStatus`)
- **should-be-standard-library/**: Utility functions that should be in Kotlin stdlib
- **data/**: Common data types and utilities
- **database-shared/**: Shared types for database abstraction (`Condition`, `Modification`, `SortPart`)

#### Database Modules
- **database/**: Abstract `Database` and `Table` interfaces, `InMemoryDatabase` implementation
- **database-processor/**: KSP code generator for type-safe database queries
- **database-mongodb/**: MongoDB implementation
- **database-postgres/**: PostgreSQL implementation (uses Exposed ORM)
- **database-jsonfile/**: JSON file-based database (for testing/development)
- **database-test/**: Shared test suite used by all database implementations

#### Cache Modules
- **cache/**: Abstract `Cache` interface, in-memory MapCache
- **cache-redis/**: Redis implementation
- **cache-memcached/**: Memcached implementation
- **cache-dynamodb/**: AWS DynamoDB implementation
- **cache-test/**: Shared test suite

#### Other Service Modules
- **files/**: File system abstraction (`PublicFileSystem`)
- **files-s3/**: S3 implementation
- **files-client/**: HTTP client for file systems
- **files-test/**: Shared test suite
- **email/**: Email service abstraction
- **email-javasmtp/**: SMTP implementation
- **sms/**: SMS service abstraction
- **sms-twilio/**: Twilio implementation
- **pubsub/**: Pub/sub messaging abstraction
- **notifications/**: Push notification abstraction
- **notifications-fcm/**: Firebase Cloud Messaging implementation

#### Observability
- **otel-jvm/**: OpenTelemetry integration for JVM

### Database Abstraction in Detail

The database abstraction provides **type-safe queries** via KSP code generation:

**Model Definition:**
```kotlin
@GenerateDataClassPaths
@Serializable
data class User(
    override val _id: UUID = UUID.random(),
    @Index(unique = true) val email: String,
    val age: Int,
    val friends: List<UUID>
) : HasId<UUID>
```

**Generated Code:**
The KSP processor generates `DataClassPath` objects for each field, enabling type-safe queries:

```kotlin
// Type-safe queries using generated paths
val adults = userTable.find(
    condition = User.path.age greaterThan 18
)

// Type-safe updates
userTable.updateOne(
    condition = User.path.email eq "john@example.com",
    modification = User.path.age assign 25
)

// Aggregations
val countByAge = userTable.groupAggregate(
    aggregate = Aggregate.Count,
    groupBy = User.path.age
)
```

### Testing Pattern

Service implementations use a **shared test suite pattern**:

1. Abstract test classes in `-test` modules (e.g., `database-test/`) define comprehensive test suites
2. Implementation modules extend these tests with their specific service instance:

```kotlin
// In database-mongodb module
class MongodbConditionTests : ConditionTests() {
    override val database: Database = mongoClient
}
```

This ensures all implementations satisfy the same contract.

## Development Workflow

### Working with Database Models

1. Define your model with `@GenerateDataClassPaths`
2. Run `./gradlew :database:kspCommonMainKotlinMetadata` to generate field paths
3. Use generated paths for type-safe queries

### Adding a New Service Implementation

1. Create a new module (e.g., `cache-newcache`)
2. Add dependency on the abstraction module (e.g., `:cache`)
3. Implement the service interface
4. Register URL schemes in the companion object init block
5. Add tests extending the shared test suite from `-test` module

### Testing with Real Services

Many tests require real service instances (MongoDB, Redis, etc.). URL schemes with `-test` suffix (e.g., `mongodb-test://`) often start ephemeral instances for testing.

## Important Patterns

### URL-Based Configuration

Services use URL strings for configuration. Common schemes:
- `ram` or `ram://` - In-memory implementation
- `mongodb://...` - MongoDB
- `postgresql://...` - PostgreSQL
- `redis://...` - Redis
- `file://path` - Local file system
- `s3://bucket/prefix` - AWS S3

### Serverless Awareness

Services support `disconnect()`/`connect()` for serverless environments (AWS Lambda, SnapStart) where long-lived connections aren't guaranteed.

### Serialization

All services use `context.internalSerializersModule` for consistent serialization. Custom types must be registered in the SerializersModule.

### Explicit API Mode

Most modules use `explicitApi()`, requiring public visibility modifiers and return types.

## Multiplatform Targets

- **JVM**: Full support (Java 8+)
- **Android**: API 21+ (uses desugaring for newer APIs)
- **JS**: Browser target
- **iOS**: iosX64, iosArm64, iosSimulatorArm64
- **macOS**: macosX64, macosArm64

Note: Not all service implementations support all platforms. Database implementations are typically JVM-only.
