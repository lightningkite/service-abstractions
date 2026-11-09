package com.lightningkite.services.ai.embedded

/** Parsed from the `embedded://` URL; see [EmbeddedLlmSettings] for what each field means per platform. */
internal data class EmbeddedEngineConfig(
    val modelName: String,
    val modelPath: String,
    val threads: Int = 4,
    val contextSize: Int = 4096,
    val dtype: String = "q4",
    val runtimeUrl: String? = null,
    val device: String? = null,
)
