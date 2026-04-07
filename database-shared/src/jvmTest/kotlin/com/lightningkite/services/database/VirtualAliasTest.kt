package com.lightningkite.services.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class AliasTesterString(val value: String)
@Serializable
@JvmInline
value class AliasTesterUuid(val value: Uuid)
@Serializable
@JvmInline
value class AliasTesterGeneric<T>(val value: T)

class VirtualAliasTest {
    @Test
    fun test() {
        val registry = SerializationRegistry(EmptySerializersModule())
        registry.registerVirtualDeep(AliasTesterString.serializer())
        registry.registerVirtualDeep(AliasTesterUuid.serializer())
        registry.registerVirtualDeep(AliasTesterGeneric.serializer(Int.serializer()))
        registry.registerVirtualDeep(AliasTesterGeneric.serializer(Uuid.serializer()))

        println("Virtual types:")
        registry.virtualTypes.values.forEach { println(it) }
    }
}