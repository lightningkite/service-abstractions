package com.lightningkite.services.ai.bedrock.awssdk

import com.lightningkite.services.ai.LlmAccess
import kotlinx.coroutines.test.runTest
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the SDK->library credential mapping without touching real AWS: a
 * [StaticCredentialsProvider] stands in for the default chain, so these run in CI with no
 * credentials configured. The end-to-end path (real profile/SSO signing a Bedrock call) is
 * exercised by the integration suites in `:ai-bedrock` once a provider is supplied.
 */
class AwsSdkCredentialsTest {

    @Test
    fun mapsBasicCredentials() = runTest {
        val provider = awsSdkCredentials(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("AKID", "SECRET")),
        )
        val creds = provider.resolve()
        assertEquals("AKID", creds.accessKeyId)
        assertEquals("SECRET", creds.secretAccessKey)
        assertNull(creds.sessionToken, "Basic credentials carry no session token")
    }

    @Test
    fun mapsSessionCredentials() = runTest {
        val provider = awsSdkCredentials(
            StaticCredentialsProvider.create(
                AwsSessionCredentials.create("AKID", "SECRET", "TOKEN"),
            ),
        )
        val creds = provider.resolve()
        assertEquals("AKID", creds.accessKeyId)
        assertEquals("SECRET", creds.secretAccessKey)
        assertEquals("TOKEN", creds.sessionToken, "Temporary credentials must forward the session token")
    }

    @Test
    fun builderProducesUrlAndRegistersScheme() {
        val settings = LlmAccess.Settings.bedrockSdk(
            modelId = "anthropic.claude-3-5-haiku-20241022-v1:0",
            region = "us-west-2",
            profile = "lk",
        )
        assertEquals(
            "bedrock-sdk://anthropic.claude-3-5-haiku-20241022-v1:0?region=us-west-2&profile=lk",
            settings.url,
        )
        assertTrue(LlmAccess.Settings.supports("bedrock-sdk"), "Building settings must register the scheme")
    }
}
