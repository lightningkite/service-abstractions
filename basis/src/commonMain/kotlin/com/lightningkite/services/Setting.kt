package com.lightningkite.services

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public interface SettingContext {
    public val projectName: String
    public val internalSerializersModule: SerializersModule
    public val metricSink: MetricSink
    public val clock: Clock get() = Clock.System
    public val secretBasis: ByteArray
    public suspend fun report(action: suspend ()->Unit): Unit = action()

    public companion object {
    }
}

public class TestSettingContext(
    override val internalSerializersModule: SerializersModule = EmptySerializersModule(),
    override var clock: Clock = Clock.System
): SettingContext {
    override val projectName: String get() = "Test"
    override val metricSink: MetricSink = MetricSink.MetricLogger(this)
    override val secretBasis: ByteArray = ByteArray(64) { 0 }
}

public interface Setting<T> {
    public operator fun invoke(context: SettingContext): T
}
