package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.inference
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live smoke test against real Bedrock.
 *
 * Skipped (not failed) when AWS credentials or region are missing, so CI without AWS access
 * still goes green. Run locally by exporting `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
 * and `AWS_REGION` (plus `AWS_SESSION_TOKEN` if using temporary credentials) before the test.
 *
 * Uses `anthropic.claude-3-5-haiku-20241022-v1:0` — cheapest Claude on Bedrock as of writing.
 */
class BedrockLiveTest {

    private fun credentialsAvailable(): Boolean =
        System.getenv("AWS_ACCESS_KEY_ID") != null &&
                System.getenv("AWS_SECRET_ACCESS_KEY") != null &&
                System.getenv("AWS_REGION") != null

    @Test fun streamSimpleText() = runTest {
        if (!credentialsAvailable()) {
            println("Skipping BedrockLiveTest — AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_REGION not set")
            return@runTest
        }
        val ctx = TestSettingContext()
        val access = BedrockLlmAccess(
            name = "test",
            context = ctx,
            region = System.getenv("AWS_REGION"),
            credentials = AwsCredentials(
                accessKeyId = System.getenv("AWS_ACCESS_KEY_ID"),
                secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY"),
                sessionToken = System.getenv("AWS_SESSION_TOKEN"),
            ),
        )
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("Say the single word 'pong' and nothing else."))),
            ),
            maxTokens = 20,
        )
        val events = access.stream(
            LlmModelId("anthropic.claude-3-5-haiku-20241022-v1:0"),
            prompt,
        ).toList()
        assertTrue(events.isNotEmpty(), "Bedrock should produce at least one stream event")
        assertTrue(
            events.any { it is LlmStreamEvent.Finished },
            "Stream should terminate with a Finished event",
        )
    }

    @Test fun inferenceConvenience() = runTest {
        if (!credentialsAvailable()) return@runTest
        val ctx = TestSettingContext()
        val access = BedrockLlmAccess(
            name = "test",
            context = ctx,
            region = System.getenv("AWS_REGION"),
            credentials = AwsCredentials(
                accessKeyId = System.getenv("AWS_ACCESS_KEY_ID"),
                secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY"),
                sessionToken = System.getenv("AWS_SESSION_TOKEN"),
            ),
        )
        val result = access.inference(
            LlmModelId("anthropic.claude-3-5-haiku-20241022-v1:0"),
            LlmPrompt(
                messages = listOf(
                    LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("Respond with the word pong."))),
                ),
                maxTokens = 20,
            ),
        )
        val text = result.message.content.filterIsInstance<LlmContent.Text>().joinToString("") { it.text }
        assertTrue(text.isNotBlank(), "Bedrock should return some text")
    }
}
