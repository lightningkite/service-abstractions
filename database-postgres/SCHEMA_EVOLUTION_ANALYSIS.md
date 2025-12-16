# PostgreSQL Schema Evolution Analysis

## Summary

This document analyzes how the PostgreSQL driver handles schema changes when Kotlin data classes are modified between application runs.

## What Works ✅

### 1. Adding Nullable Fields
**Status: WORKS**

When you add a nullable field to an existing model, PostgreSQL automatically handles it:

```kotlin
// V1
data class User(
    val _id: Uuid,
    val name: String
)

// V2 - Add nullable field
data class User(
    val _id: Uuid,
    val name: String,
    val email: String? = null  // New nullable field
)
```

**Behavior:**
- `ALTER TABLE ADD COLUMN email TEXT` is executed
- Existing records read with `email = null`
- New records can populate the field
- No migration needed

**Why it works:** PostgreSQL allows adding nullable columns to tables with existing data.

### 2. Reading Data After Removing Fields
**Status: WORKS**

When you remove a field from your model, old data can still be read:

```kotlin
// V1
data class User(
    val _id: Uuid,
    val name: String,
    val deprecated: String
)

// V2 - Field removed
data class User(
    val _id: Uuid,
    val name: String
)
```

**Behavior:**
- The `deprecated` column remains in the database
- Reading existing records works fine (column ignored)
- **⚠️ LIMITATION:** Cannot insert new records if removed field was non-nullable

**Why it works:** The decoder only reads fields that exist in the current descriptor.

## What Doesn't Work ❌

### 1. Adding Non-Nullable Fields Without Database Default
**Status: FAILS**

When you add a non-nullable field to an existing model, PostgreSQL rejects the schema change:

```kotlin
// V1
data class User(
    val _id: Uuid,
    val name: String
)

// V2 - Add non-nullable field
data class User(
    val _id: Uuid,
    val name: String,
    val status: String = "active"  // Kotlin default doesn't help
)
```

**Error:**
```
ERROR: column "status" of relation "..." contains null values
SQL: ALTER TABLE ... ADD status TEXT NOT NULL
```

**Why it fails:**
- Exposed generates `ALTER TABLE ADD COLUMN status TEXT NOT NULL`
- PostgreSQL can't execute this because existing rows would have NULL
- The Kotlin default (`= "active"`) only applies when constructing new objects in code
- It is NOT used as a database default value

**Workarounds:**
1. Add as nullable first, backfill data, then make non-nullable (manual migration)
2. Use nullable fields with Kotlin defaults instead
3. Manually execute SQL with database default: `ALTER TABLE ... ADD COLUMN status TEXT NOT NULL DEFAULT 'active'`

### 2. Inserting Data After Removing Non-Nullable Fields
**Status: FAILS**

After removing a non-nullable field, you cannot insert new data:

```kotlin
// V1 - Field is non-nullable
data class User(
    val _id: Uuid,
    val name: String,
    val deprecated: String = "old"
)

// V2 - Field removed
data class User(
    val _id: Uuid,
    val name: String
)
```

**Error:**
```
ERROR: null value in column "deprecated" violates not-null constraint
Failing row contains (..., ..., null)
```

**Why it fails:**
- The `deprecated` column still exists in the database as NOT NULL
- When inserting new records, the encoder doesn't provide a value for it
- PostgreSQL rejects the insert

**Workarounds:**
1. Make the field nullable before removing it (two-step migration)
2. Manually execute: `ALTER TABLE ... ALTER COLUMN deprecated DROP NOT NULL`
3. Actually drop the column: `ALTER TABLE ... DROP COLUMN deprecated`

## Schema Evolution Patterns

### Pattern 1: Adding Optional Features (✅ Safe)

```kotlin
// V1
data class Product(val _id: Uuid, val name: String, val price: Double)

// V2 - Add optional metadata
data class Product(
    val _id: Uuid,
    val name: String,
    val price: Double,
    val tags: List<String> = emptyList(),      // OK - nullable with default
    val description: String? = null,            // OK - nullable
    val metadata: Map<String, String>? = null   // OK - nullable
)
```

### Pattern 2: Required Field Migration (❌ Requires Manual Steps)

```kotlin
// V1
data class User(val _id: Uuid, val name: String)

// Migration: Add as nullable first
data class UserV1_5(
    val _id: Uuid,
    val name: String,
    val email: String? = null  // Step 1: Add as nullable
)

// Backfill data in deployment script:
// UPDATE users SET email = generate_email(name) WHERE email IS NULL;

// V2: Make non-nullable (requires manual ALTER TABLE)
data class User(
    val _id: Uuid,
    val name: String,
    val email: String  // Step 2: Make non-nullable after backfill
)
```

### Pattern 3: Safe Field Removal (✅ Two-Step Process)

```kotlin
// V1
data class Order(
    val _id: Uuid,
    val total: Double,
    val oldStatus: String = "active"
)

// V1.5: Make nullable first
data class Order(
    val _id: Uuid,
    val total: Double,
    val oldStatus: String? = "active"  // Step 1: Make nullable
)
// Deploy and verify

// V2: Remove field
data class Order(
    val _id: Uuid,
    val total: Double
    // Step 2: Remove from code (column remains in DB)
)
```

## Implementation Details

### Schema Synchronization

The PostgreSQL driver uses Exposed's `statementsRequiredToActualizeScheme()` (PostgresCollection.kt:89-96):

```kotlin
private val prepare = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
    t {
        statementsRequiredToActualizeScheme(table).forEach {
            exec(it)
        }
    }
}
```

This:
- Compares the expected schema (from Kotlin descriptor) with actual database schema
- Generates `ALTER TABLE` statements to add missing columns
- **Does NOT** drop columns that no longer exist in the model
- **Does NOT** change column nullability
- **Does NOT** add database default values

### Encoding/Decoding

**Decoder** (DbLikeMapDecoder.kt:153-161):
```kotlin
override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
    while (currentIndex < size) {
        val name = descriptor.getTag(currentIndex++)
        if (keys.any {
            it.startsWith(name) && (it.length == name.length || it[name.length] == '_')
        }) return currentIndex - 1
    }
    return CompositeDecoder.DECODE_DONE
}
```

- Only decodes fields that exist in the descriptor
- Skips columns that exist in the database but not in the model
- Handles missing columns gracefully for nullable fields
- **⚠️** Cannot handle missing columns for non-nullable fields without defaults

**Encoder** (DbLikeMapEncoder.kt):
- Only writes fields that exist in the current descriptor
- Doesn't write values for removed fields
- **⚠️** PostgreSQL will reject if removed field is NOT NULL

## Recommendations

### For Application Developers

1. **Prefer nullable fields for new additions** - they're much easier to evolve
2. **Use Kotlin defaults for nullable fields** - provides good fallback behavior
3. **Plan migrations for non-nullable fields** - requires multiple deployment steps
4. **Don't remove non-nullable fields directly** - make them nullable first
5. **Test schema changes** - use the provided test suite as a reference

### For Library Developers

Potential improvements to the driver:

1. **Support database defaults**: Allow specifying default values that map to SQL DEFAULT clauses
2. **Migration hooks**: Provide callbacks for custom migration logic
3. **Schema validation mode**: Fail fast if schema mismatch detected
4. **Column dropping**: Optional automatic column removal (with safeguards)
5. **Better error messages**: Explain schema evolution issues more clearly

## Test Results

See `SchemaEvolutionTest.kt` for comprehensive test coverage:

- ✅ `testAddNullableField` - Adding nullable fields works
- ❌ `testAddNonNullableFieldWithDefault` - Fails (Kotlin default != DB default)
- ❌ `testRemoveField` - Reading works, inserting fails for non-nullable
- ❌ `testAddMultipleFields` - Fails for non-nullable additions
- ❌ `testFieldTypeChange` - Complex migration fails
- ❌ `testAddNestedField` - Fails for non-nullable nested objects

## Conclusion

The PostgreSQL driver handles **additive nullable changes** well but struggles with:
- Non-nullable field additions (no DB default support)
- Field removals when original field was non-nullable
- Type changes (requires manual migration)

For production use, treat schema evolution as a **multi-step deployment process** rather than automatic migration.
