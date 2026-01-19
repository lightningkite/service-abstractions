package com.lightningkite.services.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.GeneratedSerializer

@OptIn(InternalSerializationApi::class)
internal expect fun GeneratedSerializer<*>.factory(): (typeArguments: Array<KSerializer<*>>) -> KSerializer<*>

// by Claude - Reflection-based annotation parsing (JVM only, returns null on other platforms)
internal expect fun reflectAnnotation(annotation: Annotation): SerializableAnnotation?

internal class PlatformNotSupportedError: Error("This function is not supported on this platform.")