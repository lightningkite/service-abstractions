package com.lightningkite.services.ai

/**
 * Parses URL query parameters into a map.
 *
 * Example: "openai-chat://gpt-4?apiKey=sk-123&temperature=0.7"
 * Returns: {"apiKey" to "sk-123", "temperature" to "0.7"}
 */
public fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()

    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
}

/**
 * Extracts the model/resource name from URL.
 *
 * Example: "openai-chat://gpt-4-turbo?apiKey=..." returns "gpt-4-turbo"
 */
public fun extractModelName(url: String): String {
    return url.substringAfter("://")
        .substringBefore("?")
        .substringBefore("/")
}

/**
 * Resolves environment variables in parameter values.
 *
 * Replaces ${ENV_VAR} with the value from System.getenv("ENV_VAR")
 */
public fun resolveEnvVars(value: String): String {
    val envVarPattern = """\$\{([^}]+)\}""".toRegex()
    return envVarPattern.replace(value) { matchResult ->
        val envVar = matchResult.groupValues[1]
        System.getenv(envVar) ?: matchResult.value
    }
}
