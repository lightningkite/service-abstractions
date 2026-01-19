# DbMapFormat Architecture Plan (v3 - Simplified)

## Overview

A clean abstraction for serializing/deserializing Kotlin objects to/from database-friendly `Map<String, Any?>` structures, with proper support for:

1. **Value classes** (inline classes) - handled correctly via `encodeInline`/`decodeInline`
2. **Type conversions** - pluggable converters for database-specific types
3. **Collection handling** - pluggable strategies (arrays, native collections, JSON)
4. **Database agnostic** - works for Postgres, Cassandra, and future databases

**Location:** `database/src/jvmMain/kotlin/com/lightningkite/services/database/`

## Design Principle: Separation of Concerns

The **format** handles:
- Serializing objects to `Map<String, Any?>`
- Deserializing `Map<String, Any?>` back to objects
- Flattening embedded structs (e.g., `address.city` → `address__city`)
- Delegating collection handling to pluggable handlers

The **database layer** handles:
- Providing appropriate ValueConverters for its native types
- Providing appropriate CollectionHandler for its storage strategy
- Schema generation (DDL)
- Query translation

---

## Core Interfaces

### ValueConverter

```kotlin
/**
 * Converts between Kotlin serialization values and database-native values.
 */
interface ValueConverter<K : Any, D : Any> {
    val descriptor: SerialDescriptor
    fun toDatabase(value: K): D
    fun fromDatabase(value: D): K
}

/**
 * Registry of value converters, keyed by serial name.
 */
class ValueConverterRegistry(converters: List<ValueConverter<*, *>>) {
    fun has(descriptor: SerialDescriptor): Boolean
    fun get(descriptor: SerialDescriptor): ValueConverter<*, *>?
    fun <K : Any> toDatabase(descriptor: SerialDescriptor, value: K): Any
    fun <K : Any> fromDatabase(descriptor: SerialDescriptor, dbValue: Any): K
}
```

### CollectionHandler

```kotlin
/**
 * Handles collection serialization strategy.
 * Database layers provide implementations for their storage approach.
 */
interface CollectionHandler {
    fun createListEncoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder

    fun createListDecoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder

    fun createMapEncoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder

    fun createMapDecoder(
        fieldPath: String,
        keyDescriptor: SerialDescriptor,
        valueDescriptor: SerialDescriptor,
        input: ReadSource,
    ): CompositeDecoder
}
```

### WriteTarget / ReadSource

```kotlin
/**
 * Target for writing serialized data.
 */
interface WriteTarget {
    fun writeField(path: String, value: Any?)

    // For future child table support
    fun writeChild(childName: String, row: ChildRow)

    fun result(): WriteResult
}

/**
 * Source for reading serialized data.
 */
interface ReadSource {
    fun readField(path: String): Any?
    fun hasField(path: String): Boolean

    // For future child table support
    fun readChildren(childName: String): List<ChildRow>
}

/**
 * Child row for future child table support.
 */
class ChildRow(
    val index: Int?,
    val key: Map<String, Any?>?,
    val values: Map<String, Any?>,
)

/**
 * Result of serialization.
 */
class WriteResult(
    val mainRecord: Map<String, Any?>,
    val children: Map<String, List<ChildRow>>,
)
```

---

## Configuration

```kotlin
/**
 * Minimal configuration for the map format.
 */
class MapFormatConfig(
    val serializersModule: SerializersModule,
    val converters: ValueConverterRegistry,
    val collectionHandler: CollectionHandler,
    val fieldSeparator: String = "__",
)
```

---

## The Format

```kotlin
class MapFormat(val config: MapFormatConfig) {

    fun <T> encode(serializer: KSerializer<T>, value: T): WriteResult {
        val target = SimpleWriteTarget()
        val encoder = MapEncoder(config, target)
        serializer.serialize(encoder, value)
        return target.result()
    }

    fun <T> decode(serializer: KSerializer<T>, source: ReadSource): T {
        val decoder = MapDecoder(config, source, serializer.descriptor)
        return serializer.deserialize(decoder)
    }

    // Convenience for simple map input
    fun <T> decode(serializer: KSerializer<T>, map: Map<String, Any?>): T {
        return decode(serializer, SimpleReadSource(map, emptyMap(), config.fieldSeparator))
    }
}
```

---

## Encoder Implementation

Key patterns (following kotlinx-serialization-csv-durable):

1. **Tag stack** for tracking field paths during encoding
2. **`encodeInline`** returns self after checking for converters - tag stays on stack for wrapped value
3. **`encodeInlineElement`** pushes tag and returns self
4. **Primitives** pop tag and write to output
5. **Embedded structs** are flattened via nested paths (e.g., `address__city`)
6. **Nullable embedded classes** use `__exists` marker

```kotlin
class MapEncoder(
    private val config: MapFormatConfig,
    private val output: WriteTarget,
) : Encoder, CompositeEncoder {

    private val tagStack = mutableListOf<String>()

    // Value class support
    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        config.converters.get(descriptor)?.let {
            return ConverterEncoder(popTag(), it, output)
        }
        return this
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        val path = nested(descriptor.getElementName(index))
        config.converters.get(descriptor.getElementDescriptor(index))?.let {
            return ConverterEncoder(path, it, output)
        }
        pushTag(path)
        return this
    }

    // Primitives pop tag and write
    override fun encodeString(value: String) = output.writeField(popTag(), value)
    // ... other primitives similar

    // Collections delegate to handler
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return when (descriptor.kind) {
            StructureKind.LIST -> {
                val path = popTag()
                config.collectionHandler.createListEncoder(path, descriptor.getElementDescriptor(0), output)
            }
            StructureKind.MAP -> {
                val path = popTag()
                config.collectionHandler.createMapEncoder(path, descriptor.getElementDescriptor(0), descriptor.getElementDescriptor(1), output)
            }
            else -> this
        }
    }
}
```

---

## Decoder Implementation

Mirror of encoder with:

1. **`decodeInline`** returns self after checking for converters
2. **`decodeInlineElement`** pushes tag and returns self
3. **`decodeElementIndex`** iterates fields, checking for data presence
4. **`decodeNotNullMark`** checks `__exists` marker for embedded classes

---

## Collection Handler Implementations

### ArrayListCollectionHandler (for Postgres, Cassandra)

Accumulates elements into a `List<Any?>` and writes as single field value.

```kotlin
class ArrayListCollectionHandler(
    private val config: MapFormatConfig,
) : CollectionHandler {

    override fun createListEncoder(
        fieldPath: String,
        elementDescriptor: SerialDescriptor,
        output: WriteTarget,
    ): CompositeEncoder {
        return ListAccumulatorEncoder(fieldPath, elementDescriptor, config, output)
    }

    // ... other methods
}

// Encoder that accumulates elements into a list
class ListAccumulatorEncoder(...) : CompositeEncoder {
    private val elements = mutableListOf<Any?>()

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        elements.add(value)
    }
    // ... other primitives

    override fun endStructure(descriptor: SerialDescriptor) {
        output.writeField(fieldPath, elements.toList())
    }
}
```

### JsonCollectionHandler (fallback)

Serializes collection to JSON string.

```kotlin
class JsonCollectionHandler(
    private val json: Json,
) : CollectionHandler {
    // Encodes collection as JSON string in the map
}
```

---

## Database Layer Usage

### Postgres

```kotlin
val postgresConverters = ValueConverterRegistry(listOf(
    object : ValueConverter<Uuid, java.util.UUID> {
        override val descriptor = Uuid.serializer().descriptor
        override fun toDatabase(value: Uuid) = value.toJavaUuid()
        override fun fromDatabase(value: java.util.UUID) = value.toKotlinUuid()
    },
    // Instant, LocalDate, Duration, etc.
))

val postgresFormat = MapFormat(MapFormatConfig(
    serializersModule = myModule,
    converters = postgresConverters,
    collectionHandler = ArrayListCollectionHandler(config),
))

// Encode
val result = postgresFormat.encode(User.serializer(), user)
// result.mainRecord is Map<String, Any?> ready for Exposed/JDBC

// Decode
val user = postgresFormat.decode(User.serializer(), rowMap)
```

### Cassandra

```kotlin
val cassandraConverters = ValueConverterRegistry(listOf(
    // Similar converters for Cassandra types
))

val cassandraFormat = MapFormat(MapFormatConfig(
    serializersModule = myModule,
    converters = cassandraConverters,
    collectionHandler = ArrayListCollectionHandler(config), // Cassandra has native list/set/map
))
```

---

## Implementation Order

1. **Core interfaces**: `ValueConverter`, `ValueConverterRegistry`, `CollectionHandler`
2. **I/O interfaces**: `WriteTarget`, `ReadSource`, `SimpleWriteTarget`, `SimpleReadSource`
3. **Config**: `MapFormatConfig`
4. **Encoder**: `MapEncoder`, `ConverterEncoder`
5. **Decoder**: `MapDecoder`, `ConverterDecoder`
6. **ArrayListCollectionHandler**: Simple accumulator-based implementation
7. **MapFormat**: Main entry point
8. **Tests**: Round-trip tests with value classes, embedded structs, collections
9. **Integrate with Postgres**: Replace existing DbMapLikeFormat
10. **Integrate with Cassandra**: Replace JSON intermediate approach

---

## What's NOT in this design (intentionally)

- **FieldStructure/TypeStructure/StructureAnalyzer** - Database layers walk SerialDescriptor directly for schema generation
- **nestedCollectionHandler** - Single handler; if complex nested behavior needed, handler can inspect element descriptor
- **InlineHint enum** - Database layer knows what handler it's using
- **Child table implementation** - Interface supports it, implementation deferred

---

## Open Questions

1. Should `WriteResult` just be `Map<String, Any?>` since we're deferring child tables? Or keep the structure for future compatibility?

2. Field separator default `__` vs `.` - which is more broadly compatible?
