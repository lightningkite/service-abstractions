package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmException

/**
 * A set of AWS credentials used to sign Bedrock requests.
 *
 * [sessionToken] is only populated when credentials came from STS / assume-role / IMDS —
 * in that case it must be sent as `x-amz-security-token` alongside the signature.
 */
public data class AwsCredentials(
    public val accessKeyId: String,
    public val secretAccessKey: String,
    public val sessionToken: String? = null,
)

/**
 * Platform-specific environment-variable lookup.
 *
 * Returns null if the variable is unset. JVM/Android read from [System.getenv]; JS reads
 * from `process.env` when available (Node) and returns null in the browser; native targets
 * call `getenv(3)`.
 */
public expect fun getEnv(name: String): String?

/**
 * Platform hook for profile-based credential resolution.
 *
 * JVM actual reads `~/.aws/credentials` / `~/.aws/config`. Non-JVM targets have no standard
 * filesystem access, so this throws [UnsupportedOperationException] there.
 */
public expect fun loadProfileCredentials(profileName: String): AwsCredentials

/**
 * Expand `${ENV_VAR}` placeholders using [getEnv]. Unresolved references are left verbatim,
 * matching the behaviour of the other LLM provider adapters in this family.
 */
internal fun resolveEnvVars(value: String): String {
    val envVarPattern = Regex("""\$\{([^}]+)}""")
    return envVarPattern.replace(value) { matchResult ->
        val envVar = matchResult.groupValues[1]
        getEnv(envVar) ?: matchResult.value
    }
}

/**
 * Resolve credentials for a Bedrock client.
 *
 * Priority (matching the AWS CLI's default behaviour, minus IMDS):
 * 1. Static credentials baked into the URL (already parsed by the Settings layer).
 * 2. Environment variables `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`.
 * 3. On JVM only: a named profile in `~/.aws/credentials`.
 *
 * Each entry point throws [IllegalArgumentException] with a clear message on failure rather
 * than silently falling through — the intent is to fail fast at service instantiation.
 */
internal fun resolveDefaultChain(): AwsCredentials {
    val access = getEnv("AWS_ACCESS_KEY_ID")
    val secret = getEnv("AWS_SECRET_ACCESS_KEY")
    if (access != null && secret != null) {
        return AwsCredentials(access, secret, getEnv("AWS_SESSION_TOKEN"))
    }
    throw LlmException.Auth(
        "No AWS credentials available. Provide them in the bedrock:// URL or set " +
                "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables.",
    )
}
