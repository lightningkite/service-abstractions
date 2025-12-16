# Default Values Implementation Plan

## Overview

This document describes the implementation of default value support in the service-abstractions database system, specifically to fix the PostgreSQL schema evolution limitation where adding non-nullable fields fails.

## Problem Statement

Currently, when you add a non-nullable field with a Kotlin default to an existing data class:

```kotlin
// V1
data class User(val _id: Uuid, val name: String)

// V2 - Adding non-nullable field
data class User(
    val _id: Uuid,
    val name: String,
    val status: String = "active"  // ❌ FAILS
)
```

The PostgreSQL driver fails with:
```
ERROR: column "status" contains null values
SQL: ALTER TABLE ... ADD status TEXT NOT NULL
```

**Why?** Because:
- Kotlin defaults only work when constructing objects in code
- Exposed's `statementsRequiredToActualizeScheme()` generates `ALTER TABLE ADD COLUMN status TEXT NOT NULL`
- PostgreSQL can't execute this on tables with existing data (rows would have NULL)

## Solution Architecture

### Phase 1: Default Value Registry (✅ IMPLEMENTED)

Created a compile-time system to capture and store default values:

**1. Registry (SerializablePropertyDefaults.kt)**
- Global map storing defaults by serializer and property index
- Populated at class initialization time by KSP-generated code
- No runtime registration function needed

**2. Updated SerializableProperty.Generated**
- Now looks up defaults from the registry
- Populates `default: B?` and `defaultCode: String?` properties
- Works transparently with existing code

**3. Updated KSP Processor**
- Extracts default values from Kotlin source code (already did this)
- Generates `registerDefaults()` calls in ModelFields files
- Emits both the actual value (for simple types) and the code string

### Phase 2: PostgreSQL Integration (⚠️ TODO)

Need to update the PostgreSQL driver to use defaults when creating columns:

**Location:** `database-postgres/src/main/kotlin/com/lightningkite/services/database/postgres/SerialDescriptorTable.kt`

**Changes needed:**

1. **Extract defaults from SerializableProperty:**
```kotlin
internal fun SerialDescriptor.columnType(
    serializersModule: SerializersModule,
    serializer: KSerializer<*>? = null  // New parameter
): List<ColumnTypeInfo> {
    // Get serializable properties if available
    val properties = serializer?.serializableProperties

    // When creating ColumnTypeInfo, include default if available
    ColumnTypeInfo(
        key = ...,
        type = ...,
        descriptorPath = ...,
        default = properties?.get(index)?.default  // NEW
    )
}
```

2. **Update ColumnTypeInfo data class:**
```kotlin
internal data class ColumnTypeInfo(
    val key: List<String>,
    val type: ColumnType<*>,
    val descriptorPath: List<Int>,
    val default: Any? = null  // NEW
)
```

3. **Apply defaults when creating columns:**
```kotlin
internal class SerialDescriptorTable(...) : Table(...) {
    init {
        descriptor.columnType(serializersModule, serializer).forEach {
            val col = registerColumn<Any?>(it.key.joinToString("__"), it.type as ColumnType<Any>)

            // Apply database default if available
            if (it.default != null) {
                col.defaultValueFun = { it.default }
            }
        }
    }
}
```

**Challenge:** Exposed's `Column.defaultValueFun` is for Kotlin defaults, not SQL DEFAULT clauses. We need to use Exposed's actual SQL default support.

Actually, Exposed columns have:
- `clientDefault: (() -> T)?` - Kotlin-side default
- SQL defaults are set via `Column.default(value)` in DSL

We need to investigate Exposed's column creation API to see how to set SQL defaults properly.

### Phase 3: SQL Default Generation (⚠️ TODO)

Need to convert Kotlin default values to SQL DEFAULT clauses:

**Challenges:**

1. **Type Mapping:**
   - `Int` defaults → `DEFAULT 42`
   - `String` defaults → `DEFAULT 'active'`
   - `Boolean` defaults → `DEFAULT true`
   - `List<T>` defaults → `DEFAULT ARRAY[]` (PostgreSQL arrays)
   - Complex objects → Difficult, may need to serialize to JSON

2. **Complex Defaults:**
   - `Uuid.random()` → Can't use as SQL default (not deterministic)
   - `emptyList()` → `DEFAULT ARRAY[]` or `DEFAULT '{}'`
   - `MyClass()` → Requires JSONB serialization

**Proposed Approach:**

```kotlin
fun Any?.toPostgresDefaultClause(columnType: IColumnType<*>): String? {
    return when (this) {
        null -> "NULL"
        is Number -> this.toString()
        is String -> "'${this.replace("'", "''")}'"
        is Boolean -> this.toString()
        is Enum<*> -> "'${this.name}'"
        // For complex types, may need to return null and keep nullable
        else -> null
    }
}
```

## Migration Strategy

### For Users

**Safe Migration Pattern:**

```kotlin
// Step 1: Add as nullable
data class User(
    val _id: Uuid,
    val name: String,
    val status: String? = "active"  // Nullable with default
)
// Deploy, verify

// Step 2: Backfill if needed
// UPDATE users SET status = 'active' WHERE status IS NULL;

// Step 3: Make non-nullable (manual ALTER TABLE)
// ALTER TABLE users ALTER COLUMN status SET NOT NULL;
// ALTER TABLE users ALTER COLUMN status SET DEFAULT 'active';

// Step 4: Update model
data class User(
    val _id: Uuid,
    val name: String,
    val status: String = "active"  // Now works!
)
```

**With Full Implementation:**

Once this is complete, adding non-nullable fields will work automatically for:
- Simple types (Int, String, Boolean, numbers)
- Enums
- Nullable types
- Maybe arrays (depending on implementation)

Complex defaults (objects, UUIDs, function calls) will still require the nullable approach.

## Testing Plan

### Unit Tests Needed

1. **Default Registry Tests:**
   - Verify defaults are registered correctly
   - Test retrieval by serializer and index
   - Test with generic types
   - Test with nested classes

2. **PostgreSQL Integration Tests:**
   - Add nullable field with simple default (should work)
   - Add non-nullable Int with default (should work after implementation)
   - Add non-nullable String with default (should work after implementation)
   - Add non-nullable complex type (should fall back to nullable)
   - Verify existing data gets default values

3. **Schema Evolution Tests:**
   - Update existing SchemaEvolutionTest.kt
   - Convert failing tests to passing ones
   - Add regression tests for edge cases

## Current Status

✅ **Completed:**
- Default value registry system
- KSP code generation
- SerializableProperty integration

⚠️ **In Progress:**
- PostgreSQL driver integration

❌ **TODO:**
- SQL DEFAULT clause generation
- Complex type handling
- Full test coverage
- Documentation updates

## Next Steps

1. Investigate Exposed's column default API
2. Implement ColumnTypeInfo.default
3. Add SQL default clause generation
4. Update SerialDescriptorTable to use defaults
5. Test with simple types first
6. Expand to complex types
7. Update schema evolution tests
8. Update user documentation

## Notes

- For serverless/Lambda, defaults might not be needed (short-lived connections)
- Consider making default SQL generation optional via config
- May need different strategies for different databases (MongoDB, etc.)
- Edge case: What if user changes default value? Need migration strategy.
