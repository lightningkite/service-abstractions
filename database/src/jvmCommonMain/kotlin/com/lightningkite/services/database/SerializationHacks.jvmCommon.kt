package com.lightningkite.services.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.GeneratedSerializer
import java.lang.reflect.Constructor

@OptIn(markerClass = [InternalSerializationApi::class])
actual fun GeneratedSerializer<*>.factory(): (typeArguments: Array<KSerializer<*>>) -> KSerializer<*> {
    val c: Constructor<*> = this::class.java.constructors.first().also {
        it.isAccessible = true
    }
    return { typeArguments ->
        c.newInstance(*typeArguments.copyOfRange(0, c.parameterTypes.size)) as KSerializer<*>
    }
}