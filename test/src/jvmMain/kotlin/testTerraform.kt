package com.lightningkite.services.test

import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.terraform.TerraformAwsVpcInfo
import com.lightningkite.services.terraform.TerraformCloudInfo
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.include
import com.lightningkite.services.terraform.terraformJsonObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

private val prettyJson = Json { prettyPrint = true }

fun <T> assertPlannableAws(
    domain: Boolean? = null,
    vpc: Boolean? = null,
    fulfill: (TerraformNeed<T>) -> TerraformServiceResult<T>
) {
    (domain?.let(::listOf) ?: listOf(true, false)).forEach { domain ->
        (vpc?.let(::listOf) ?: listOf(true, false)).forEach { vpc ->
            assertPlannableAwsSpecific(domain = domain, vpc = vpc, fulfill = fulfill)
        }
    }
}

data class TerraformServicePlan<T>(
    val result: TerraformServiceResult<T>,
    val root: File,
    val plan: File
)

fun <T> assertPlannableAwsSpecific(
    name: String = "anon",
    domain: Boolean,
    vpc: Boolean,
    fulfill: (TerraformNeed<T>) -> TerraformServiceResult<T>
): TerraformServicePlan<T> {
    val aws = TerraformProvider(
        TerraformProviderImport.aws,
        alias = null,
        out = buildJsonObject {
            put("region", "us-west-2")
        }
    )

    val vpcDef = terraformJsonObject {
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
            "from_port" - 0
            "to_port" - 0
            "ip_protocol" - -1
        }
        "resource.aws_vpc_security_group_egress_rule.freeInternal" {
            "for_each" - expression("toset(module.vpc.private_subnets_cidr_blocks)")
            "security_group_id" - expression("aws_security_group.internal.id")
            "cidr_ipv4" - expression("each.key")
            "from_port" - 0
            "to_port" - 0
            "ip_protocol" - -1
        }
    }
    val vpcInfo = TerraformAwsVpcInfo(
        idExpression = TerraformJsonObject.expression("module.vpc.vpc_id"),
        privateSubnetsExpression = TerraformJsonObject.expression("module.vpc.private_subnets"),
        securityGroupExpression = TerraformJsonObject.expression("aws_security_group.internal.id"),
        natGatewayIpExpression = TerraformJsonObject.expression("module.vpc.nat_public_ips"),
    )

    val domainDef = terraformJsonObject {
        "data.aws_route53_zone.main" {
            "name" - "cs.lightningkite.com"
        }
    }

    val info = TerraformCloudInfo(
        projectPrefix = "testproject",
        domain = "test.cs.lightningkite.com".takeIf { domain },
        domainZoneId = TerraformJsonObject.expression("data.aws_route53_zone.main.zone_id").takeIf { domain },
        deploymentTag = "test-tag",
        applicationProvider = aws,
        applicationVpc = vpcInfo.takeIf { vpc },
    )
    val result = TerraformNeed<T>("test", info).let(fulfill)
    val root = File("build/test/$name")
//    root.deleteRecursively()
    root.mkdirs()
    root.resolve("main.tf.json").writeText(
        terraformJsonObject {
            "terraform" {
                "required_providers" - result.requireProviders.map { it.toTerraformJson() }
                "required_version" - "~> 1.0"
                "backend.local" {
                    "path" - "./build/terraform.tfstate"
                }
            }
            "output.result" {
                "value" - result.setting
                "sensitive" - true
            }
            include(result.nonStandardProviders + aws)
            if (vpc) include(vpcDef)
            if (domain) include(domainDef)
        }.let { prettyJson.encodeToString(it) }
    )
    root.resolve("active.tf.json").writeText(prettyJson.encodeToString(result.content))
    println(root)
    root.runTerraform("init", "-input=false", "-no-color")
    root.runTerraform("plan", "-input=false", "-no-color", "-out=plan.tfplan")
    return TerraformServicePlan(
        result, root, root.resolve("plan.tfplan")
    )
}

private fun File.runTerraform(vararg args: String): String {
    val tempOut = File.createTempFile("out", ".out")
    val result = ProcessBuilder("terraform", *args)
        .directory(this)
        .also { it.environment()["AWS_PROFILE"] = "lk" }
        .inheritIO()
        .redirectOutput(tempOut)
        .start()
        .waitFor()
    val text = tempOut.readText()
    println(text)
    if(result != 0) {
        throw Exception("Terraform exited with result $result")
    }
    return text
}

fun <T: Setting<R>, R> withAwsSpecific(
    name: String,
    serializer: KSerializer<T>,
    domain: Boolean,
    vpc: Boolean,
    settingContext: SettingContext = TestSettingContext(),
    fulfill: (TerraformNeed<T>) -> TerraformServiceResult<T>,
    test: (R)->Unit
) {
    expensive {
        val basis = assertPlannableAwsSpecific(name, domain, vpc, fulfill)
        basis.root.runTerraform("apply", "plan.tfplan", "-no-color")
        val setting = basis.root.runTerraform("output", "-json", "-no-color")
            .let { Json.parseToJsonElement(it) }
            .jsonObject["result"]!!
            .jsonObject["value"]!!
            .let { Json.decodeFromJsonElement(serializer, it) }
        val resource = setting("test", settingContext)
        test(resource)
        basis.root.runTerraform("destroy", "--auto-approve", "-no-color")
    }
}

inline fun expensive(run: ()->Unit) {
    // Check for environment variable
    if(System.getenv("RUN_EXPENSIVE_TESTS") == "true") run()
    else println("Skipped.")
}