@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*

public sealed interface VirtualType {
    public val serialName: String
    public fun serializer(registry: SerializationRegistry, arguments: Array<KSerializer<*>>): KSerializer<*>
    public val annotations: List<SerializableAnnotation>
}

@Serializable
public data class VirtualTypeParameter(
    val name: String,
)

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
            ?: throw Exception("$serialName is not registered in either the registeredTypes or registeredGenericTypes.  Options are are ${registry.registeredTypes.joinToString()}")
    }
}

// Claude: top-level functions stay in this file so their JVM class stays VirtualTypeKt.
public val KSerializer<*>.serializableAnnotations: List<SerializableAnnotation>
    get() = if (this is VirtualType) this.annotations else descriptor.annotations.mapNotNull {
        SerializableAnnotation.parseOrNull(
            it
        )
    }

public fun KSerializer<*>.getElementSerializableAnnotations(index: Int): List<SerializableAnnotation> =
    if (this is VirtualStruct.Concrete) this.struct.fields[index].annotations
    else descriptor.getElementAnnotations(index).mapNotNull { SerializableAnnotation.parseOrNull(it) }

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
