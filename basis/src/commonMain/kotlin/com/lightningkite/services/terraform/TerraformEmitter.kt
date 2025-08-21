package com.lightningkite.services.terraform

import com.lightningkite.services.ExceptionReporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

public interface TerraformEmitter {
    public val projectPrefix: String
    public val deploymentTag: String
    public val domain: String?

    public fun require(provider: TerraformProviderImport)
    public fun require(provider: TerraformProvider)
    public fun emit(context: String? = null, action: TerraformJsonObject.()->Unit)
    public fun fulfillSetting(settingName: String, element: JsonElement)
}

public class TerraformAwsVpcInfo(
    public val idExpression: String,
    public val securityGroupExpression: String,
    public val privateSubnetsExpression: String,
    public val natGatewayIpExpression: String,
)

public interface TerraformEmitterAws: TerraformEmitter {
    public val applicationRegion: String
    public fun addApplicationPolicyStatement(
        actions: List<String>,
        effect: String = "Allow",
        resources: List<String>,
//        condition: Map<String, Map<String, String>>? = null,
    )
}

public interface TerraformEmitterAwsVpc: TerraformEmitterAws  {
    public val applicationVpc: TerraformAwsVpcInfo
    public val applicationIdExpression: String get() = applicationVpc.idExpression
    public val applicationSecurityGroupExpression: String get() = applicationVpc.securityGroupExpression
    public val applicationPrivateSubnetsExpression: String get() = applicationVpc.privateSubnetsExpression
    public val applicationNatGatewayIpExpression: String get() = applicationVpc.natGatewayIpExpression
}

public interface TerraformEmitterAwsDomain: TerraformEmitterAws  {
    public val domainZoneId: String
    override val domain: String
}

// sample fulfillment

context(emitter: TerraformEmitter)
public inline fun <reified T> TerraformNeed<T>.direct(value: T): Unit = with(emitter) {
    fulfillSetting(name, Json { encodeDefaults = true }.encodeToJsonElement(value))
}

context(emitter: TerraformEmitter)
public inline fun <reified T> TerraformNeed<T>.oldStyle(
    need: TerraformNeed<T>,
    setting: JsonElement,
    requireProviders: Collection<TerraformProviderImport> = emptyList(),
    providers: Collection<TerraformProvider> = emptyList(),
    crossinline content: TerraformJsonObject.()->Unit
) {
    emitter.fulfillSetting(name, setting)
    providers.forEach { emitter.require(it) }
    requireProviders.forEach { emitter.require(it) }
    emitter.emit(need.name) { content() }
}
context(emitter: TerraformEmitter)
public inline fun <reified T> TerraformNeed<T>.oldStyle(
    need: TerraformNeed<T>,
    setting: String,
    requireProviders: Collection<TerraformProviderImport> = emptyList(),
    providers: Collection<TerraformProvider> = emptyList(),
    crossinline content: TerraformJsonObject.()->Unit
) {
    emitter.fulfillSetting(name, JsonPrimitive(setting))
    providers.forEach { emitter.require(it) }
    requireProviders.forEach { emitter.require(it) }
    emitter.emit(need.name) { content() }
}