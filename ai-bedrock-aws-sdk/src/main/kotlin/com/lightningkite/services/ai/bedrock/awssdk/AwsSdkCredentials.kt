package com.lightningkite.services.ai.bedrock.awssdk

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.bedrock.AwsCredentials
import com.lightningkite.services.ai.bedrock.AwsCredentialsProvider
import com.lightningkite.services.ai.bedrock.BedrockLlmAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider as SdkAwsCredentialsProvider

/**
 * Adapt an AWS SDK credential provider to this library's [AwsCredentialsProvider], giving the
 * pure-Kotlin [BedrockLlmAccess] the full AWS credential story on the JVM: environment
 * variables, `~/.aws/credentials` and `~/.aws/config` profiles (including SSO, `sso_session`,
 * `credential_process`, and `role_arn`/`source_profile` assume-role chains), container/ECS
 * roles, and EC2 instance metadata — all with automatic refresh of expiring temporary
 * credentials.
 *
 * The SDK's `resolveCredentials()` is a blocking call (it may hit the filesystem, STS, or
 * IMDS), so it runs on [Dispatchers.IO]. It is invoked once per Bedrock request, so refreshed
 * credentials take effect on the next call.
 *
 * @param provider any AWS SDK credential provider; defaults to [DefaultCredentialsProvider],
 *   which walks the standard AWS credential chain exactly like the AWS CLI and SDKs.
 */
public fun awsSdkCredentials(
    provider: SdkAwsCredentialsProvider = DefaultCredentialsProvider.create(),
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
 * Convenience for a single named profile from `~/.aws/credentials` / `~/.aws/config`, resolved
 * with the AWS SDK (so SSO/assume-role/`credential_process` profiles work, unlike the built-in
 * static-only reader in `ai-bedrock`). Equivalent to
 * `awsSdkCredentials(ProfileCredentialsProvider.create(profile))`.
 */
public fun awsSdkProfileCredentials(profile: String): AwsCredentialsProvider =
    awsSdkCredentials(ProfileCredentialsProvider.create(profile))

/**
 * Build [LlmAccess.Settings] for a Bedrock model whose credentials come from the AWS SDK. No
 * secret ever appears in the URL — resolution happens through the SDK's credential chain.
 *
 * Produces `bedrock-sdk://<model-id>?region=<region>[&profile=<profile>]`.
 *
 * @param modelId Bedrock model id (e.g. `anthropic.claude-3-5-haiku-20241022-v1:0`).
 * @param region AWS region; falls back to `AWS_REGION`, then `us-east-1`.
 * @param profile optional named profile; when null the full default credential chain is used.
 */
public fun LlmAccess.Settings.Companion.bedrockSdk(
    modelId: String,
    region: String? = null,
    profile: String? = null,
): LlmAccess.Settings {
    BedrockSdkLlm.ensureRegistered()
    val params = buildList {
        region?.let { add("region=$it") }
        profile?.let { add("profile=$it") }
    }
    val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
    return LlmAccess.Settings("bedrock-sdk://$modelId$query")
}

/**
 * Registers the `bedrock-sdk://` URL scheme on [LlmAccess.Settings]. The scheme resolves
 * credentials via the AWS SDK (see [awsSdkCredentials]) and otherwise delegates to the
 * pure-Kotlin [BedrockLlmAccess].
 *
 * URL shape: `bedrock-sdk://<model-id>?region=<region>[&profile=<profile>]`.
 */
public object BedrockSdkLlm {
    init {
        // Idempotent guard mirrors the ai-bedrock registration: registering a scheme twice
        // throws, which would be fatal if class-loading reached us from multiple paths.
        if (!LlmAccess.Settings.supports("bedrock-sdk")) {
            LlmAccess.Settings.register("bedrock-sdk") { name, url, context ->
                val params = parseUrlParams(url)
                val region = params["region"]
                    ?: System.getenv("AWS_REGION")
                    ?: "us-east-1"
                val modelId = url.substringAfter("://", "").substringBefore("?")
                require(modelId.isNotEmpty()) {
                    "bedrock-sdk URL must include a model id: bedrock-sdk://<model-id>?region=<region>[&profile=<profile>]"
                }
                val provider = params["profile"]
                    ?.let(::awsSdkProfileCredentials)
                    ?: awsSdkCredentials()
                BedrockLlmAccess(
                    name = name,
                    context = context,
                    region = region,
                    credentialsProvider = provider,
                )
            }
        }
    }

    /** No-op call site that forces this object's init to run, registering the scheme. */
    public fun ensureRegistered() {}
}

/** Parse `?a=b&c=d` query parameters; later duplicates win. Empty map when there's no query. */
private fun parseUrlParams(url: String): Map<String, String> {
    val query = url.substringAfter("?", "")
    if (query.isEmpty()) return emptyMap()
    return query.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
}
