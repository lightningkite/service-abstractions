package com.lightningkite.services.ai.embedded

/**
 * Configuration for the on-device LLM inference engine, parsed from the URL scheme.
 *
 * @property modelName Identifier for the model (e.g., "gemma-2b", "phi-3-mini")
 * @property modelPath Platform-specific path to model files. On native platforms this is a
 *   filesystem path; on web this is a HuggingFace model ID or URL.
 * @property threads Number of threads for inference (default: 4)
 * @property contextSize Maximum context window in tokens (default: 4096)
 */
internal data class EmbeddedEngineConfig(
    val modelName: String,
    val modelPath: String?,
    val threads: Int = 4,
    val contextSize: Int = 4096,
)
