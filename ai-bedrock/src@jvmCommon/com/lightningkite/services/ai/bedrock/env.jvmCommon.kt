package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmException
import java.io.File

/** JVM actual: delegates to [System.getenv]. */
public actual fun getEnv(name: String): String? = System.getenv(name)

/**
 * Parse AWS INI-style credential files `~/.aws/credentials` and `~/.aws/config` and return
 * the requested profile. Tilde expansion uses `user.home`; honours `AWS_SHARED_CREDENTIALS_FILE`
 * and `AWS_CONFIG_FILE` overrides used by the official AWS tooling.
 *
 * Only the access-key, secret-key, and session-token fields are read — other settings
 * (e.g. `region`, `sso_*`) are ignored here; the caller already parses `region` from the URL.
 */
public actual fun loadProfileCredentials(profileName: String): AwsCredentials {
    val home = System.getProperty("user.home") ?: ""
    val credsPath = System.getenv("AWS_SHARED_CREDENTIALS_FILE") ?: "$home/.aws/credentials"
    val configPath = System.getenv("AWS_CONFIG_FILE") ?: "$home/.aws/config"

    // In ~/.aws/config, profiles other than [default] are prefixed "profile foo".
    val creds = readIniProfile(File(credsPath), profileName)
    val config = readIniProfile(File(configPath), if (profileName == "default") "default" else "profile $profileName")

    val merged = config + creds  // credentials file wins over config.
    val access = merged["aws_access_key_id"]
        ?: throw LlmException.Auth(
            "Profile '$profileName' has no aws_access_key_id in $credsPath or $configPath",
        )
    val secret = merged["aws_secret_access_key"]
        ?: throw LlmException.Auth(
            "Profile '$profileName' has no aws_secret_access_key in $credsPath or $configPath",
        )
    val token = merged["aws_session_token"]
    return AwsCredentials(access, secret, token)
}

/**
 * Minimal INI reader — splits into sections on `[header]` lines and returns the key/value
 * pairs for one section. Whitespace around keys and values is trimmed; comments (`#` or `;`
 * at column 0) are skipped. Missing file returns an empty map.
 */
private fun readIniProfile(file: File, section: String): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val result = mutableMapOf<String, String>()
    var inSection = false
    file.forEachLine { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachLine
        if (line.startsWith("[") && line.endsWith("]")) {
            inSection = line.substring(1, line.length - 1).trim() == section
            return@forEachLine
        }
        if (!inSection) return@forEachLine
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEachLine
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        result[key] = value
    }
    return result
}
