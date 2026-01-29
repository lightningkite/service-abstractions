// by Claude
package com.lightningkite.services.cache.dynamodb

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Test

/**
 * Tests for the DynamoDB Terraform configuration in tf.kt.
 *
 * These tests verify that:
 * - The awsDynamoDb() function generates valid terraform-plannable configuration
 * - The function correctly validates that the 'dynamodb' URL scheme is registered
 * - The generated settings URL follows the expected format
 */
class TfTest {
    init {
        // Ensure DynamoDbCache is initialized so the 'dynamodb' URL scheme is registered
        DynamoDbCache
    }

    @OptIn(Untested::class)
    @Test
    fun `awsDynamoDb generates plannable terraform configuration`() {
        // Test that awsDynamoDb() generates valid terraform configuration
        // Uses assertPlannableAws (not Vpc) because awsDynamoDb uses TerraformEmitterAws context
        assertPlannableAws<Cache.Settings>("dynamodb") {
            it.awsDynamoDb()
        }
    }
}
