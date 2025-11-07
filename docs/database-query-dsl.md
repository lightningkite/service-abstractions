# Database Query DSL

The `database-shared` module provides a type-safe, serializable query language for database operations. This DSL allows you to write queries that work across different database backends (MongoDB, PostgreSQL, in-memory, etc.) without learning database-specific syntax.

## Core Concepts

The query DSL consists of three main components:

1. **Condition** - Boolean queries for filtering records
2. **Modification** - Update operations for transforming records
3. **DataClassPath** - Type-safe field references

All components are:
- **Serializable** - Can be sent over network or stored
- **Translatable** - Database backends convert them to native queries
- **Evaluatable** - Can test against in-memory objects

## Generating Type-Safe Paths

To use the DSL with your models, annotate them with `@GenerateDataClassPaths`:

```kotlin
@GenerateDataClassPaths
@Serializable
data class User(
    override val _id: UUID = UUID.random(),
    @Index(unique = true) val email: String,
    val name: String,
    val age: Int,
    val tags: List<String>,
    val address: Address?
) : HasId<UUID>

@GenerateDataClassPaths
@Serializable
data class Address(
    val city: String,
    val state: String
)
```

The KSP processor (`database-processor`) generates path objects:

```kotlin
// Auto-generated in your build directory
val emailPath: DataClassPath<User, String> = User.path.email
val agePath: DataClassPath<User, Int> = User.path.age
val cityPath: DataClassPath<User, String> = User.path.address.notNull.city
```

## Conditions (Queries)

### Basic Comparisons

```kotlin
// Equality
User.path.email eq "alice@example.com"
User.path.status neq UserStatus.Banned

// Numeric comparisons
User.path.age gt 18
User.path.age gte 21
User.path.age lt 65
User.path.age lte 120

// Set membership
User.path.role inside listOf(Role.Admin, Role.Moderator)
User.path.status notInside setOf(UserStatus.Banned, UserStatus.Deleted)
```

### String Operations

```kotlin
// Substring search (case-insensitive by default)
User.path.name contains "alice"
User.path.email contains "@example.com"

// Case-sensitive search
User.path.name.contains("Alice", ignoreCase = false)

// Full-text search with fuzzy matching
User.path.bio.fullTextSearch(
    value = "software engineer",
    levenshteinDistance = 2,
    requireAllTermsPresent = true
)
```

### Boolean Logic

```kotlin
// AND
val adults = User.path.age gte 18 and (User.path.verified eq true)

// OR
val specialUsers = User.path.role eq Role.Admin or (User.path.tier eq Tier.VIP)

// NOT
val nonAdmins = !(User.path.role eq Role.Admin)

// Complex combinations
val activeVips = (User.path.status eq UserStatus.Active) and
                 (User.path.tier eq Tier.VIP or User.path.lifetimeSpend gte 10000.0)
```

### Null Handling

```kotlin
// Nullable field access
User.path.middleName.notNull eq "Marie"

// Null-safe conditions
User.path.address.notNull { address ->
    address.city eq "New York"
}
```

### Collection Operations

```kotlin
// List/Set elements
User.path.tags.elements eq "vip"  // Has "vip" tag

// All elements match condition
User.path.tags all { it eq "verified" }

// Any element matches condition
User.path.friends any { it eq friendId }

// Size checks (deprecated - use isEmpty/notEmpty when available)
@Deprecated
User.path.tags sizesEquals 0
```

### Geospatial Queries

```kotlin
import com.lightningkite.Length.Companion.miles

// Distance-based query
User.path.location.distanceBetween(
    value = GeoCoordinate(40.7128, -74.0060),  // NYC
    greaterThan = 0.0.miles,
    lessThan = 10.0.miles
)
```

### Bitwise Operations

```kotlin
// Useful for flags and permissions
User.path.permissions allSet PERMISSION_READ
User.path.permissions anySet (PERMISSION_WRITE or PERMISSION_DELETE)
User.path.flags allClear FLAG_BANNED
```

## Modifications (Updates)

### Basic Assignments

```kotlin
modification<User> { it ->
    it.name assign "New Name"
    it.lastLoginAt assign Clock.System.now()
}
```

### Numeric Operations

```kotlin
modification<Counter> { it ->
    it.value += 1           // Increment
    it.multiplier *= 2      // Multiply
    it.score coerceAtMost 100    // Cap at maximum
    it.score coerceAtLeast 0     // Floor at minimum
}
```

### String Operations

```kotlin
modification<User> { it ->
    it.bio += " (verified)"  // Append to string
}
```

### Collection Modifications

```kotlin
modification<User> { it ->
    // Add elements
    it.tags += "premium"
    it.tags addAll listOf("verified", "active")

    // Remove elements by condition
    it.tags.removeAll { tag -> tag contains "temp" }

    // Remove specific elements
    it.tags.removeAll(listOf("spam", "test"))

    // Remove first/last
    it.recentViews.dropFirst()
    it.recentViews.dropLast()

    // Transform each element
    it.scores forEach { score ->
        score.value *= 1.1  // Apply 10% bonus
    }

    // Conditional element transformation
    it.items.forEachIf(
        condition = { item -> item.status eq ItemStatus.Pending },
        modification = { item ->
            item.status assign ItemStatus.Active
        }
    )
}
```

### Map Modifications

```kotlin
modification<Config> { it ->
    // Merge maps
    it.settings += mapOf("theme" to "dark", "language" to "en")

    // Remove keys
    it.settings.removeKeys(setOf("deprecated_setting"))

    // Modify specific keys
    it.settings.modifyByKey(mapOf(
        "timeout" to { timeout -> timeout.value *= 2 }
    ))
}
```

### Chained Modifications

```kotlin
modification<User> { it ->
    it.loginCount += 1
    it.lastLoginAt assign now()
    it.tags += "active"
}
```

## Complete Query Examples

### Finding Records

```kotlin
// Find all active adult users
val adults = userTable.find(
    condition = (User.path.age gte 18) and (User.path.status eq UserStatus.Active)
)

// Find with sorting and pagination
val topUsers = userTable.find(
    condition = User.path.score gt 1000,
    orderBy = listOf(SortPart(User.path.score, ascending = false)),
    limit = 10
)
```

### Updating Records

```kotlin
// Update single record
userTable.updateOne(
    condition = User.path._id eq userId,
    modification = modification<User> { it ->
        it.age += 1
        it.lastBirthdayAt assign Clock.System.now()
    }
)

// Update multiple records
userTable.updateMany(
    condition = User.path.status eq UserStatus.Pending,
    modification = modification<User> { it ->
        it.status assign UserStatus.Active
        it.activatedAt assign Clock.System.now()
    }
)
```

### Complex Queries

```kotlin
// Find users in specific cities with high scores
val query = Query<User>(
    orderBy = listOf(
        SortPart(User.path.score, ascending = false),
        SortPart(User.path.name, ascending = true, ignoreCase = true)
    ),
    skip = 0,
    limit = 50
) { path ->
    (path.address.notNull.city inside listOf("New York", "San Francisco")) and
    (path.score gte 500) and
    (path.tags any { it eq "verified" })
}
```

## Backend Support Matrix

Not all database backends support all operations:

| Condition Type | MongoDB | PostgreSQL | In-Memory |
|---------------|---------|------------|-----------|
| Basic comparisons | ✅ | ✅ | ✅ |
| String contains | ✅ | ✅ | ✅ |
| Full-text search | ✅ | ✅ | ~approximation |
| Regex matches | ✅ | ✅ | ✅ |
| Geo queries | ✅ | ✅ (PostGIS) | ✅ |
| Bitwise ops | ✅ | ✅ | ✅ |
| Collection ops | ✅ | ✅ (array ops) | ✅ |

Check your database backend's documentation for specific limitations.

## Best Practices

### Performance Considerations

1. **Use indexes**: Annotate frequently queried fields with `@Index`
   ```kotlin
   @Index val email: String
   @Index val createdAt: Instant
   ```

2. **Limit result sets**: Always use pagination to avoid loading entire tables
   ```kotlin
   val query = Query<User>(limit = 100)  // Default is 100
   ```

3. **Avoid regex when possible**: String operations are faster than regex
   ```kotlin
   // Prefer this
   User.path.email contains "@example.com"

   // Over this
   User.path.email matches ".*@example\\.com"
   ```

4. **Use projection for large models**: Only load fields you need (if supported by backend)

### Type Safety Tips

1. **Let KSP generate paths**: Don't manually create DataClassPath objects
2. **Use DSL builders**: Prefer `modification<T> { }` over constructing Modification objects
3. **Leverage IDE autocomplete**: Type `User.path.` to explore available fields

### Error Handling

```kotlin
try {
    val users = userTable.find(condition = complexCondition)
} catch (e: UnsupportedOperationException) {
    // Some backends don't support certain conditions
    // Fall back to alternative query or in-memory filtering
}
```

## Advanced Topics

### Conditional Queries

```kotlin
// Build queries dynamically
fun buildUserQuery(
    minAge: Int?,
    tags: List<String>?,
    verified: Boolean?
): Condition<User> = Condition.andNotNull(
    minAge?.let { User.path.age gte it },
    tags?.let { User.path.tags any { tag -> tag inside tags } },
    verified?.let { User.path.verified eq it }
)
```

### Custom Conditions

For complex logic not expressible in the DSL:

```kotlin
// Define custom condition
val customCondition = object : Condition<User>() {
    override fun invoke(on: User): Boolean {
        // Custom logic for in-memory evaluation
        return on.name.length > 5 && on.age > 18
    }
}
```

### Serialization

Conditions and Modifications are fully serializable:

```kotlin
val condition: Condition<User> = User.path.age gte 18
val json = Json.encodeToString(condition)
val decoded = Json.decodeFromString<Condition<User>>(json)
```

This enables:
- Sending queries from client to server
- Storing query templates
- Building query builders in admin UIs
