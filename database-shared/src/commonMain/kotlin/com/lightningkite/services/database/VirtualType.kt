@file:OptIn(ExperimentalSerializationApi::class, ExperimentalUnsignedTypes::class)

package com.lightningkite.services.database

import com.lightningkite.services.data.nowLocal
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
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
    override val annotations: List<SerializableAnnotation>,
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
    public inner class Concrete(
        private val registry: SerializationRegistry,
        public val arguments: Array<out KSerializer<*>>,
    ) :
        KSerializer<VirtualInstance>, VirtualType by this@VirtualStruct,
        KSerializerWithDefault<VirtualInstance> {
        internal val struct: VirtualStruct = this@VirtualStruct

        init {
            if (arguments.size < parameters.size) throw IllegalArgumentException("VirtualStructure $serialName needs ${parameters.size} parameters, but we only got ${arguments.size}")
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
                        "uuid()", "UUID.random()",
                            -> {
                            { Uuid.random() }
                        }

                        "now()",
                        "Clock.System.now()",
                        "Clock.default().now()",
                            -> {
                            { Clock.System.now() }
                        }

                        "nowLocal().date",
                        "Clock.System.nowLocal().date",
                        "Clock.default().nowLocal().date",
                            -> {
                            { Clock.System.nowLocal().date }
                        }

                        "nowLocal().time",
                        "Clock.System.nowLocal().time",
                        "Clock.default().nowLocal().time",
                            -> {
                            { Clock.System.nowLocal().time }
                        }
                        // TODO: Check for field cloning?
                        else -> null
                    }
                } ?: field.defaultJson?.let {
                    try {
                        val v = DefaultDecoder.json.decodeFromString(serializer, it)
                        ; { v }
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
            val values = Array(fields.size) {
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
            for ((index, _) in fields.withIndex()) {
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
public data class VirtualSealedOption(
    val name: String,
    val secondaryNames: Set<String>,
    val type: VirtualTypeReference,
    val index: Int,
)

public data class VirtualSealedInstance(
    val option: VirtualSealedOption,
    val value: Any,
)

@Serializable
public data class VirtualSealed(
    override val serialName: String,
    override val annotations: List<SerializableAnnotation>,
    val options: List<VirtualSealedOption>,
    val parameters: List<VirtualTypeParameter> = listOf(),
) : VirtualType {

    public operator fun invoke(registry: SerializationRegistry, vararg arguments: KSerializer<*>): Concrete =
        Concrete(registry, arguments)

    override fun serializer(registry: SerializationRegistry, arguments: Array<KSerializer<*>>): KSerializer<*> =
        Concrete(registry, arguments)

    override fun toString(): String = "virtual sealed class $serialName${
        parameters.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.name } ?: ""
    } { ${options.joinToString { it.name }} }"

    public inner class Concrete(
        private val registry: SerializationRegistry,
        public val arguments: Array<out KSerializer<*>>,
    ) : KSerializerWithDefault<VirtualSealedInstance>, VirtualType by this@VirtualSealed {
        public val sealed: VirtualSealed = this@VirtualSealed


        private val typeArguments = parameters.indices.associate { parameters[it].name to arguments[it] }

        private val optionSerializers by lazy {
            options.map { it.type.serializer(registry, typeArguments) }
        }
        private val optionLookup by lazy {
            options.flatMap { (it.secondaryNames + it.name).map { k -> k to it } }.associate { it }
        }
        override val default: VirtualSealedInstance
            get() = VirtualSealedInstance(
                options.first(),
                optionSerializers.first().default()!!
            )

        public val serializableOptions: Array<SealedSerializableOption<*>> by lazy {
            options.map { SealedSerializableOption(it.index, it.name, it.secondaryNames, optionSerializers[it.index]) }
                .toTypedArray()
        }

        @OptIn(InternalSerializationApi::class)
        @Transient
        override val descriptor: SerialDescriptor by lazy {
            buildSerialDescriptor(this@VirtualSealed.serialName, PolymorphicKind.SEALED) {
                element("type", buildSerialDescriptor("type", PrimitiveKind.STRING))
                val valueDescriptor = buildClassSerialDescriptor("value") {
                    for ((index, opt) in options.withIndex()) {
                        element(elementName = opt.name, descriptor = optionSerializers[index].descriptor)
                    }
                }
                element("value", valueDescriptor)
            }
        }

        // JSON sealed class format is flat: {"type":"SubclassName","field1":"v1",...}
        // PolymorphicKind.SEALED in JSON uses array format internally, so we handle
        // JSON explicitly to match the real kotlinx.serialization sealed class wire format.
        @Suppress("UNCHECKED_CAST")
        override fun deserialize(decoder: Decoder): VirtualSealedInstance {
            if (decoder is JsonDecoder) {
                val classDiscriminator = decoder.json.configuration.classDiscriminator
                val element = decoder.decodeJsonElement() as? JsonObject
                    ?: throw SerializationException("Expected JSON object for sealed class ${this@VirtualSealed.serialName}")
                val typeName = element[classDiscriminator]?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing '$classDiscriminator' field in ${this@VirtualSealed.serialName}")
                val option = optionLookup[typeName]
                    ?: throw SerializationException("Unknown type '$typeName' for ${this@VirtualSealed.serialName}. Available: ${optionLookup.keys}")
                val actual = optionSerializers[option.index]
                val stripped = JsonObject(element.filterKeys { it != classDiscriminator })
                val value = decoder.json.decodeFromJsonElement(actual, stripped)
                return VirtualSealedInstance(option, value!!)
            }
            // Non-JSON: internal array format [typeName, value]
            return decoder.decodeStructure(descriptor) {
                val optionName = decodeStringElement(descriptor, 0)
                val option = optionLookup[optionName]
                    ?: throw SerializationException("No option '$optionName' found. Available: ${optionLookup.keys}")
                val actual = optionSerializers[option.index]
                val value = decodeSerializableElement(descriptor, 1, actual)
                VirtualSealedInstance(option, value!!)
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun serialize(encoder: Encoder, value: VirtualSealedInstance) {
            if (encoder is JsonEncoder) {
                val classDiscriminator = encoder.json.configuration.classDiscriminator
                val actual = value.option.type.serializer(registry, typeArguments)
                val subElement = encoder.json.encodeToJsonElement(actual, value.value)
                val merged = buildJsonObject {
                    put(classDiscriminator, value.option.name)
                    (subElement as? JsonObject)?.forEach { (k, v) -> put(k, v) }
                        ?: throw SerializationException("Sealed subtype ${value.option.name} must serialize to a JSON object")
                }
                encoder.encodeJsonElement(merged)
            } else {
                encoder.encodeStructure(descriptor) {
                    val actual = value.option.type.serializer(registry, typeArguments)
                    encodeStringElement(descriptor, 0, value.option.name)
                    encodeSerializableElement(descriptor, 1, actual, value.value)
                }
            }
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
        override fun getElementIndex(name: String): Int = map[name]?.index ?: CompositeDecoder.UNKNOWN_NAME

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
    val index: Int,
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
    public val values: List<Any?>,
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

@Serializable
public data class VirtualTypeReference(
    val serialName: String,
    val arguments: List<VirtualTypeReference>,
    val isNullable: Boolean,
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
    public val values: Map<String, SerializableAnnotationValue>,
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
                arguments = o.typeParametersSerializersOrNull()
                    ?.map { paramSerializer -> paramSerializer.virtualTypeReference(registry) } ?: listOf(),
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