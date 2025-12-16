# Default Values Solution - Corrected Approach

## The Problem You Identified

You correctly pointed out that my initial solution with a global registry had a **critical flaw**:

```kotlin
// My initial (broken) approach
private val User__defaults = SerializablePropertyDefaults.registerDefaults(...)
```

This would never run because:
- Top-level properties in Kotlin are initialized lazily on first access
- This private property is never accessed
- So defaults would never be registered
- We'd be back to needing `prepareModels()` to force initialization

## The Solution: Embed Defaults in Generated Properties

Instead of a global registry, we **embed defaults directly in the property objects** via anonymous classes:

### Generated Code (Non-Generic Types)

For a model with defaults:

```kotlin
@GenerateDataClassPaths
@Serializable
data class User(
    val _id: Uuid = Uuid.random(),
    val name: String,
    val age: Int = 18,
    val status: String = "active"
)
```

The KSP processor now generates:

```kotlin
// Property WITHOUT default - uses base class
public val User__id: SerializableProperty<User, Uuid> = SerializableProperty.Generated(
    User.serializer() as GeneratedSerializer<User>,
    0
)

// Property WITHOUT default
public val User_name: SerializableProperty<User, String> = SerializableProperty.Generated(
    User.serializer() as GeneratedSerializer<User>,
    1
)

// Property WITH default - anonymous subclass with override
public val User_age: SerializableProperty<User, Int> = object : SerializableProperty.Generated<User, Int>(
    User.serializer() as GeneratedSerializer<User>,
    2
) {
    override val default: Int? = 18
    override val defaultCode: String = "18"
}

// Property WITH default
public val User_status: SerializableProperty<User, String> = object : SerializableProperty.Generated<User, String>(
    User.serializer() as GeneratedSerializer<User>,
    3
) {
    override val default: String? = "active"
    override val defaultCode: String = "\"active\""
}

// Path accessors
public val <ROOT> DataClassPath<ROOT, User>._id: DataClassPath<ROOT, Uuid> get() = this[User__id]
public val <ROOT> DataClassPath<ROOT, User>.name: DataClassPath<ROOT, String> get() = this[User_name]
public val <ROOT> DataClassPath<ROOT, User>.age: DataClassPath<ROOT, Int> get() = this[User_age]
public val <ROOT> DataClassPath<ROOT, User>.status: DataClassPath<ROOT, String> get() = this[User_status]
```

## Why This Works

### No Initialization Order Issues

The properties are **top-level val declarations**. They're initialized when first accessed, which happens naturally when:

1. Someone uses the property: `User_age`
2. Someone uses the path: `User.path.age` (which references `User_age`)
3. The PostgreSQL driver iterates `serializer.serializableProperties`

At that point, the object is created with the defaults baked in. **No separate initialization step needed.**

### Simple Types Work Out of the Box

For simple defaults, we emit the actual value:

```kotlin
override val default: Int? = 18
override val default: String? = "active"
override val default: Boolean? = true
override val default: Double? = 3.14
```

### Complex Types Get Code String Only

For complex defaults we can't evaluate, we store the code:

```kotlin
override val default: Uuid? = null /* complex default: Uuid.random() */
override val defaultCode: String = "Uuid.random()"
```

The PostgreSQL driver can decide:
- Use the `default` value if available (simple types)
- Fall back to making the column nullable if `default` is null (complex types)
- Use `defaultCode` for documentation/migration scripts

## Example Usage

```kotlin
// In your code
val ageProperty = User.serializer().serializableProperties!![2]
println(ageProperty.name)         // "age"
println(ageProperty.default)      // 18
println(ageProperty.defaultCode)  // "18"

// In PostgreSQL driver
val statusProperty = User.serializer().serializableProperties!![3]
if (statusProperty.default != null) {
    // Can add as NOT NULL with SQL DEFAULT
    // ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
} else {
    // Complex default or no default - add as nullable
    // ALTER TABLE users ADD COLUMN status TEXT
}
```

## What's Still Needed

### PostgreSQL Driver Integration

Update `SerialDescriptorTable.kt` to:

1. Extract defaults from properties when creating columns
2. Convert simple defaults to SQL DEFAULT clauses
3. Apply defaults via Exposed's column API

Example integration:

```kotlin
internal fun SerialDescriptor.columnType(
    serializersModule: SerializersModule,
    serializer: KSerializer<*>
): List<ColumnTypeInfo> {
    val properties = serializer.serializableProperties

    // ... existing code ...

    return info.mapIndexed { index, typeInfo ->
        val property = properties?.getOrNull(index)
        ColumnTypeInfo(
            key = typeInfo.key,
            type = typeInfo.type,
            descriptorPath = typeInfo.descriptorPath,
            default = property?.default,  // NEW
            defaultCode = property?.defaultCode  // NEW
        )
    }
}
```

Then when creating columns:

```kotlin
descriptor.columnType(serializersModule, serializer).forEach { info ->
    val col = registerColumn<Any?>(info.key.joinToString("__"), info.type)

    // Apply SQL default if available
    if (info.default != null) {
        col.default(literalOf(info.default))  // Exposed API for SQL DEFAULT
    }
}
```

## Benefits Over Registry Approach

✅ **No initialization order issues** - properties initialized on demand
✅ **No global state** - defaults are part of the property object
✅ **No `prepareModels()`** - everything happens naturally
✅ **Type-safe** - defaults are properly typed
✅ **Works with incremental compilation** - no cross-file dependencies
✅ **Easier to debug** - defaults visible in generated code

## Limitations

❌ **Complex defaults can't be evaluated at compile time** - stored as code only
❌ **Generic types** - need to handle with care (default values lose type parameters)
❌ **Exposed API** - need to verify Exposed supports SQL DEFAULT clauses properly

## Next Steps

1. Test that generated code compiles
2. Implement PostgreSQL integration to actually use the defaults
3. Handle edge cases (arrays, nested objects, enums)
4. Write comprehensive tests
5. Update schema evolution tests to verify non-nullable fields work
