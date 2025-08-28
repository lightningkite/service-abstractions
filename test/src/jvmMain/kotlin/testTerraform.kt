package com.lightningkite.services.test

import com.lightningkite.services.terraform.AwsPolicyStatement
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    override val policyStatements = ArrayList<AwsPolicyStatement>()

    override val projectPrefix: String get() = "test-project"
    override val deploymentTag: String get() = "test-project"

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
                "resource.aws_iam_policy.$name" {
                    "name" - "${projectPrefix}-${name}-policy"
                    "description" - "Allows sending of e-mails via Simple Email Service"
                    "policy" - Json.encodeToString(buildJsonObject {
                        put("Version", "2012-10-17")
                        put("Statement", Json.encodeToJsonElement(policyStatements.toList()))
                    })
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

    fun write() {
        finish()
        for ((name, content) in files.entries) {
            root.resolve("$name.tf.json").writeText(prettyJson.encodeToString(content.toJsonObject()))
        }
        println(root)
    }

    fun plan(): Plan {
        write()
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

private fun TerraformEmitterAws.vpc(cidr: String = "10.0.0.0/16"): TerraformAwsVpcInfo {
    emit("cloud") {
        "module.vpc" {
            "source" - "terraform-aws-modules/vpc/aws"
            "version" - "4.0.2"

            "name" - "$projectPrefix"
            "cidr" - cidr

            "azs" - listOf("us-west-2a", "us-west-2b", "us-west-2c")
            "private_subnets" - listOf("10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24")
            "public_subnets" - listOf("10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24")

            "enable_nat_gateway" - true
            "single_nat_gateway" - true
            "enable_vpn_gateway" - false
            "enable_dns_hostnames" - true
            "enable_dns_support" - true
        }
        "resource.aws_security_group.internal" {
            "name" - "$projectPrefix-private"
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
        id = "module.vpc.vpc_id",
        securityGroup = "aws_security_group.internal.id",
        privateSubnets = "module.vpc.private_subnets",
        publicSubnets = "module.vpc.public_subnets",
        applicationSubnets = "module.vpc.public_subnets",
//        applicationSubnets = "module.vpc.private_subnets",
        natGatewayIps = "module.vpc.nat_public_ips",
        cidr = cidr
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

fun TerraformEmitterAwsVpc.bastion(
    ubuntu_version: String = "24.04",
    arch: String = "amd64",
    virtualization_type: String = "hvm",
    volume_type: String = "ebs-gp3",
) {
    @Serializable data class Admin(
        val username: String = "jivie",
        val email: String = "joseph@lightningkite.com",
        val name: String = "Joseph Ivie",
        val site: String = "lightningkite.com",
        val phone1: String = "8013693729",
        val phone2: String = "",
        val keys: List<String> = listOf(
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDpQPSzK//RcMqYjkSdvjByZCjMNHR4A5LNFhZ9K1AblyX7TH2XEYVVvGgDzZ49+aal7UdugOgnIBWfXZZ4dlUAmfnePJfHqZ5Sapj13YrfGWLkXuie6e8hkj3V5FY5TVHIGxq0qe/rA20Ur8yRAwJSMnDOun/gq4+GjVQrtKRx0zAzDNH3QvJS79bcBYSKf7BodAXcUE2JY0JipBDlm321dJBWoFMEI1rOYJElF2fKMpZ0Y7eAxoL2MisI1addD3Vo8J+RGqrA2zJbuccDXZt+5R5ej8AOPYShhuFVfQRTG0N8AxSn3WtNg7Xj8wRvtMgPRtTAplNnoU2nUl0p3jjgGQU5/NawQkUR3B+gkv3FBZSxHJ1AnJzqOizBmfIHwm2dMDXeASUri9JJKzPvn9o1QzeM22UkBt7Oo81Ie4c4mwgTaieEo7oxHLQeyl01gNkGBADg35RgzdBqBvQzJDXEkIFhD6AD/tbpCns8iP63J0jRGQ4xxAzRi1BCnOhphnc= joseph@joseph-ThinkPad-E15"
        )
    )
    require(TerraformProviderImport.tls)
    require(TerraformProviderImport.ssh)
    emit("bastion") {
        val admins = listOf(Admin())

        val instanceProfile = if(policyStatements.isNotEmpty()) {
            "resource.aws_iam_role.bastion_exec" {
                "name" - "$projectPrefix-bastion-exec"
                "assume_role_policy" - Json.encodeToString(buildJsonObject {
                    put("Version", "2012-10-17")
                    put("Statement", Json.encodeToJsonElement(policyStatements.toList()))
                })
            }
            "resource.aws_iam_role.bastion_instance_profile" {
                "name" - "$projectPrefix-bastion_instance_profile"
                "assume_role_policy" - Json.encodeToString(buildJsonObject {
                    put("Version", "2012-10-17")
                    put(
                        "Statement", Json.encodeToJsonElement(
                            listOfNotNull(
                        AwsPolicyStatement(
                        action = listOf("sts:AssumeRole"),
                        resource = listOf(expression("aws_iam_role.bastion_exec.arn")),
                        principal = buildJsonObject {
                            put("service", "ec2.amazonaws.com")
                        }
                    ))))
                })
            }
            "resource.aws_iam_instance_profile.bastion_instance_profile" {
                "name" - "$projectPrefix-bastion_instance_profile"
                "role" - expression("aws_iam_role.bastion_instance_profile.name")
            }
            expression("aws_iam_instance_profile.bastion_instance_profile.name")
        } else null
        "data.aws_ssm_parameter.bastion_ubuntu" {
            "name" - "/aws/service/canonical/ubuntu/server/${ubuntu_version}/stable/current/${arch}/${virtualization_type}/${volume_type}/ami-id"
        }
        "resource.tls_private_key.bastion" {
            "algorithm" - "RSA"
            "rsa_bits" - 4096
        }
        "resource.aws_key_pair.bastion" {
            "key_name" - "${projectPrefix}-bastion-key"
            "public_key" - expression("tls_private_key.bastion.public_key_openssh")
        }
        "resource.aws_security_group.bastion" {
            "name" - "${projectPrefix}-bastion"
            "description" - "The rules for the server"
            "vpc_id" - expression(applicationVpc.id)

            "tags" {
                "Name" - "${projectPrefix}-bastion"
            }
        }
        "resource.aws_vpc_security_group_ingress_rule.bastion_allow_http" {
            "security_group_id" - expression("aws_security_group.bastion.id")
            "cidr_ipv4" - "0.0.0.0/0"
            "from_port" - 80
            "ip_protocol" - "tcp"
            "to_port" - 80
        }
        "resource.aws_vpc_security_group_ingress_rule.bastion_allow_https" {
            "security_group_id" - expression("aws_security_group.bastion.id")
            "cidr_ipv4" - "0.0.0.0/0"
            "from_port" - 443
            "ip_protocol" - "tcp"
            "to_port" - 443
        }
        "resource.aws_vpc_security_group_ingress_rule.allow_tls_ipv4" {
            "security_group_id" - expression("aws_security_group.bastion.id")
            "cidr_ipv4" - "75.148.99.49/32"
            "from_port"-22
            "ip_protocol" - "tcp"
            "to_port" - 22
        }
        "resource.aws_vpc_security_group_egress_rule.allow_tls_ipv4" {
            "security_group_id" - expression("aws_security_group.bastion.id")
            "cidr_ipv4" - "0.0.0.0/0"
            "ip_protocol" - "-1"
        }

        "resource.aws_instance.bastion" {
            "ami" - expression("data.aws_ssm_parameter.bastion_ubuntu.value")
            "instance_type" - "t3.micro"
            instanceProfile?.let { "iam_instance_profile" - it }
            "key_name" - expression("aws_key_pair.bastion.key_name")

            "vpc_security_group_ids" - listOf(expression(applicationVpc.securityGroup), expression("aws_security_group.bastion.id"))
            "subnet_id" - expression("element(${applicationVpc.publicSubnets}, 0)")

            "tags" {
                "Name" - "$projectPrefix-single-ec2"
            }
        }

        "output.root_access_key" {
            "value" - expression("resource.tls_private_key.bastion.private_key_pem")
            "sensitive" - true
        }

//        "resource.ssh_resource.bastion_install_resources" {
//            "depends_on" - listOf("aws_instance.bastion")
//            "host" - expression("aws_eip.bastion.public_ip")
//            "triggers" - {
//                "instanceid" - expression("aws_instance.bastion.id")
//                "admins" - expression("jsonencode(var.admins)")
//            }
//            "user" - "ubuntu"
//            "password" - ""
//            "private_key" - expression("tls_private_key.bastion.private_key_openssh")
//            "commands" - listOf(
//                "sudo apt update -y",
//                "sudo apt upgrade -y",
//                "sudo apt install -y build-essential vim git net-tools whois openjdk-17-jdk ca-certificates curl supervisor libssl-dev pkg-config unzip nginx certbot python3-certbot-nginx rustup",
//            )
//            "timeout" - "5m"
//        }

        "resource.aws_eip.bastion" {
            "instance" - expression("aws_instance.bastion.id")
        }

        "resource.ssh_resource.bastion_users" {
            "depends_on" - listOf("aws_instance.bastion")
            "host" - expression("aws_eip.bastion.public_ip")
            "triggers" {
                "instanceid" - expression("aws_instance.bastion.id")
            }
            "user" - "ubuntu"
            "password" - ""
            "private_key" - expression("tls_private_key.bastion.private_key_openssh")
            "commands" - admins.flatMap { x ->
                listOf(
                    "sudo adduser ${x.username} --gecos \"${x.name},${x.site},${x.phone1},${x.phone2},${x.email}\" || true",
                    "echo \"${x.username}:changeme\" | sudo chpasswd ${x.username}",
                    "sudo mkdir -p /home/${x.username}/.ssh",
                    "printf \"${x.keys.joinToString("\n")}\n\" | sudo tee /home/${x.username}/.ssh/authorized_keys",
                    "sudo chmod 755 /home/${x.username}/.ssh",
                    "sudo chmod 664 /home/${x.username}/.ssh/authorized_keys",
                    "sudo chown ${x.username}:${x.username} /home/${x.username}/.ssh -R",
                    "sudo adduser ${x.username} sudo",
                )
            }
            "timeout" - "20s"
        }
    }
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