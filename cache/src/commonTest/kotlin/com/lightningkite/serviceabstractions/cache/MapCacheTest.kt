package com.lightningkite.serviceabstractions.cache

import com.lightningkite.serviceabstractions.MetricSink
import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.TestSettingContext
import com.lightningkite.serviceabstractions.cache.test.CacheTest
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

class MapCacheTest: CacheTest() {
    override val cache: Cache = MapCache(mutableMapOf(), TestSettingContext())
}