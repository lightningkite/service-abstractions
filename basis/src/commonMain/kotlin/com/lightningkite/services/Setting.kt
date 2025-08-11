package com.lightningkite.services

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public interface SettingContext {
    public val projectName: String
    public val internalSerializersModule: SerializersModule
    public val clock: Clock get() = Clock.System
    public suspend fun report(action: suspend ()->Unit): Unit = action()

    public companion object {
    }
}

public class TestSettingContext(
    override val internalSerializersModule: SerializersModule = EmptySerializersModule(),
    override var clock: Clock = Clock.System
): SettingContext {
    override val projectName: String get() = "Test"
}

public interface Setting<T> {
    public operator fun invoke(name: String, context: SettingContext): T
}
