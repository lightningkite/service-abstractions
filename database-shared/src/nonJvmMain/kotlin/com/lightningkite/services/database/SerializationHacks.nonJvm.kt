package com.lightningkite.services.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.GeneratedSerializer

@OptIn(markerClass = [InternalSerializationApi::class])
internal actual fun GeneratedSerializer<*>.factory(): (typeArguments: Array<KSerializer<*>>) -> KSerializer<*> {
    throw PlatformNotSupportedError()
}

// by Claude - Reflection not available on non-JVM platforms
internal actual fun reflectAnnotation(annotation: Annotation): SerializableAnnotation? = null