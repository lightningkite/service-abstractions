package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.test.assertPlannableAws
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Terraform integration tests for AWS WebSocket PubSub.
 *
 * These tests verify that the Terraform configuration can be planned
 * (and optionally applied) using the 'lk' AWS profile.
 *
 * To run the full integration test with actual AWS resources:
 * ```
 * RUN_EXPENSIVE_TESTS=true ./gradlew :pubsub-aws:test
 * ```
 */
@Ignore
class TfTest {

    init {
        // Ensure the companion object is initialized (registers URL schemes)
        AwsWebSocketPubSub
    }

    @Test
    fun `terraform plan for aws websocket pubsub`() {
        assertPlannableAws<PubSub.Settings>(
            name = "pubsub-ws",
            fulfill = {
                it.awsApiGatewayWebSocket()
            }
        )
    }

    @Test
    fun `terraform plan with custom settings`() {
        assertPlannableAws<PubSub.Settings>(
            name = "pubsub-ws-custom",
            fulfill = {
                it.awsApiGatewayWebSocket(
                    lambdaMemoryMb = 512,
                    lambdaTimeoutSeconds = 60,
                    logRetentionDays = 7
                )
            }
        )
    }
}
