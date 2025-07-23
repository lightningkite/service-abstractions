package com.lightningkite.serviceabstractions

import kotlinx.serialization.modules.SerializersModule

public interface SettingContext {
    public val serializersModule: SerializersModule
    public val metricSink: MetricSink
}

public interface Setting<T> {
    public operator fun invoke(context: SettingContext): T
}