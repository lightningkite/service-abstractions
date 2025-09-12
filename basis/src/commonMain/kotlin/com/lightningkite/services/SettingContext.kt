package com.lightningkite.services

import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public interface SettingContext {
    public val projectName: String
    public val internalSerializersModule: SerializersModule
    public val openTelemetry: OpenTelemetry?
    public val clock: Clock get() = Clock.System
    public val sharedResources: SharedResources
    public suspend fun report(action: suspend ()->Unit): Unit = action()

    public companion object {
    }
}

public operator fun <T> SettingContext.get(key: SharedResources.Key<T>): T = sharedResources.get(key, this)

