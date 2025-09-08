package com.lightningkite.services.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.GeneratedSerializer

@OptIn(InternalSerializationApi::class)
internal expect fun GeneratedSerializer<*>.factory(): (typeArguments: Array<KSerializer<*>>) -> KSerializer<*>

internal class PlatformNotSupportedError: Error("This function is not supported on this platform.")