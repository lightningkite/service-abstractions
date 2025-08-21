package com.lightningkite.services.test

import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.terraform.TerraformAwsVpcInfo
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformEmitterAwsDomain
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.include
import com.lightningkite.services.terraform.terraformJsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer
import java.io.File

private val prettyJson = Json { prettyPrint = true }

open class TerraformEmitterAwsTest<S>(
    val root: File,
    val targetSetting: String = "Test",
    val serializer: KSerializer<S>,
) : TerraformEmitter, TerraformEmitterAws {
    init {
        root.mkdirs()
    }

    data class PolicyStatement(
        val actions: List<String>,
        val effect: String,
        val resources: List<String>,
    ) {

    }

    val policyStatements = ArrayList<PolicyStatement>()
    override fun addApplicationPolicyStatement(
        actions: List<String>,
        effect: String,
        resources: List<String>
    ) {
        policyStatements += PolicyStatement(actions, effect, resources)
    }

    override val projectPrefix: String get() = "test-project"
    override val deploymentTag: String get() = "test-project"
    override val domain: String? get() = null

    val imports = HashSet<TerraformProviderImport>()
    val providers = HashSet<TerraformProvider>()
    override fun require(provider: TerraformProviderImport) {
        imports += provider
    }

    override fun require(provider: TerraformProvider) {
        providers += provider
    }

    val files = mutableMapOf<String, TerraformJsonObject>()
    val settings = mutableMapOf<String, JsonElement>()

    override fun emit(
        context: String?,
        action: TerraformJsonObject.() -> Unit
    ) {
        files.getOrPut(context ?: "unclassified") { TerraformJsonObject() }.action()
    }

    override fun fulfillSetting(settingName: String, element: JsonElement) {
        settings[settingName] = element
    }

    override val applicationRegion: String
        get() = "us-west-2"

    init {
        require(
            TerraformProvider(
            TerraformProviderImport.aws,
            alias = null,
            out = buildJsonObject {
                put("region", applicationRegion)
            }
        ))
    }

    fun finish() {
        emit("main") {
            "terraform" {
                "required_providers" {
                    imports
                        .plus(TerraformProviderImport.aws)
                        .distinct()
                        .map { it.toTerraformJson() }
                        .forEach { include(it) }
                }
                "required_version" - "~> 1.0"
                "backend.local" {
                    "path" - "./build/terraform.tfstate"
                }
            }
            "output.result" {
                "value" - settings[targetSetting]!!
                "sensitive" - true
            }
            include(providers)
        }
        if (policyStatements.isNotEmpty()) {
            emit("testuser") {
                val name = "testuser"
                "resource.aws_iam_user.$name" {
                    "name" - "${projectPrefix}-${name}-user"
                }
                "resource.aws_iam_access_key.$name" {
                    "user" - expression("aws_iam_user.$name.name")
                }
                "data.aws_iam_policy_document.$name" {
                    "statement" - policyStatements.map {
                        buildJsonObject {
                            put("actions", JsonArray(it.actions.map(::JsonPrimitive)))
                            put("effect", it.effect)
                            put("resources", JsonArray(it.resources.map(::JsonPrimitive)))
                        }
                    }
                }
                "resource.aws_iam_policy.$name" {
                    "name" - "${projectPrefix}-${name}-policy"
                    "description" - "Allows sending of e-mails via Simple Email Service"
                    "policy" - expression("data.aws_iam_policy_document.$name.json")
                }
                "resource.aws_iam_user_policy_attachment.$name" {
                    "user" - expression("aws_iam_user.$name.name")
                    "policy_arn" - expression("aws_iam_policy.$name.arn")
                }
                "output.userKey" {
                    "value" - expression("resource.aws_iam_access_key.testuser.id")
                    "sensitive" - true
                }
                "output.userSecret" {
                    "value" - expression("resource.aws_iam_access_key.testuser.secret")
                    "sensitive" - true
                }
            }
        }
    }

    fun plan(): Plan {
        finish()
        for ((name, content) in files.entries) {
            root.resolve("$name.tf.json").writeText(prettyJson.encodeToString(content.toJsonObject()))
        }
        println(root)
        root.runTerraform("init", "-upgrade", "-input=false", "-no-color")
        root.runTerraform("plan", "-input=false", "-no-color", "-out=plan.tfplan")
        return Plan(root.resolve("plan.tfplan"))
    }

    inner class Plan(
        val plan: File
    ) {
        fun apply(): S {
            root.runTerraform("apply", "plan.tfplan", "-no-color")
            val setting = root.runTerraform("output", "-json", "-no-color")
                .let { Json.parseToJsonElement(it) }
                .jsonObject["result"]!!
                .jsonObject["value"]!!
                .let { Json.decodeFromJsonElement(serializer, it) }
            return setting
        }
    }

    fun destroy() {
        root.runTerraform("destroy", "--auto-approve", "-no-color")
    }
}

private fun TerraformEmitterAws.vpc(): TerraformAwsVpcInfo {
    emit("cloud") {
        "module.vpc" {
            "source" - "terraform-aws-modules/vpc/aws"
            "version" - "4.0.2"

            "name" - "terraform-ais-stage"
            "cidr" - "10.0.0.0/16"

            "azs" - listOf("us-west-2a", "us-west-2b", "us-west-2c")
            "private_subnets" - listOf("10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24")
            "public_subnets" - listOf("10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24")

            "enable_nat_gateway" - true
            "single_nat_gateway" - true
            "enable_vpn_gateway" - false
            "enable_dns_hostnames" - false
            "enable_dns_support" - true
        }
        "resource.aws_security_group.internal" {
            "name" - "terraform-ais-stage-private"
            "vpc_id" - expression("module.vpc.vpc_id")
        }
        "resource.aws_vpc_security_group_ingress_rule.freeInternal" {
            "for_each" - expression("toset(module.vpc.private_subnets_cidr_blocks)")
            "security_group_id" - expression("aws_security_group.internal.id")
            "cidr_ipv4" - expression("each.key")
            "ip_protocol" - -1
        }
        "resource.aws_vpc_security_group_egress_rule.freeInternal" {
            "for_each" - expression("toset(module.vpc.private_subnets_cidr_blocks)")
            "security_group_id" - expression("aws_security_group.internal.id")
            "cidr_ipv4" - expression("each.key")
            "ip_protocol" - -1
        }
    }
    return TerraformAwsVpcInfo(
        idExpression = TerraformJsonObject.expression("module.vpc.vpc_id"),
        securityGroupExpression = TerraformJsonObject.expression("aws_security_group.internal.id"),
        privateSubnetsExpression = TerraformJsonObject.expression("module.vpc.private_subnets"),
        natGatewayIpExpression = TerraformJsonObject.expression("module.vpc.nat_public_ips"),
    )
}

private fun TerraformEmitterAws.domain(): String {
    emit("cloud") {
        "data.aws_route53_zone.main" {
            "name" - "cs.lightningkite.com"
        }
    }
    return TerraformJsonObject.expression("data.aws_route53_zone.main.zone_id")
}

class TerraformEmitterAwsTestWithDomain<S>(root: File, targetSetting: String, serializer: KSerializer<S>) :
    TerraformEmitterAwsTest<S>(root, targetSetting, serializer), TerraformEmitterAwsDomain {
    override val domainZoneId: String = domain()
    override val domain: String get() = "test.cs.lightningkite.com"
}

class TerraformEmitterAwsTestWithDomainVpc<S>(root: File, targetSetting: String, serializer: KSerializer<S>) :
    TerraformEmitterAwsTest<S>(root, targetSetting, serializer), TerraformEmitterAwsDomain, TerraformEmitterAwsVpc {
    override val domainZoneId: String = domain()
    override val domain: String get() = "test.cs.lightningkite.com"
    override val applicationVpc: TerraformAwsVpcInfo = vpc()
}

class TerraformEmitterAwsTestWithVpc<S>(root: File, targetSetting: String, serializer: KSerializer<S>) :
    TerraformEmitterAwsTest<S>(root, targetSetting, serializer), TerraformEmitterAwsVpc {
    override val applicationVpc: TerraformAwsVpcInfo = vpc()
}

private fun File.runTerraform(vararg args: String): String {
    val tempOut = File.createTempFile("out", ".out")
    val result = ProcessBuilder("terraform", *args)
        .directory(this)
        .also { it.environment()["AWS_PROFILE"] = "lk" }
        .inheritIO()
        .redirectOutput(tempOut)
        .redirectError(tempOut)
        .start()
        .waitFor()
    val text = tempOut.readText()
    println(text)
    if (result != 0) {
        throw Exception("Terraform exited with result $result")
    }
    return text
}

inline fun <reified T> assertPlannableAws(
    name: String,
    fulfill: context(TerraformEmitterAws) (TerraformNeed<T>) -> Unit,
) {
    for (tester in listOf(
        TerraformEmitterAwsTest(File("build/test/$name"), "test", serializer<T>()),
        TerraformEmitterAwsTestWithVpc(File("build/test/$name-vpc"), "test", serializer<T>()),
        TerraformEmitterAwsTestWithDomain(File("build/test/$name-dom"), "test", serializer<T>()),
        TerraformEmitterAwsTestWithDomainVpc(File("build/test/$name-vpcdom"), "test", serializer<T>()),
    )) {
        with(tester) { fulfill(TerraformNeed("test")) }
        val plan = tester.plan()
//        expensive {
//            runBlocking { applyCheck(plan.apply()) }
//            tester.destroy()
//        }
    }
}

inline fun <reified T> assertPlannableAwsDomain(
    name: String,
    fulfill: context(TerraformEmitterAwsDomain) (TerraformNeed<T>) -> Unit,
) {
    for (tester in listOf(
        TerraformEmitterAwsTestWithDomain(File("build/test/$name"), "test", serializer<T>()),
        TerraformEmitterAwsTestWithDomainVpc(File("build/test/$name-vpc"), "test", serializer<T>()),
    )) {
        with(tester) { fulfill(TerraformNeed("test")) }
        val plan = tester.plan()
//        expensive {
//            runBlocking { applyCheck(plan.apply()) }
//            tester.destroy()
//        }
    }
}

inline fun <reified T> assertPlannableAwsVpc(
    name: String,
    fulfill: context(TerraformEmitterAwsVpc) (TerraformNeed<T>) -> Unit,
) {
    for (tester in listOf(
        TerraformEmitterAwsTestWithVpc(File("build/test/$name"), "test", serializer<T>()),
        TerraformEmitterAwsTestWithDomainVpc(File("build/test/$name-dom"), "test", serializer<T>()),
    )) {
        with(tester) { fulfill(TerraformNeed("test")) }
        val plan = tester.plan()
//        expensive {
//            runBlocking { applyCheck(plan.apply()) }
//            tester.destroy()
//        }
    }
}

inline fun expensive(run: () -> Unit) {
    // Check for environment variable
    if (System.getenv("RUN_EXPENSIVE_TESTS") == "true") run()
    else println("Skipped.")
}