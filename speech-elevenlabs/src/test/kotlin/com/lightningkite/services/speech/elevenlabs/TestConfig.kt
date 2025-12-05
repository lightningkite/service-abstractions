package com.lightningkite.services.speech.elevenlabs

import java.io.File
import java.util.Properties

/**
 * Test configuration that reads API keys from environment variables
 * or falls back to local.properties file.
 */
object TestConfig {
    private val properties: Properties by lazy {
        Properties().apply {
            // Search for local.properties in current dir and parent directories
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                val propsFile = File(dir, "local.properties")
                if (propsFile.exists()) {
                    propsFile.inputStream().use { load(it) }
                    break
                }
                dir = dir.parentFile
            }
        }
    }

    val elevenlabsApiKey: String?
        get() = System.getenv("ELEVENLABS_API_KEY")
            ?: properties.getProperty("ELEVENLABS_API_KEY")
            ?: properties.getProperty("ELEVEN_LABS_API_KEY")

    val hasElevenLabsCredentials: Boolean
        get() = elevenlabsApiKey != null
}
