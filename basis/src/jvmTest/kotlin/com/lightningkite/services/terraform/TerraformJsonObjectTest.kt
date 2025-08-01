package com.lightningkite.services.terraform

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class TerraformJsonObjectTest {
    @Test
    fun testSpike() {
        TerraformJsonObject().apply {
            "resource.aws_instance.example2" {
//                "a" - 1
            }
            "resource.aws_instance.example" {
                "other" - listOf(JsonPrimitive(1))
                "provider" - listOf(
                    terraformJsonObject {
                        "local-exec" {
                            "command" - "echo ${'$'}{self.private_ip} > ip.txt"
                        }
                    },
                    terraformJsonObject {
                        "file" {
                            "source" - "example.txt"
                            "destination" - "/tmp/example.txt"
                        }
                    },
                    terraformJsonObject {
                        "remote-exec" {
                            "inline" - listOf("sudo install-something -f /tmp/example.txt")
                        }
                    },
                )
            }
            println(toString())
        }.toJsonObject().toString().let(::println)
    }
    @Test
    fun test() {
        TerraformJsonObject().apply {
            "resource.aws_instance.example" {
                "instance_type" - "m5.large"
                "amis" {
                    "us-east-2" - "ami-00000000000000000"
                }
                "tags" {
                    "Name" - "example_instance"
                }
            }
        }.toJsonObject().toString().let(::println)
        TerraformJsonObject().apply {
            "resource.aws_instance.example" {
                "instance_type" - "t2.micro"
                "ami" - "ami-abc123"
                "other" - listOf(JsonPrimitive(1))
                "provider" - listOf(
                    terraformJsonObject {
                        "local-exec" {
                            "command" - "echo ${'$'}{self.private_ip} > ip.txt"
                        }
                    },
                    terraformJsonObject {
                        "file" {
                            "source" - "example.txt"
                            "destination" - "/tmp/example.txt"
                        }
                    },
                    terraformJsonObject {
                        "remote-exec" {
                            "inline" - listOf("sudo install-something -f /tmp/example.txt")
                        }
                    },
                )
            }
            "resource.aws_instance.example2" {
                "instance_type" - "t2.micro"
                "ami" - "ami-abc123"
                "provisioner.local-exec" {
                    "command" - "echo ${'$'}{self.private_ip} > ip.txt"
                }
                "provisioner.file" {
                    "source" - "example.txt"
                    "destination" - "/tmp/example.txt"
                }
                "provisioner.remote-exec" {
                    "inline" - listOf("sudo install-something -f /tmp/example.txt")
                }
            }
        }.toJsonObject().toString().let(::println)
    }
}