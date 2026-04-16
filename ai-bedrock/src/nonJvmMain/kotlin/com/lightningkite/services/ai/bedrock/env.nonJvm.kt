package com.lightningkite.services.ai.bedrock

/**
 * Non-JVM actual.
 *
 * Environment variable lookup is not universally available across the non-JVM targets this
 * module compiles for (browser JS has no env; native and Node do). We return null here; the
 * JS-specific source set may override this via additional actuals if browser-side env access
 * is ever needed. In practice, browser / mobile code supplies credentials via the URL query
 * parameters (or a constructor override), not environment variables.
 */
public actual fun getEnv(name: String): String? = null

/**
 * Non-JVM actual: AWS shared credential/config files require filesystem access which is not
 * portable across browser JS, iOS, macOS, and Android (ignoring its JVM path). Profiles are
 * therefore JVM-only.
 */
public actual fun loadProfileCredentials(profileName: String): AwsCredentials {
    throw UnsupportedOperationException(
        "Profile-based AWS credentials (bedrock://$profileName@...) require filesystem access " +
                "and are only supported on JVM. Use static credentials or environment variables instead.",
    )
}
