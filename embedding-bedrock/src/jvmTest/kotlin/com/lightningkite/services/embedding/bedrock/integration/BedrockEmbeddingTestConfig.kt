package com.lightningkite.services.embedding.bedrock.integration

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.bedrock.BedrockEmbeddingService
import com.lightningkite.services.embedding.bedrock.BedrockEmbeddingSettings
import com.lightningkite.services.embedding.bedrock.AwsCredentials
import java.io.File
import java.util.Properties

/**
 * Shared configuration for the `:embedding-bedrock` live integration tests.
 *
 * Tests hit the real Bedrock API and cost real tokens -- gated on `AWS_ACCESS_KEY_ID`.
 * When absent, every test skips via [servicePresent]=false.
 */
internal object BedrockEmbeddingTestConfig {

    init {
        BedrockEmbeddingSettings.ensureRegistered()
    }

    /** Cheapest Bedrock embedding model for test runs. */
    val defaultModel: EmbeddingModelId = EmbeddingModelId("amazon.titan-embed-text-v2:0")

    val servicePresent: Boolean get() = accessKeyId != null && secretAccessKey != null

    private val accessKeyId: String? by lazy {
        System.getenv("AWS_ACCESS_KEY_ID") ?: loadFromLocalProperties("AWS_ACCESS_KEY_ID")
    }
    private val secretAccessKey: String? by lazy {
        System.getenv("AWS_SECRET_ACCESS_KEY") ?: loadFromLocalProperties("AWS_SECRET_ACCESS_KEY")
    }
    private val sessionToken: String? by lazy {
        System.getenv("AWS_SESSION_TOKEN") ?: loadFromLocalProperties("AWS_SESSION_TOKEN")
    }
    private val region: String by lazy {
        System.getenv("AWS_REGION") ?: loadFromLocalProperties("AWS_REGION") ?: "us-east-1"
    }

    private val context = TestSettingContext()

    val service: EmbeddingService by lazy {
        val key = accessKeyId ?: error("AWS_ACCESS_KEY_ID not set; tests should check servicePresent first")
        val secret = secretAccessKey ?: error("AWS_SECRET_ACCESS_KEY not set")
        BedrockEmbeddingService(
            name = "bedrock-embedding-test",
            context = context,
            region = region,
            credentials = AwsCredentials(key, secret, sessionToken),
        )
    }

    val invalidCredentialsService: EmbeddingService by lazy {
        BedrockEmbeddingService(
            name = "bedrock-embedding-test-bad-key",
            context = context,
            region = region,
            credentials = AwsCredentials(
                accessKeyId = "AKIAIOSFODNN7INVALID",
                secretAccessKey = "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",
            ),
        )
    }

    private fun loadFromLocalProperties(key: String): String? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val file = File(dir, "local.properties")
            if (file.exists()) {
                val props = Properties()
                file.inputStream().use { props.load(it) }
                props.getProperty(key)?.let { return it }
            }
            dir = dir.parentFile
        }
        return null
    }
}
