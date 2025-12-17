package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.TerraformEmitterAwsTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

object DeployForTesting {
    /** Local file where the deployed PubSub URL is stored */
    val urlFile = File("pubsub-aws/local/deployed-url.json")

    @JvmStatic
    fun main(vararg args: String) {
        AwsWebSocketPubSub
        val t = TerraformEmitterAwsTest(File("pubsub-aws/terraform/integrationtests"), serializer = serializer<PubSub.Settings>())
        with(t) {
            TerraformNeed<PubSub.Settings>(targetSetting).awsApiGatewayWebSocket()
        }
//        t.destroy()
        val settings = t.plan().apply()

        // Save the URL to a local file for integration tests
        urlFile.parentFile.mkdirs()
        urlFile.writeText(Json.encodeToString(settings))
        println("Deployed URL saved to: ${urlFile.absolutePath}")
    }
}