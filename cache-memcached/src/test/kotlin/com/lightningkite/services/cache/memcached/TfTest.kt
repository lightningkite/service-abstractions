package com.lightningkite.services.cache.memcached

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.test.assertPlannableAwsVpc
import kotlin.test.Test

@OptIn(Untested::class)
class TfTest {
    init {
        MemcachedCache
    }

    @Test
    fun test() {
        assertPlannableAwsVpc<Cache.Settings>("aws") {
            it.awsElasticacheMemcached()
        }
    }

    @Test
    fun testServerless() {
        assertPlannableAwsVpc<Cache.Settings>("aws-sls") {
            it.awsElasticacheMemcachedServerless()
        }
    }
}