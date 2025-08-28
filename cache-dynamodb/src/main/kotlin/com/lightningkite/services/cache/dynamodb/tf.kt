package com.lightningkite.services.cache.dynamodb

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive


@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.awsDynamoDb(
): Unit {
    emitter.fulfillSetting(name, JsonPrimitive("dynamodb://${emitter.applicationRegion}/${emitter.projectPrefix.replace('-', '_')}"))
}