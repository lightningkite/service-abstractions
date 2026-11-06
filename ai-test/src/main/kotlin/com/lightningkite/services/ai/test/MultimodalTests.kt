package com.lightningkite.services.ai.test

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import com.lightningkite.services.ai.userMessage
import com.lightningkite.services.data.MediaType
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
 * to skip the URL test while still exercising the base64 path. A PDF test runs when the model
 * info says the model reads PDFs.
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
                    userMessage(
                        caption = "What single color is this image? Respond with just the color name.",
                        attachment = LlmAttachment.Base64(pngMediaType, TINY_RED_PNG_BASE64),
                    ),
                ),
                maxTokens = testMaxTokens,
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
                    userMessage(
                        caption = "What is the primary color of this image? Respond with just the color name.",
                        attachment = LlmAttachment.Url(pngMediaType, STABLE_BLUE_IMAGE_URL),
                    ),
                ),
                maxTokens = testMaxTokens,
            ),
        )
        val text = result.message.plainText().lowercase()
        assertTrue(
            "blue" in text,
            "Expected the model to identify the image as blue; got: '$text'",
        )
    }

    /**
     * Send [PELICAN_PDF_BASE64] and ask for the word it contains. Runs only when the service's
     * model info for [visionModel] lists `application/pdf` in its supported media types.
     */
    @Test
    public fun base64PdfReadCorrectly(): Unit = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val model = visionModel ?: run {
            println("SKIP base64PdfReadCorrectly: provider does not support vision")
            return@runTest
        }
        val info = service.getModels().find { it.id.id == model.id }
        if (info == null || info.supportedMediaTypes.none { it.accepts(MediaType.Application.Pdf) }) {
            println("SKIP base64PdfReadCorrectly: ${model.id} doesn't list application/pdf as supported")
            return@runTest
        }
        val result = service.inference(
            model = model,
            prompt = LlmPrompt(
                messages = listOf(
                    userMessage(
                        caption = "What single word does this document contain? Respond with just the word.",
                        attachment = LlmAttachment.Base64(MediaType.Application.Pdf, PELICAN_PDF_BASE64),
                    ),
                ),
                maxTokens = testMaxTokens,
            ),
        )
        val text = result.message.plainText().lowercase()
        assertTrue(
            "pelican" in text,
            "Expected the model to read 'pelican' from the PDF; got: '$text'",
        )
    }
}
