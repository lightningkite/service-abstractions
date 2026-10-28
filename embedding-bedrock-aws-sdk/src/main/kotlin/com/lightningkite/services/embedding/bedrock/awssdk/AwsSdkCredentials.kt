package com.lightningkite.services.embedding.bedrock.awssdk

import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.bedrock.AwsCredentials
import com.lightningkite.services.embedding.bedrock.AwsCredentialsProvider
import com.lightningkite.services.embedding.bedrock.BedrockEmbeddingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider as SdkAwsCredentialsProvider

/**
 * Adapt an AWS SDK credential provider to [AwsCredentialsProvider], so [BedrockEmbeddingService] signs with whatever the
 * SDK resolves: environment variables, static and `aws login` profiles, `credential_process`, container roles, and EC2
 * instance metadata. SSO profiles additionally need `software.amazon.awssdk:sso` and `ssooidc` on the classpath, and
 * `role_arn` profiles need `software.amazon.awssdk:sts`; without them the SDK throws when resolving such a profile.
 *
 * The SDK caches credentials and refreshes them before they expire; this asks it on every request, which is cheap.
 * `resolveCredentials()` can block on files or the network, so it runs on [Dispatchers.IO].
 */
public fun awsSdkCredentials(
    provider: SdkAwsCredentialsProvider = DefaultCredentialsProvider.builder().build(),
): AwsCredentialsProvider = AwsCredentialsProvider {
    withContext(Dispatchers.IO) {
        val resolved = provider.resolveCredentials()
        AwsCredentials(
            accessKeyId = resolved.accessKeyId(),
            secretAccessKey = resolved.secretAccessKey(),
            sessionToken = (resolved as? AwsSessionCredentials)?.sessionToken(),
        )
    }
}

/**
 * Build [EmbeddingService.Settings] for a Bedrock embedding model whose credentials come from the AWS SDK.
 *
 * Produces `bedrock-sdk://<model-id>?region=<region>[&dimensions=<n>][&profile=<profile>]`.
 *
 * @param region AWS region; when null, `AWS_REGION` must be set.
 * @param dimensions vector length for models that offer a choice (Titan V2, Cohere Embed v4).
 * @param profile named profile from `~/.aws/config`; when null the SDK's default chain is used.
 */
public fun EmbeddingService.Settings.Companion.bedrockSdk(
    modelId: String,
    region: String? = null,
    dimensions: Int? = null,
    profile: String? = null,
): EmbeddingService.Settings {
    BedrockSdkEmbedding.ensureRegistered()
    val params = buildList {
        region?.let { add("region=$it") }
        dimensions?.let { add("dimensions=$it") }
        profile?.let { add("profile=$it") }
    }
    val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
    return EmbeddingService.Settings("bedrock-sdk://$modelId$query")
}

/**
 * Registers the `bedrock-sdk://` scheme on [EmbeddingService.Settings]: a [BedrockEmbeddingService] signing with
 * [awsSdkCredentials].
 *
 * URL shape: `bedrock-sdk://<model-id>?region=<region>[&dimensions=<n>][&profile=<profile>]`.
 */
public object BedrockSdkEmbedding {
    init {
        if (!EmbeddingService.Settings.supports("bedrock-sdk")) {
            EmbeddingService.Settings.register("bedrock-sdk") { name, url, context ->
                val params = url.substringAfter("?", "").split("&").filter { it.isNotEmpty() }
                    .associate { it.substringBefore("=") to it.substringAfter("=") }
                val modelId = url.substringAfter("://").substringBefore("?")
                require(modelId.isNotEmpty()) {
                    "bedrock-sdk URL must include a model id: bedrock-sdk://<model-id>?region=<region>[&dimensions=<n>][&profile=<profile>]"
                }
                val region = params["region"] ?: System.getenv("AWS_REGION")
                    ?: throw IllegalArgumentException("bedrock-sdk URL needs ?region=<region>, or AWS_REGION must be set")
                val sdkProvider = params["profile"]?.let { ProfileCredentialsProvider.create(it) }
                    ?: DefaultCredentialsProvider.builder().build()
                BedrockEmbeddingService(
                    name = name,
                    context = context,
                    region = region,
                    credentialsProvider = awsSdkCredentials(sdkProvider),
                    dimensions = params["dimensions"]?.toInt(),
                )
            }
        }
    }

    /** Forces this object's init, registering the scheme. */
    public fun ensureRegistered() {}
}
