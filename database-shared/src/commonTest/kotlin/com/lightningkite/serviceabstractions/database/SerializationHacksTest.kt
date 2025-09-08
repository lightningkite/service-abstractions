package com.lightningkite.serviceabstractions.database

import com.lightningkite.services.database.PlatformNotSupportedError
import com.lightningkite.services.database.factory
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test

class SerializationHacksTest {
    @OptIn(InternalSerializationApi::class)
    @Test fun test() {
        try {
            val x = (SampleBox.serializer(NothingSerializer()) as GeneratedSerializer<*>)
                .factory()
                .invoke(arrayOf(Int.serializer(), Int.serializer(), Int.serializer()))
                .also {
                    println("Serializer is $it")
                    println("Descriptor info is ${it.descriptor.serialName}")
                } as KSerializer<SampleBox<Int>>
            Json.encodeToString(x, SampleBox(1)).also { println(it) }
        } catch(e: PlatformNotSupportedError) {
            println("Skipping test due to lack of serialization features.")
        }
    }
}

@Serializable
data class SampleBox<T>(val value: T)
