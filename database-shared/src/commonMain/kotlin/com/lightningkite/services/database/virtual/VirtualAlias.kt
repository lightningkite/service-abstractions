@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.*

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
