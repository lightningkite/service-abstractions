package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies image-attachment handling for providers that support vision.
 *
 * The whole class is skipped when the subclass leaves [visionModel] null; otherwise it sends
 * a tiny known-color PNG and a stable public image URL and asserts the model correctly names
 * the color. Providers without URL-attachment support can set [supportsUrlAttachments]=false
 * to skip the URL test while still exercising the base64 path.
 */
public abstract class MultimodalTests : LlmAccessTests() {

    /**
     * Send the [TINY_RED_PNG_BASE64] constant as a base64 image and ask the model what
     * color it is. Expect "red" in the response.
     */
    @Test
    public fun base64ImageDescribedCorrectly(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val model = visionModel ?: run {
            println("SKIP base64ImageDescribedCorrectly: provider does not support vision")
            return@runTest
        }
        val result = service.inference(
            model = model,
            prompt = LlmPrompt(
                messages = listOf(
                    userWithAttachment(
                        caption = "What single color is this image? Respond with just the color name.",
                        attachment = LlmAttachment.Base64(pngMediaType, TINY_RED_PNG_BASE64),
                    ),
                ),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val text = result.message.plainText().lowercase()
        assertTrue(
            "red" in text,
            "Expected the model to identify the image as red; got: '$text'",
        )
    }

    /**
     * Send the [STABLE_BLUE_IMAGE_URL] as a URL attachment. Providers that cannot fetch
     * attachments by URL (rare for vision models but possible for local / enterprise
     * deployments) should set [supportsUrlAttachments]=false to skip.
     */
    @Test
    public fun urlImageDescribedCorrectly(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val model = visionModel ?: run {
            println("SKIP urlImageDescribedCorrectly: provider does not support vision")
            return@runTest
        }
        if (!supportsUrlAttachments) {
            println("SKIP urlImageDescribedCorrectly: provider does not support URL attachments")
            return@runTest
        }
        val result = service.inference(
            model = model,
            prompt = LlmPrompt(
                messages = listOf(
                    userWithAttachment(
                        caption = "What is the primary color of this image? Respond with just the color name.",
                        attachment = LlmAttachment.Url(pngMediaType, STABLE_BLUE_IMAGE_URL),
                    ),
                ),
                maxTokens = testMaxTokens,
                temperature = 0.0,
            ),
        )
        val text = result.message.plainText().lowercase()
        assertTrue(
            "blue" in text,
            "Expected the model to identify the image as blue; got: '$text'",
        )
    }
}
