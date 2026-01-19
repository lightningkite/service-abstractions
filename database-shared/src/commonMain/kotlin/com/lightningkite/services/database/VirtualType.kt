@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import com.lightningkite.nowLocal
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.uuid.Uuid

public sealed interface VirtualType {
    public val serialName: String
    public fun serializer(registry: SerializationRegistry, arguments: Array<KSerializer<*>>): KSerializer<*>
    public val annotations: List<SerializableAnnotation>
}

@Serializable
public data class VirtualAlias(
    override val serialName: String,
    val wraps: VirtualTypeReference,
    override val annotations: List<SerializableAnnotation>
) : VirtualType {
    override fun serializer(registry: SerializationRegistry, arguments: Array<KSerializer<*>>): KSerializer<*> {
        return wraps.serializer(registry, mapOf())  // TODO: Support generics
    }
}

@Serializable
public data class VirtualTypeParameter(
    val name: String,
)

@Serializable
public data class VirtualStruct(
    override val serialName: String,
    override val annotations: List<SerializableAnnotation>,
    val fields: List<VirtualField>,
    val parameters: List<VirtualTypeParameter>,
) : VirtualType {
    internal val idField = fields.find { it.name == "_id" }

    public operator fun invoke(registry: SerializationRegistry, vararg arguments: KSerializer<*>): Concrete =
        Concrete(registry, arguments)

    override fun serializer(registry: SerializationRegistry, arguments: Array<KSerializer<*>>): KSerializer<*> =
        Concrete(registry, arguments)

    override fun toString(): String = "virtual data class $serialName${
        parameters.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.name } ?: ""
    }(${fields.joinToString()})"

    private object DefaultNotPresent
    public inner class Concrete(private val registry: SerializationRegistry, public val arguments: Array<out KSerializer<*>>) :
        KSerializer<VirtualInstance>, VirtualType by this@VirtualStruct,
        KSerializerWithDefault<VirtualInstance> {
        internal val struct: VirtualStruct = this@VirtualStruct

        init {
            if (arguments.size < parameters.size) throw IllegalArgumentException("VirtualStructure ${serialName} needs ${parameters.size} parameters, but we only got ${arguments.size}")
        }

        private val typeArguments = parameters.indices.associate { parameters[it].name to arguments[it] }

        override val default: VirtualInstance
            get() = VirtualInstance(
                this,
                fields.indices.map { index ->
                    val gen = defaultGenerators[index]
                    val serializer = serializers[index]
                    if (gen != null) gen() else serializer.default()
                }
            )

        public operator fun invoke(): VirtualInstance = default
        public operator fun invoke(map: Map<VirtualField, Any?> = mapOf()): VirtualInstance {
            val fields = fields.indices.mapTo(ArrayList()) { index ->
                val gen = defaultGenerators[index]
                val serializer = serializers[index]
                if (gen != null) gen() else serializer.default()
            }
            for ((key, value) in map) fields[key.index] = value
            return VirtualInstance(this, fields)
        }

        private var instantiated: Boolean = false
        private val placeholderSerializer: KSerializer<Any?> by lazy {
            // Placeholder is needed for cases where the class has properties of itself. Ex. data class Nested(val inner: Nested?)
            // When Concrete gets it's internal property serializers it will check if the property is itself, if it is
            // then this placeholder will be returned, which holds dummy information until the full descriptor is built
            object : KSerializer<Any?> {
                private val placeholderDescriptor = buildClassSerialDescriptor("$serialName.placeholder").nullable
                override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor(serialName) {
                    if (instantiated) this@Concrete.descriptor else placeholderDescriptor
                }

                override fun deserialize(decoder: Decoder): Any {
                    if (!instantiated) throw SerializationException("Concrete $serialName placeholder deserialize called before instantiated")
                    return this@Concrete.deserialize(decoder)
                }

                override fun serialize(encoder: Encoder, value: Any?) {
                    if (!instantiated) throw SerializationException("Concrete $serialName placeholder deserialize called before instantiated")
                    if (value == null) encoder.encodeNull()
                    else this@Concrete.serialize(encoder, value as VirtualInstance)
                }
            }
        }

        private val serializers by lazy {
            fields.map {
                if (it.type.serialName == serialName) placeholderSerializer
                else it.type.serializer(registry, typeArguments)
            }
        }
        private val defaultGenerators: List<(() -> Any?)?> by lazy {
            fields.zip(serializers) { field, serializer ->
                field.defaultCode?.trim()?.let {
                    // handling some common cases for sanity's sake
                    when (it) {
                        "Uuid.random()",
                        "uuid()", "UUID.random()" -> {
                            { Uuid.random() }
                        }

                        "now()",
                        "Clock.System.now()",
                        "Clock.default().now()" -> {
                            { Clock.System.now() }
                        }

                        "nowLocal().date",
                        "Clock.System.nowLocal().date",
                        "Clock.default().nowLocal().date" -> {
                            { Clock.System.nowLocal().date }
                        }

                        "nowLocal().time",
                        "Clock.System.nowLocal().time",
                        "Clock.default().nowLocal().time" -> {
                            { Clock.System.nowLocal().time }
                        }
                        // TODO: Check for field cloning?
                        else -> null
                    }
                } ?: field.defaultJson?.let {
                    try {
                        val v = DefaultDecoder.json.decodeFromString(serializer, it)
                        ;{ v }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            }
        }
        private val skipSerializationIfEqual by lazy {
            defaultGenerators.map {
                if (it != null) it()
                else DefaultNotPresent
            }
        }

        public val serializableProperties: Array<SerializableProperty<VirtualInstance, Any?>> by lazy {
            fields.map {
                SerializableProperty.FromVirtualField(it, registry, typeArguments)
            }.toTypedArray()
        }
        private val ensureNotNull = fields.withIndex().filter {
            !it.value.optional && !it.value.type.isNullable
        }.map { it.index }.toIntArray()

        @Transient
        override val descriptor: SerialDescriptor by lazy {
            val descriptor = buildClassSerialDescriptor(serialName) {
                for (field in fields)
                    element(
                        elementName = field.name,
                        descriptor = serializers[field.index].descriptor,
                        annotations = listOf(),
                        isOptional = field.optional
                    )
            }
            instantiated = true
            descriptor
        }

        override fun deserialize(decoder: Decoder): VirtualInstance {
            val values = Array<Any?>(fields.size) {
                defaultGenerators[it]?.invoke()
            }
            val s = decoder.beginStructure(descriptor)
            while (true) {
                val index = s.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index == CompositeDecoder.UNKNOWN_NAME) break
                val f = fields[index]
                if (f.type.isNullable) {
                    values[index] = s.decodeNullableSerializableElement(
                        descriptor,
                        index,
                        serializers[index],
                        null
                    )
                } else {
                    values[index] = s.decodeSerializableElement(
                        descriptor,
                        index,
                        serializers[index],
                        null
                    )
                }
            }
            s.endStructure(descriptor)
            // Ensure we got everything
            ensureNotNull.forEach { index ->
                if (values[index] == null) {
                    throw SerializationException("${fields[index].name} required but was not present")
                }
            }
            return VirtualInstance(this, values.asList())
        }

        override fun serialize(encoder: Encoder, value: VirtualInstance) {
            val s = encoder.beginStructure(descriptor)
            for ((index, field) in fields.withIndex()) {
                val v = value.values[index]
                if (v != skipSerializationIfEqual[index] || s.shouldEncodeElementDefault(descriptor, index)) {
                    val ser = serializers[index]
                    s.encodeSerializableElement(descriptor, index, ser, v)
                }
            }
            s.endStructure(descriptor)
        }
    }
}

@Serializable
public data class VirtualEnum(
    override val serialName: String,
    override val annotations: List<SerializableAnnotation>,
    public val options: List<VirtualEnumOption>,
) : VirtualType, KSerializer<VirtualEnumValue> {
    @Transient
    private val entries = options.map { VirtualEnumValue(this, it.index) }

    @Transient
    private val map = options.associateBy { it.name }
    override fun serializer(registry: SerializationRegistry, arguments: Array<KSerializer<*>>): KSerializer<*> = this
    override fun toString(): String = "Virtual $serialName { ${options.joinToString()} }"

    @OptIn(SealedSerializationApi::class)
    @Transient
    override val descriptor: SerialDescriptor = object : SerialDescriptor {
        @ExperimentalSerializationApi
        override val elementsCount: Int get() = options.size

        @ExperimentalSerializationApi
        override val kind: SerialKind = SerialKind.ENUM

        @ExperimentalSerializationApi
        override val serialName: String = this@VirtualEnum.serialName

        @ExperimentalSerializationApi
        override fun getElementAnnotations(index: Int): List<Annotation> = listOf()

        @ExperimentalSerializationApi
        override fun getElementDescriptor(index: Int): SerialDescriptor = this

        @ExperimentalSerializationApi
        override fun getElementIndex(name: String): Int = map.get(name)?.index ?: CompositeDecoder.UNKNOWN_NAME

        @ExperimentalSerializationApi
        override fun getElementName(index: Int): String = options[index].name

        @ExperimentalSerializationApi
        override fun isElementOptional(index: Int): Boolean = false
    }

    override fun deserialize(decoder: Decoder): VirtualEnumValue {
        return entries[decoder.decodeEnum(descriptor)]
    }

    override fun serialize(encoder: Encoder, value: VirtualEnumValue) {
        encoder.encodeEnum(descriptor, value.index)
    }

    public companion object {
        public val cache: HashMap<String, VirtualEnum> = HashMap()
    }
}

@Serializable
public data class VirtualEnumOption(
    val name: String,
    val annotations: List<SerializableAnnotation>,
    val index: Int
)

public class VirtualEnumValue(
    public val enum: VirtualEnum,
    public val index: Int,
) : Comparable<VirtualEnumValue> {
    override fun compareTo(other: VirtualEnumValue): Int = index.compareTo(other.index)
    override fun toString(): String = enum.options[index].name
}

public data class VirtualInstance(
    public val type: VirtualStruct.Concrete,
    public val values: List<Any?>
) : HasId<Comparable<Comparable<*>>>, Comparable<VirtualInstance> {
    @Suppress("UNCHECKED_CAST")
    override val _id: Comparable<Comparable<*>>
        get() = type.struct.idField?.let { values[it.index] as Comparable<Comparable<*>> }
            ?: values.hashCode() as Comparable<Comparable<*>>

    override fun toString(): String =
        "${type.struct.serialName}(${values.zip(type.struct.fields).joinToString { "${it.second.name}=${it.first}" }})"

    override fun compareTo(other: VirtualInstance): Int {
        for (i in values.indices) {
            val t = this.values[i]
            val o = other.values[i]
            @Suppress("UNCHECKED_CAST")
            if (t is Comparable<*>) {
                val r = (t as Comparable<Any?>).compareTo(o)
                if (r != 0) return r
            }
        }
        return 0
    }
}

@Serializable
public data class VirtualField(
    val index: Int,
    val name: String,
    val type: VirtualTypeReference,
    val optional: Boolean,
    val annotations: List<SerializableAnnotation>,
    val defaultJson: String? = null,
    val defaultCode: String? = null,
) {
    override fun toString(): String = "$name: $type"
}

private const val ARRAY_NAME = "kotlin.Array"
private const val ARRAY_LIST_NAME = "kotlin.collections.ArrayList"
private const val LINKED_HASH_SET_NAME = "kotlin.collections.LinkedHashSet"
private const val HASH_SET_NAME = "kotlin.collections.HashSet"
private const val LINKED_HASH_MAP_NAME = "kotlin.collections.LinkedHashMap"
private const val HASH_MAP_NAME = "kotlin.collections.HashMap"

internal val skipTypes = setOf(
    "com.lightningkite.services.database.Condition",
    "com.lightningkite.services.database.Modification",
    "com.lightningkite.services.database.DataClassPathPartial",
    "com.lightningkite.services.database.SortPart",
)

@Serializable
public data class VirtualTypeReference(
    val serialName: String,
    val arguments: List<VirtualTypeReference>,
    val isNullable: Boolean
) {
    init {
        if (serialName.endsWith("?")) throw Exception()
    }

    override fun toString(): String =
        serialName + (arguments.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.toString() }
            ?: "") + (if (isNullable) "?" else "")

    @Suppress("UNCHECKED_CAST")
    public fun serializer(registry: SerializationRegistry, context: Map<String, KSerializer<*>>): KSerializer<Any?> {
        return (context[serialName] as? KSerializer<Any?>
            ?: registry[serialName, arguments.map { it.serializer(registry, context) }.toTypedArray()])
            ?.let { if (isNullable) it.nullable2 else it }
            ?: throw Exception("$serialName is not registered in either the registeredTypes or registeredGenericTypes.  virtualTypes are ${registry.virtualTypes.keys.joinToString()}")
    }
}

@Serializable
public data class SerializableAnnotation(
    public val fqn: String,
    public val values: Map<String, SerializableAnnotationValue>
) {
    public companion object {
        private val __do_not_use_externally__parsers = HashMap<String, (Annotation) -> SerializableAnnotation>()
        public fun <T : Annotation> parser(name: String, handler: (T) -> SerializableAnnotation) {
            @Suppress("UNCHECKED_CAST")
            __do_not_use_externally__parsers[name] = handler as ((Annotation) -> SerializableAnnotation)
        }

        // by Claude - Modified to use reflection fallback
        public fun parseOrNull(annotation: Annotation): SerializableAnnotation? {
            val fqn = annotation.toString().removePrefix("@").substringBefore("(")
            // First check registered parsers (for custom handling), then fall back to reflection
            return __do_not_use_externally__parsers[fqn]?.invoke(annotation)
                ?: reflectAnnotation(annotation)
        }
    }
}

public val KSerializer<*>.serializableAnnotations: List<SerializableAnnotation>
    get() = if (this is VirtualType) this.annotations else descriptor.annotations.mapNotNull {
        SerializableAnnotation.parseOrNull(
            it
        )
    }

public fun KSerializer<*>.getElementSerializableAnnotations(index: Int): List<SerializableAnnotation> =
    if (this is VirtualStruct.Concrete) this.struct.fields[index].annotations
    else descriptor.getElementAnnotations(index).mapNotNull { SerializableAnnotation.parseOrNull(it) }

@Serializable
public sealed class SerializableAnnotationValue {
    @Serializable
    public data object NullValue : SerializableAnnotationValue()

    @Serializable
    public data class BooleanValue(val value: Boolean) : SerializableAnnotationValue()

    @Serializable
    public data class ByteValue(val value: Byte) : SerializableAnnotationValue()

    @Serializable
    public data class ShortValue(val value: Short) : SerializableAnnotationValue()

    @Serializable
    public data class IntValue(val value: Int) : SerializableAnnotationValue()

    @Serializable
    public data class LongValue(val value: Long) : SerializableAnnotationValue()

    @Serializable
    public data class FloatValue(val value: Float) : SerializableAnnotationValue()

    @Serializable
    public data class DoubleValue(val value: Double) : SerializableAnnotationValue()

    @Serializable
    public data class CharValue(val value: Char) : SerializableAnnotationValue()

    @Serializable
    public data class StringValue(val value: String) : SerializableAnnotationValue()

    @Serializable
    public data class ClassValue(val fqn: String) : SerializableAnnotationValue()

    @Serializable
    public data class ArrayValue(val value: List<SerializableAnnotationValue>) : SerializableAnnotationValue()
    public companion object {
        @OptIn(InternalSerializationApi::class)
        public operator fun invoke(value: Any?): SerializableAnnotationValue {
            return when (value) {
                null -> NullValue
                is Boolean -> BooleanValue(value)
                is Byte -> ByteValue(value)
                is Short -> ShortValue(value)
                is Int -> IntValue(value)
                is Long -> LongValue(value)
                is Float -> FloatValue(value)
                is Double -> DoubleValue(value)
                is Char -> CharValue(value)
                is String -> StringValue(value)
                is KClass<*> -> ClassValue(value.serializerOrNull()?.descriptor?.serialName ?: "")
                is BooleanArray -> ArrayValue(value.map { invoke(it) })
                is ByteArray -> ArrayValue(value.map { invoke(it) })
                is ShortArray -> ArrayValue(value.map { invoke(it) })
                is IntArray -> ArrayValue(value.map { invoke(it) })
                is LongArray -> ArrayValue(value.map { invoke(it) })
                is FloatArray -> ArrayValue(value.map { invoke(it) })
                is DoubleArray -> ArrayValue(value.map { invoke(it) })
                is CharArray -> ArrayValue(value.map { invoke(it) })
                is Array<*> -> ArrayValue(value.map { invoke(it) })
                else -> NullValue
            }
        }
    }
}


@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
public fun KSerializer<*>.virtualTypeReference(registry: SerializationRegistry): VirtualTypeReference {
    val o = nullElement() ?: this
    if (o.descriptor.kind == SerialKind.CONTEXTUAL) {
        registry.module.getContextualDescriptor(o.descriptor)?.let {
            return VirtualTypeReference(
                serialName = it.serialName,
                arguments = o.typeParametersSerializersOrNull()?.map { it.virtualTypeReference(registry) } ?: listOf(),
                isNullable = this.descriptor.isNullable
            )
        }
    }
    return VirtualTypeReference(
        serialName = o.descriptor.serialName,
        arguments = o.typeParametersSerializersOrNull()?.map { it.virtualTypeReference(registry) } ?: listOf(),
        isNullable = descriptor.isNullable
    )
}