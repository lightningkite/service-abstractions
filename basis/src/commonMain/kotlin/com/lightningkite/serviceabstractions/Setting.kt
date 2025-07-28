package com.lightningkite.serviceabstractions

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public interface SettingContext {
    public val name: String
    public val serializersModule: SerializersModule
    public val metricSink: MetricSink
    public val clock: Clock get() = Clock.System
    public val secretBasis: ByteArray
    public suspend fun report(action: suspend ()->Unit): Unit = action()

    public companion object {
    }
}

public class TestSettingContext(
    override val serializersModule: SerializersModule = EmptySerializersModule(),
    override var clock: Clock = Clock.System
): SettingContext {
    override val name: String get() = "Test"
    override val metricSink: MetricSink = MetricSink.LogImmediately(this)
    override val secretBasis: ByteArray = ByteArray(64) { 0 }
}

public interface Setting<T> {
    public operator fun invoke(context: SettingContext): T
}
