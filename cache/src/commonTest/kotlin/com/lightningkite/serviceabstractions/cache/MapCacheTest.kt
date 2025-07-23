package com.lightningkite.serviceabstractions.cache

import com.lightningkite.serviceabstractions.MetricSink
import com.lightningkite.serviceabstractions.SettingContext
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

class MapCacheTest: CacheTest() {
    override val cache: Cache = MapCache(mutableMapOf(), object: SettingContext {
        override val metricSink: MetricSink
            get() = MetricSink.None
        override val serializersModule: SerializersModule
            get() = EmptySerializersModule()
    })
}