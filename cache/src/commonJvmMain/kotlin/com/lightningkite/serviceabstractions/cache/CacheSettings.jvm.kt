package com.lightningkite.serviceabstractions.cache

import java.util.concurrent.ConcurrentHashMap

internal actual fun platformSpecificCacheSettings() {
    Cache.Settings.register("ram") { url, module -> MapCache(ConcurrentHashMap(), module) }
}