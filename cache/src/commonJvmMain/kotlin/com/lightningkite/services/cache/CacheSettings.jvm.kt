package com.lightningkite.services.cache

import java.util.concurrent.ConcurrentHashMap

internal actual fun platformSpecificCacheSettings() {
    Cache.Settings.register("ram") { url, module -> MapCache(ConcurrentHashMap(), module) }
}