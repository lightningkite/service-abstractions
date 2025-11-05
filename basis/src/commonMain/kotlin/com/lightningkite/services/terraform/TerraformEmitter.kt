package com.lightningkite.services.terraform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.KSerializer

/**
 * Interface for emitting Terraform configuration programmatically.
 *
 * `TerraformEmitter` collects infrastructure requirements from services and emits
 * corresponding Terraform JSON. Services can declare what cloud resources they need
 * (databases, storage, networks) and the emitter generates IaC to provision them.
 *
 * This enables:
 * - Infrastructure-from-code: Services declare their own infrastructure needs
 * - Environment consistency: Same service config across dev/staging/prod
 * - Automated provisioning: Generate Terraform from application configuration
 *
 * @property projectPrefix Prefix for all resource names (e.g., "myapp-prod-")
 * @property deploymentTag Tag/version identifier for this deployment
 */
public interface TerraformEmitter {
    public val projectPrefix: String
    public val deploymentTag: String

    /** Registers a required Terraform provider (e.g., AWS, Google Cloud). */
    public fun require(provider: TerraformProviderImport)
    /** Registers a configured provider instance. */
    public fun require(provider: TerraformProvider)
    /** Emits Terraform configuration into a named context. */
    public fun emit(context: String? = null, action: TerraformJsonObject.()->Unit)
    /** Records a configuration value that was determined/generated. */
    public fun fulfillSetting(settingName: String, element: JsonElement)
    /** Declares a variable that needs to be provided to Terraform. */
    public fun variable(need: TerraformNeed<*>)
}

public class TerraformAwsVpcInfo(
    public val id: String,
    public val securityGroup: String,
    public val privateSubnets: String,
    public val publicSubnets: String,
    public val applicationSubnets: String,
    public val natGatewayIps: String,
    public val cidr: String,
)

public interface TerraformEmitterAws: TerraformEmitter {
    public val applicationRegion: String
    public val policyStatements: MutableCollection<AwsPolicyStatement>
}

public interface TerraformEmitterKnownIpAddresses: TerraformEmitterAws  {
    public val applicationIpAddresses: String
}

public interface TerraformEmitterAwsVpc: TerraformEmitterAws, TerraformEmitterKnownIpAddresses  {
    public val applicationVpc: TerraformAwsVpcInfo
    override val applicationIpAddresses: String get() = applicationVpc.natGatewayIps
}

public interface TerraformEmitterAwsDomain: TerraformEmitterAws  {
    public val domainZoneId: String
    public val domain: String
}

// sample fulfillment

context(emitter: TerraformEmitter)
public inline fun <reified T> TerraformNeed<T>.direct(value: T): Unit = with(emitter) {
    fulfillSetting(name, Json { encodeDefaults = true }.encodeToJsonElement(value))
}

context(emitter: TerraformEmitter)
public inline fun <reified T> TerraformNeed<T>.byVariable(): Unit = with(emitter) {
    variable(this@byVariable)
    emit("variables") {
        "variable.$name" {}
    }
    fulfillSetting(name, JsonPrimitive(TerraformJsonObject.expression("var.$name")))
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
