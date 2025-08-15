package com.lightningkite.services.cache.memcached

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformCloudInfo
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

class TfTest {
    @Test fun test() {
        assertPlannableAws<Cache>(vpc = true) {
            it.awsElasticacheMemcached()
        }
    }
}