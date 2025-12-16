# Database Defaults Gradle Plugin

This Gradle plugin automatically sets up the infrastructure needed to use default values in `SerializableProperty` instances, even with type-erased `KSerializer<*>` references.

## What Problem Does This Solve?

When working with Kotlin serialization and database abstractions, you often need to access property default values at runtime. However, JVM type erasure makes this challenging when you only have a `KSerializer<*>` reference (without the concrete type).

**Example scenario:**
```kotlin
@Serializable
data class User(
    val name: String,
    val age: Int = 18,  // Default value
    val status: String = "active"  // Default value
)

// Type-erased scenario (common in database operations)
val serializer: KSerializer<*> = User.serializer()
val properties = serializer.serializableProperties  // How do we access defaults here?
```

This plugin solves this by:
1. Generating `__serializableProperties` arrays with defaults embedded (via KSP)
2. Injecting a `SerializablePropertiesProvider<T>` interface into serializer classes (via compiler plugin)
3. Making `KSerializer<*>.serializableProperties` work via runtime interface checks

## How It Works

The plugin combines two components:

### 1. **KSP Processor** (database-processor)
Generates code for classes annotated with `@GenerateDataClassPaths`:
- `ModelName__serializableProperties` arrays containing property metadata with defaults
- Type-safe `DataClassPath` fields for database queries
- Type-specific extension: `KSerializer<ModelType>.serializableProperties`

### 2. **Compiler Plugin** (database-compiler-plugin)
Injects at compile time:
- `SerializablePropertiesProvider<T>` interface into `$serializer` classes
- `getSerializablePropertiesWithDefaults()` method that returns the KSP-generated array
- Enables type-erased access via interface check in `KSerializer<*>.serializableProperties`

## Usage

### Step 1: Apply the Plugin

In your `build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform")  // or kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.lightningkite.serviceabstractions.database-defaults") version "x.x.x"
}
```

That's it! The plugin automatically:
- Applies KSP plugin
- Adds database-processor as a KSP dependency
- Adds database-compiler-plugin to the compiler classpath

### Step 2: Annotate Your Models

```kotlin
import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable

@Serializable
@GenerateDataClassPaths
data class User(
    val name: String,
    val age: Int = 18,
    val status: String = "active",
    val tags: List<String> = emptyList()
)
```

### Step 3: Access Defaults

```kotlin
// Type-specific access
val serializer = User.serializer()
val properties = serializer.serializableProperties
properties.forEach { prop ->
    println("${prop.name}: default=${prop.default}, defaultCode=${prop.defaultCode}")
}

// Type-erased access (the key feature!)
val erasedSerializer: KSerializer<*> = User.serializer()
val erasedProperties = erasedSerializer.serializableProperties
// ✓ Works! Defaults are accessible even with type erasure
```

## Default Value Handling

The system distinguishes between "simple" and "complex" defaults:

### Simple Defaults (value can be serialized)
- `Int`, `String`, `Boolean`, `Double`, `Long`, etc.
- Both `default` (actual value) and `defaultCode` (source code) are populated

Example:
```kotlin
val age: Int = 18
// → default = 18, defaultCode = "18"

val status: String = "active"
// → default = "active", defaultCode = "\"active\""
```

### Complex Defaults (requires code execution)
- Function calls: `Uuid.random()`, `emptyList()`, `Clock.System.now()`
- Object creation: `User(...)`, etc.
- Only `defaultCode` is populated, `default` is `null`

Example:
```kotlin
val id: Uuid = Uuid.random()
// → default = null, defaultCode = "Uuid.random()"

val tags: List<String> = emptyList()
// → default = null, defaultCode = "emptyList()"
```

## Example: Database Schema Evolution

This is particularly useful for database migrations where you need to add default values to existing records:

```kotlin
@Serializable
@GenerateDataClassPaths
data class User(
    val name: String,
    val email: String,
    val age: Int = 18,  // New field with default
    val status: String = "pending"  // New field with default
)

// Migration code
val serializer: KSerializer<*> = User.serializer()
val properties = serializer.serializableProperties!!

val newFields = properties.filter { it.default != null }
newFields.forEach { prop ->
    // Add default value to existing records that lack this field
    database.updateMany(
        condition = Condition.Always,
        modification = prop.assign(prop.default!!)
    )
}
```

## Manual Setup (Without Plugin)

If you need more control or are troubleshooting, you can manually configure both components:

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp") version "x.x.x"
}

dependencies {
    // Add KSP processor for all KSP configurations
    configurations.filter { it.name.startsWith("ksp") }.forEach {
        add(it.name, "com.lightningkite.services:database-processor:x.x.x")
    }

    // Add compiler plugin to classpath
    kotlinCompilerPluginClasspath("com.lightningkite.services:database-compiler-plugin:x.x.x")
}
```

## Technical Details

### Why Both KSP and Compiler Plugin?

- **KSP** runs during the analysis phase and can inspect source code to extract default values from the AST
- **Compiler Plugin** runs during IR generation and can modify bytecode to inject interfaces and methods
- Together they provide a solution that works with type erasure while maintaining type safety

### How Does Type Erasure Access Work?

The `KSerializer<*>.serializableProperties` extension property checks at runtime:
```kotlin
val KSerializer<*>.serializableProperties: Array<SerializableProperty<*, *>>?
    get() = when {
        this is SerializablePropertiesProvider<*> ->
            this.getSerializablePropertiesWithDefaults()
        else ->
            // Fallback to descriptor-based lookup
            getSerializablePropertiesFromCache(this.descriptor.serialName)
    }
```

## Requirements

- Kotlin 2.0+
- kotlinx.serialization 1.6+
- KSP (automatically applied by plugin)

## License

Same as service-abstractions library
