# Database Module - User Guide

**Module:** `database`
**Package:** `com.lightningkite.services.database`
**Purpose:** Unified database abstraction for querying and modifying data across different database backends

---

## Overview

The Database module provides a type-safe, backend-agnostic API for working with databases. Applications can switch between MongoDB, PostgreSQL, in-memory storage, and other backends via configuration without changing code.

### Key Features

- **Type-safe queries** via KSP-generated DataClassPath accessors
- **Multiple backends** - MongoDB, PostgreSQL, in-memory, JSON files
- **Unified API** - Same code works across all database implementations
- **Flow-based results** - Memory-efficient streaming for large result sets
- **Health monitoring** - Built-in health checks for all database connections
- **Serverless support** - Explicit connect/disconnect for AWS Lambda, SnapStart

---

## Quick Start

### 1. Define Your Model

```kotlin
@GenerateDataClassPaths
@Serializable
data class User(
    override val _id: UUID = UUID.random(),
    @Index(unique = true) val email: String,
    val name: String,
    val age: Int,
    val active: Boolean = true,
    val createdAt: Instant = Clock.System.now()
) : HasId<UUID>
```

**Key annotations:**
- `@GenerateDataClassPaths` - Generates type-safe field accessors for queries
- `@Serializable` - Required for serialization/deserialization
- `@Index` - Creates database indexes for performance
- `HasId<T>` - Models must have an `_id` field

### 2. Configure Database

```kotlin
@Serializable
data class ServerSettings(
    val database: Database.Settings = Database.Settings("mongodb://localhost:27017/myapp")
)

val context = SettingContext(...)
val db: Database = settings.database("main-db", context)
```

**Supported URL schemes:**
- `ram://` - In-memory (no persistence)
- `mongodb://host:port/dbname` - MongoDB
- `postgresql://host:port/dbname` - PostgreSQL
- `file:///path/to/data.json` - JSON file storage (dev/testing)
- `delay://100-500ms/mongodb://...` - Add artificial latency (testing)

### 3. Get a Table

```kotlin
val userTable: Table<User> = db.table<User>()
// or with custom name:
val userTable: Table<User> = db.table<User>("users")
```

### 4. Perform Operations

```kotlin
// Insert
val newUser = User(email = "alice@example.com", name = "Alice", age = 30)
userTable.insert(listOf(newUser))

// Query
val adults = userTable.find(
    condition = User.path.age greaterThanOrEqualTo 18,
    orderBy = listOf(SortPart(User.path.name, ascending = true))
).toList()

// Update
userTable.updateOne(
    condition = User.path.email eq "alice@example.com",
    modification = modification {
        it.age += 1
        it.active assign true
    }
)

// Delete
userTable.deleteMany(condition = User.path.active eq false)
```

---

## Querying with Conditions

The query DSL uses generated `DataClassPath` accessors for type-safe queries. See [database-query-dsl.md](./database-query-dsl.md) for complete reference.

### Common Query Patterns

**Equality:**
```kotlin
userTable.find(condition = User.path.email eq "alice@example.com")
```

**Comparison:**
```kotlin
userTable.find(condition = User.path.age greaterThan 18)
userTable.find(condition = User.path.age lessThanOrEqualTo 65)
```

**Logical operators:**
```kotlin
userTable.find(
    condition = (User.path.age greaterThan 18) and (User.path.active eq true)
)
userTable.find(
    condition = (User.path.role eq "admin") or (User.path.role eq "moderator")
)
```

**String operations:**
```kotlin
userTable.find(condition = User.path.name.startsWith("A"))
userTable.find(condition = User.path.email.contains("@gmail.com"))
userTable.find(condition = User.path.name.matches(Regex("[A-Z].*")))
```

**Collection operations:**
```kotlin
userTable.find(condition = User.path.tags.containsElement("premium"))
userTable.find(condition = User.path.tags.all())  // Has elements
```

**Nested fields:**
```kotlin
userTable.find(condition = User.path.address.city eq "San Francisco")
```

---

## Modifying Data

### Update Operations

```kotlin
// Increment/decrement
userTable.updateOne(
    condition = User.path._id eq userId,
    modification = modification {
        it.age += 1
        it.loginCount += 1
    }
)

// Set values
userTable.updateOne(
    condition = User.path._id eq userId,
    modification = modification {
        it.active assign false
        it.lastLoginAt assign Clock.System.now()
    }
)

// Multiply/divide
userTable.updateOne(
    condition = User.path._id eq userId,
    modification = modification {
        it.score *= 1.1
    }
)

// List operations
userTable.updateOne(
    condition = User.path._id eq userId,
    modification = modification {
        it.tags.addAll(listOf("new-tag"))
        it.tags.removeAll { tag -> tag == "old-tag" }
    }
)
```

### Replace vs Update

**Replace** - Overwrites entire document:
```kotlin
val updatedUser = user.copy(name = "New Name")
userTable.replaceOne(
    condition = User.path._id eq userId,
    model = updatedUser
)
```

**Update** - Modifies specific fields:
```kotlin
userTable.updateOne(
    condition = User.path._id eq userId,
    modification = modification { it.name assign "New Name" }
)
```

**When to use each:**
- Use **update** when changing a few fields (more efficient)
- Use **replace** when you have the full object already modified

### Upsert Operations

Insert if doesn't exist, otherwise update:

```kotlin
userTable.upsertOne(
    condition = User.path.email eq "alice@example.com",
    modification = modification { it.lastSeen assign Clock.System.now() },
    model = User(email = "alice@example.com", name = "Alice", age = 30)
)
```

---

## Aggregations

### Count

```kotlin
val adultCount = userTable.count(condition = User.path.age greaterThanOrEqualTo 18)
```

### Group Count

```kotlin
val countsByAge: Map<Int, Int> = userTable.groupCount(
    condition = Condition.Always,
    groupBy = User.path.age
)
```

### Aggregate Functions

```kotlin
// Average age
val avgAge: Double? = userTable.aggregate(
    aggregate = Aggregate.Average,
    property = User.path.age
)

// Sum of scores
val totalScore: Double? = userTable.aggregate(
    aggregate = Aggregate.Sum,
    property = User.path.score
)

// Min/max
val minAge: Double? = userTable.aggregate(Aggregate.Min, User.path.age)
val maxAge: Double? = userTable.aggregate(Aggregate.Max, User.path.age)
```

### Group Aggregates

```kotlin
val avgScoreByCity: Map<String, Double?> = userTable.groupAggregate(
    aggregate = Aggregate.Average,
    groupBy = User.path.address.city,
    property = User.path.score
)
```

---

## Pagination

### Skip/Limit (Simple)

```kotlin
val page1 = userTable.find(
    condition = Condition.Always,
    orderBy = listOf(SortPart(User.path.createdAt, ascending = false)),
    skip = 0,
    limit = 20
).toList()

val page2 = userTable.find(
    condition = Condition.Always,
    orderBy = listOf(SortPart(User.path.createdAt, ascending = false)),
    skip = 20,
    limit = 20
).toList()
```

**Warning:** Skip/limit is inefficient for large offsets. Use cursor-based pagination for better performance.

### Cursor-Based (Efficient)

```kotlin
// First page
val page1 = userTable.find(
    condition = Condition.Always,
    orderBy = listOf(SortPart(User.path.createdAt, ascending = false)),
    limit = 20
).toList()

// Next page (using last item as cursor)
val lastItem = page1.last()
val page2 = userTable.find(
    condition = User.path.createdAt lessThan lastItem.createdAt,
    orderBy = listOf(SortPart(User.path.createdAt, ascending = false)),
    limit = 20
).toList()
```

---

## Performance Optimization

### Indexes

Add `@Index` to frequently queried fields:

```kotlin
@GenerateDataClassPaths
@Serializable
data class User(
    override val _id: UUID = UUID.random(),
    @Index(unique = true) val email: String,  // Unique index
    @Index val age: Int,                       // Non-unique index
    @Index val createdAt: Instant,             // For sorting
    val name: String
) : HasId<UUID>
```

### Query Limits

Always use limits on unbounded queries:

```kotlin
// BAD - Could return millions of records
val allUsers = userTable.find(condition = Condition.Always).toList()

// GOOD - Limit results
val recentUsers = userTable.find(
    condition = Condition.Always,
    orderBy = listOf(SortPart(User.path.createdAt, ascending = false)),
    limit = 100
).toList()
```

### Streaming Results

Use Flow instead of `.toList()` for large result sets:

```kotlin
userTable.find(condition = Condition.Always).collect { user ->
    processUser(user)  // Handles one at a time, memory-efficient
}
```

### Partial Queries

Query only the fields you need (supported by some backends):

```kotlin
val userNames = userTable.findPartial(
    fields = setOf(User.path.name, User.path.email),
    condition = User.path.active eq true
).toList()
```

---

## Backend-Specific Considerations

### MongoDB

- **Atlas Search**: Full-text search requires MongoDB Atlas
- **Transactions**: Supported via replica sets
- **Field names**: Avoid dots in field names (MongoDB uses dots for nesting)
- **Case sensitivity**: String comparisons are case-sensitive by default

### PostgreSQL

- **JSONB storage**: All models stored as JSONB documents
- **Schema creation**: Tables auto-created on first use
- **Transactions**: Full ACID transaction support
- **Connection pooling**: Use HikariCP for production

### In-Memory

- **No persistence**: Data lost on restart
- **No indexes**: All queries are full table scans
- **Thread safety**: Thread-safe via ConcurrentHashMap
- **Testing**: Great for unit tests

---

## Health Checks

All database implementations support health checks:

```kotlin
val status: HealthStatus = db.healthCheck()
when (status.level) {
    HealthStatus.Level.OK -> println("Database is healthy")
    HealthStatus.Level.WARNING -> println("Database degraded: ${status.additionalMessage}")
    HealthStatus.Level.ERROR -> println("Database down: ${status.additionalMessage}")
}
```

Health checks perform a test insert/read/delete to verify connectivity.

---

## Common Patterns

### Get by ID

```kotlin
suspend fun Table<User>.getById(id: UUID): User? {
    return find(condition = User.path._id eq id).firstOrNull()
}
```

### Upsert by ID (convenience)

```kotlin
suspend fun Table<User>.upsertOneById(id: String, model: User): EntryChange<User> {
    return upsertOne(
        condition = User.path._id eq id,
        modification = Modification.Chain(),
        model = model
    )
}
```

### Bulk Insert with Error Handling

```kotlin
try {
    userTable.insert(newUsers)
} catch (e: UniqueViolationException) {
    logger.error("Duplicate email detected")
    // Handle duplicate key error
}
```

### Soft Delete Pattern

```kotlin
// Mark as deleted instead of removing
userTable.updateOne(
    condition = User.path._id eq userId,
    modification = modification { it.deletedAt assign Clock.System.now() }
)

// Query only non-deleted
userTable.find(condition = User.path.deletedAt eq null)
```

---

## Migration and Schema Management

### Schema Evolution

The database module doesn't have built-in migrations. For production use:

1. **Add optional fields** with default values:
```kotlin
@Serializable
data class User(
    val email: String,
    val name: String,
    val phoneNumber: String? = null  // New optional field
)
```

2. **Write migration scripts** for required changes:
```kotlin
suspend fun migrateAddPhoneNumber(db: Database) {
    val users = db.table<User>()
    users.updateMany(
        condition = User.path.phoneNumber eq null,
        modification = modification { it.phoneNumber assign "" }
    )
}
```

3. **Use database versioning** in your settings:
```kotlin
@Serializable
data class ServerSettings(
    val database: Database.Settings,
    val databaseVersion: Int = 1
)
```

---

## Testing

### Use In-Memory Database for Tests

```kotlin
@Test
fun testUserCreation() = runTest {
    val db = Database.Settings("ram://")("test-db", testContext)
    val users = db.table<User>()

    val user = User(email = "test@example.com", name = "Test", age = 25)
    users.insert(listOf(user))

    val found = users.find(condition = User.path.email eq "test@example.com").first()
    assertEquals(user.email, found.email)
}
```

### Add Artificial Latency

Test slow database scenarios:

```kotlin
val db = Database.Settings("delay://100-500ms/ram://")("test-db", context)
```

---

## Troubleshooting

### "No serializer found for class X"

**Cause:** Class not registered in SerializersModule
**Fix:** Add to context.internalSerializersModule:

```kotlin
val context = SettingContext(
    internalSerializersModule = SerializersModule {
        contextual(User::class, User.serializer())
    }
)
```

### "Table not found" (PostgreSQL)

**Cause:** Table not created yet
**Fix:** Tables are auto-created on first use. Ensure you call an operation.

### Query timeout after 15 seconds

**Cause:** Query takes too long
**Fix:** Add indexes or increase maxQueryMs:

```kotlin
userTable.find(
    condition = ...,
    maxQueryMs = 60_000  // 60 seconds
)
```

### Connection pool exhausted

**Cause:** Not enough connections for concurrent requests
**Fix:** Increase pool size in connection URL or use HikariCP settings

---

## See Also

- [database-query-dsl.md](./database-query-dsl.md) - Complete Condition/Modification reference
- [cache-module.md](./cache-module.md) - Caching to reduce database load
- [CODE_REVIEW_PRIORITIES.md](./CODE_REVIEW_PRIORITIES.md) - Known issues and enhancements
