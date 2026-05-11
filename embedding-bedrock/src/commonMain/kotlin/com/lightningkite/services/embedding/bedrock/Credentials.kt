package com.lightningkite.services.embedding.bedrock

import com.lightningkite.services.embedding.EmbeddingException

/**
 * A set of AWS credentials used to sign Bedrock requests.
 *
 * [sessionToken] is only populated when credentials came from STS / assume-role / IMDS.
 */
public data class AwsCredentials(
    public val accessKeyId: String,
    public val secretAccessKey: String,
    public val sessionToken: String? = null,
)

/**
 * Platform-specific environment-variable lookup.
 *
 * JVM/Android read from [System.getenv]; JS returns null; native targets call `getenv(3)`.
 */
internal expect fun getEnv(name: String): String?

/**
 * Platform hook for profile-based credential resolution.
 *
 * JVM actual reads `~/.aws/credentials` / `~/.aws/config`. Non-JVM targets throw.
 */
internal expect fun loadProfileCredentials(profileName: String): AwsCredentials

/**
 * Expand `${ENV_VAR}` placeholders using [getEnv]. Unresolved references are left verbatim.
 */
internal fun resolveEnvVars(value: String): String {
    val envVarPattern = Regex("""\$\{([^}]+)}""")
    return envVarPattern.replace(value) { matchResult ->
        val envVar = matchResult.groupValues[1]
        getEnv(envVar) ?: matchResult.value
    }
}

/**
 * Resolve credentials via the default chain:
 * 1. Environment variables `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`.
 * 2. On JVM only: a named profile in `~/.aws/credentials`.
 */
internal fun resolveDefaultChain(): AwsCredentials {
    val access = getEnv("AWS_ACCESS_KEY_ID")
    val secret = getEnv("AWS_SECRET_ACCESS_KEY")
    if (access != null && secret != null) {
        return AwsCredentials(access, secret, getEnv("AWS_SESSION_TOKEN"))
    }
    throw EmbeddingException.Auth(
        "No AWS credentials available. Provide them in the bedrock:// URL or set " +
                "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables.",
    )
}
