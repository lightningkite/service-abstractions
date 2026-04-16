package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.MultimodalTests

/**
 * Live multimodal suite against Ollama. Requires a vision model (LLaVA family by default,
 * override via `OLLAMA_VISION_MODEL`). The entire suite auto-skips when no suitable vision
 * model is installed locally — see [OllamaTestConfig.visionModel] for autodetection logic.
 *
 * Ollama does not accept URL-form attachments; it requires base64 image bytes inline. The
 * URL-attachment test is therefore disabled for this provider.
 */
class OllamaMultimodalIntegrationTest : MultimodalTests() {
    override val service: LlmAccess get() = OllamaTestConfig.service
    override val cheapModel: LlmModelId get() = OllamaTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = OllamaTestConfig.visionModel
    override val servicePresent: Boolean get() = OllamaTestConfig.servicePresent

    /** Ollama's native `/api/chat` takes images as base64 only — no fetch-by-URL. */
    override val supportsUrlAttachments: Boolean = false
}
