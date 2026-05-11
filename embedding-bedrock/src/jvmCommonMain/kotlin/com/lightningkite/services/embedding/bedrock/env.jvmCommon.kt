package com.lightningkite.services.embedding.bedrock

import com.lightningkite.services.embedding.EmbeddingException
import java.io.File

/** JVM actual: delegates to [System.getenv]. */
internal actual fun getEnv(name: String): String? = System.getenv(name)

/**
 * Parse AWS INI-style credential files `~/.aws/credentials` and `~/.aws/config` and return
 * the requested profile. Honours `AWS_SHARED_CREDENTIALS_FILE` and `AWS_CONFIG_FILE` overrides.
 */
internal actual fun loadProfileCredentials(profileName: String): AwsCredentials {
    val home = System.getProperty("user.home") ?: ""
    val credsPath = System.getenv("AWS_SHARED_CREDENTIALS_FILE") ?: "$home/.aws/credentials"
    val configPath = System.getenv("AWS_CONFIG_FILE") ?: "$home/.aws/config"

    val creds = readIniProfile(File(credsPath), profileName)
    val config = readIniProfile(File(configPath), if (profileName == "default") "default" else "profile $profileName")

    val merged = config + creds  // credentials file wins over config
    val access = merged["aws_access_key_id"]
        ?: throw EmbeddingException.Auth(
            "Profile '$profileName' has no aws_access_key_id in $credsPath or $configPath",
        )
    val secret = merged["aws_secret_access_key"]
        ?: throw EmbeddingException.Auth(
            "Profile '$profileName' has no aws_secret_access_key in $credsPath or $configPath",
        )
    val token = merged["aws_session_token"]
    return AwsCredentials(access, secret, token)
}

/**
 * Minimal INI reader: splits on `[header]` lines, returns key/value pairs for one section.
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
