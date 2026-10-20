@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import com.lightningkite.services.database.path.*

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

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
