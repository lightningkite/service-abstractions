package com.lightningkite.services.cache

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.test.CacheTest

class MapCacheTest: CacheTest() {
    override val cache: Cache = Cache.Settings("ram").invoke("test", TestSettingContext())
}