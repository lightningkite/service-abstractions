package com.lightningkite.services.pubsub.mqtt.paho.aws

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Manual deployment test for AWS IoT Core.
 *
 * Run this test manually to deploy AWS IoT infrastructure.
 * It's marked with @Ignore so it doesn't run automatically.
 *
 * To run: ./gradlew :pubsub-mqtt-paho:test --tests "DeployForTesting"
 */
class DeployForTesting {

    companion object {
        private val terraformDir = File("pubsub-mqtt-paho/terraform/aws-iot").absoluteFile
        val configFile = File("pubsub-mqtt-paho/local/aws-iot-config.json").absoluteFile
    }

    @Test
    @Ignore("Manual deployment only - run explicitly when needed")
    fun deployAwsIotCore() {
        require(terraformDir.exists()) {
            "Terraform directory not found: ${terraformDir.absolutePath}"
        }

        println("Deploying AWS IoT Core infrastructure...")
        println("Terraform directory: ${terraformDir.absolutePath}")
        println("AWS Profile: lk")

        // Run terraform init
        println("\n=== Running terraform init ===")
        val initResult = ProcessBuilder("terraform", "init")
            .directory(terraformDir)
            .apply {
                environment()["AWS_PROFILE"] = "lk"
            }
            .inheritIO()
            .start()
            .waitFor()

        require(initResult == 0) { "terraform init failed with exit code $initResult" }

        // Run terraform apply
        println("\n=== Running terraform apply ===")
        val applyResult = ProcessBuilder("terraform", "apply", "-auto-approve", "-var", "prefix=mqtt-test")
            .directory(terraformDir)
            .apply {
                environment()["AWS_PROFILE"] = "lk"
            }
            .inheritIO()
            .start()
            .waitFor()

        require(applyResult == 0) { "terraform apply failed with exit code $applyResult" }

        // Extract outputs
        println("\n=== Extracting terraform outputs ===")
        val outputProcess = ProcessBuilder("terraform", "output", "-json")
            .directory(terraformDir)
            .apply {
                environment()["AWS_PROFILE"] = "lk"
            }
            .start()

        val outputJson = outputProcess.inputStream.bufferedReader().readText()
        outputProcess.waitFor()

        val outputs = Json.parseToJsonElement(outputJson).jsonObject

        val endpoint = outputs["iot_endpoint"]?.jsonObject?.get("value")
            ?.jsonPrimitive?.content
            ?: error("Could not extract iot_endpoint from terraform output")

        val thingName = outputs["thing_name"]?.jsonObject?.get("value")
            ?.jsonPrimitive?.content
            ?: error("Could not extract thing_name from terraform output")

        // Certificate files are in the terraform directory
        val certFile = File(terraformDir, "certificates/certificate.pem.crt").absolutePath
        val keyFile = File(terraformDir, "certificates/private.pkcs8.key").absolutePath  // Use PKCS#8 format for Java
        val caFile = File(terraformDir, "certificates/AmazonRootCA1.pem").absolutePath

        // Create config object
        val config = AwsIotCoreIntegrationTest.Companion.AwsIotConfig(
            endpoint = endpoint,
            thingName = thingName,
            certFile = certFile,
            keyFile = keyFile,
            caFile = caFile
        )

        // Save config to local file
        configFile.parentFile.mkdirs()
        configFile.writeText(Json { prettyPrint = true }.encodeToString(config))

        println("\n=== Deployment Complete ===")
        println("Configuration saved to: ${configFile.absolutePath}")
        println("\nConnection details:")
        println("  Endpoint: ${config.endpoint}:8883")
        println("  Thing Name: ${config.thingName}")
        println("  Certificate: ${config.certFile}")
        println("  Private Key: ${config.keyFile}")
        println("  Root CA: ${config.caFile}")
        println("\nConnection URL:")
        println("  ${config.toUrl()}")
        println("\nYou can now run AwsIotCoreIntegrationTest")
    }

    @Test
    @Ignore("Manual teardown only - run explicitly when needed")
    fun destroyAwsIotCore() {
        println("Destroying AWS IoT Core infrastructure...")

        val destroyResult = ProcessBuilder("terraform", "destroy", "-auto-approve")
            .directory(terraformDir)
            .apply {
                environment()["AWS_PROFILE"] = "lk"
            }
            .inheritIO()
            .start()
            .waitFor()

        require(destroyResult == 0) { "terraform destroy failed with exit code $destroyResult" }

        // Clean up config file
        if (configFile.exists()) {
            configFile.delete()
            println("Removed config file: ${configFile.absolutePath}")
        }

        println("Destruction complete!")
    }
}
