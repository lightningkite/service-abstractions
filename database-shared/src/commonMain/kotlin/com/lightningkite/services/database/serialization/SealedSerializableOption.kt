@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.json.JsonNames

public data class SealedSerializableOption<out T>(
    val index: Int,
    val name: String,
    val secondaryNames: Set<String> = setOf(),
    val serializer: KSerializer<@UnsafeVariance T>,
)

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
public val <T> KSerializer<T>.serializableOptions: Array<SealedSerializableOption<T>>?
    get() {
        val poly = this as? AbstractPolymorphicSerializer<*> ?: return null
        if (descriptor.kind != PolymorphicKind.SEALED) return null
        val subDesc = descriptor.getElementDescriptor(1)
        return Array(subDesc.elementsCount) { i ->
            val name = subDesc.getElementName(i)
            val secondaryNames = subDesc.getElementAnnotations(i)
                .filterIsInstance<JsonNames>()
                .flatMap { it.names.toList() }
                .toSet()
            val ser = poly.findPolymorphicSerializerOrNull(StubPolymorphicDecoder, name) as? KSerializer<T>
                ?: return null
            SealedSerializableOption(i, name, secondaryNames, ser)
        }
    }

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
public fun <T> KSerializer<T>.serializableOptionsIdentify(item: T): SealedSerializableOption<T>? {
    val poly = this as? AbstractPolymorphicSerializer<Any> ?: return null
    if (descriptor.kind != PolymorphicKind.SEALED) return null
    val ser = poly.findPolymorphicSerializerOrNull(StubPolymorphicEncoder, item as Any) ?: return null
    return serializableOptions?.firstOrNull { it.serializer.descriptor.serialName == ser.descriptor.serialName }
        ?.let { it as SealedSerializableOption<T>? }
}
