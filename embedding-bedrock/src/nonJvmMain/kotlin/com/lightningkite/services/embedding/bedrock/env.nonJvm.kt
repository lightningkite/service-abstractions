package com.lightningkite.services.embedding.bedrock

/**
 * Non-JVM actual: environment variables are not universally available on browser JS,
 * iOS, or macOS. Returns null; credentials must be supplied via the URL.
 */
internal actual fun getEnv(name: String): String? = null

/**
 * Non-JVM actual: AWS shared credential/config files require filesystem access which is
 * not portable across browser JS, iOS, and macOS. Profiles are JVM-only.
 */
internal actual fun loadProfileCredentials(profileName: String): AwsCredentials {
    throw UnsupportedOperationException(
        "Profile-based AWS credentials (bedrock://$profileName@...) require filesystem access " +
                "and are only supported on JVM. Use static credentials or environment variables instead.",
    )
}
