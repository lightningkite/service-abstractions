package com.lightningkite.services.cache.memcached

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertPlannableAwsVpc
import com.lightningkite.services.test.expensive
import kotlin.test.Test

class TfTest {
    init {
        MemcachedCache
    }
    @Test fun test() {
        assertPlannableAwsVpc<Cache.Settings>("aws") {
            it.awsElasticacheMemcached()
        }
    }
    @Test fun testServerless() {
        assertPlannableAwsVpc<Cache.Settings>("aws-sls") {
            it.awsElasticacheMemcachedServerless()
        }
    }
}