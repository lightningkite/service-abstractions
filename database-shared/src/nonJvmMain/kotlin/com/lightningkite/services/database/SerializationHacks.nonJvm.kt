package com.lightningkite.services.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.GeneratedSerializer

@OptIn(markerClass = [InternalSerializationApi::class])
internal actual fun GeneratedSerializer<*>.factory(): (typeArguments: Array<KSerializer<*>>) -> KSerializer<*> {
    throw PlatformNotSupportedError()
}