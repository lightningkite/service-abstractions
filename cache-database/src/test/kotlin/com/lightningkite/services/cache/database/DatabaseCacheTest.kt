package com.lightningkite.services.cache.database

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.test.CacheTest
import com.lightningkite.services.database.InMemoryDatabase
import kotlin.test.Test

//class DatabaseCacheTest : CacheTest() {
//    override val cache: Cache = TestSettingContext().let { context ->
//        DatabaseCache(
//            "test",
//            context,
//            InMemoryDatabase("test-db", context = context)
//        )
//    }
//
//    @Test
//    fun start() {}
//}