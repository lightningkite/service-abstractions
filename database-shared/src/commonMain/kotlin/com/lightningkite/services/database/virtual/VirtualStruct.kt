@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import com.lightningkite.services.data.ExperimentalLightningServer
import com.lightningkite.services.data.nowLocal
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
                @OptIn(ExperimentalLightningServer::class)
                override val descriptor: SerialDescriptor = LazySerialDescriptor(serialName, StructureKind.CLASS) {
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
