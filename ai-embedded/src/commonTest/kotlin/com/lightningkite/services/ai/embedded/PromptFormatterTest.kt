package com.lightningkite.services.ai.embedded

import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptFormatterTest {
    private val simplePrompt = LlmPrompt(
        systemPrompt = listOf(LlmPart.Text("You are helpful.")),
        messages = listOf(
            LlmMessage.User(parts = listOf(LlmPart.Text("Hello")))
        )
    )

    @Test
    fun chatMLFormat() {
        val result = PromptFormatter.format(simplePrompt, ChatTemplate.ChatML)
        assertContains(result, "<|im_start|>system\nYou are helpful.<|im_end|>")
        assertContains(result, "<|im_start|>user\nHello<|im_end|>")
        assertTrue(result.endsWith("<|im_start|>assistant\n"), "Expected ChatML assistant prefix at end")
    }

    @Test
    fun llamaFormat() {
        val result = PromptFormatter.format(simplePrompt, ChatTemplate.Llama)
        assertContains(result, "<|start_header_id|>system<|end_header_id|>\n\nYou are helpful.<|eot_id|>")
        assertContains(result, "<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>")
        assertTrue(result.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun gemmaFormat() {
        val result = PromptFormatter.format(simplePrompt, ChatTemplate.Gemma)
        assertContains(result, "<start_of_turn>system\nYou are helpful.<end_of_turn>")
        assertContains(result, "<start_of_turn>user\nHello<end_of_turn>")
        assertTrue(result.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun phiFormat() {
        val result = PromptFormatter.format(simplePrompt, ChatTemplate.Phi)
        assertContains(result, "<|system|>\nYou are helpful.<|end|>")
        assertContains(result, "<|user|>\nHello<|end|>")
        assertTrue(result.endsWith("<|assistant|>\n"))
    }

    @Test
    fun multiTurnConversation() {
        val prompt = LlmPrompt(
            systemPrompt = listOf(LlmPart.Text("Be brief.")),
            messages = listOf(
                LlmMessage.User(parts = listOf(LlmPart.Text("Hi"))),
                LlmMessage.Agent(parts = listOf(LlmPart.Text("Hello!"))),
                LlmMessage.User(parts = listOf(LlmPart.Text("How are you?"))),
            )
        )
        val result = PromptFormatter.format(prompt, ChatTemplate.ChatML)
        assertContains(result, "<|im_start|>assistant\nHello!<|im_end|>")
        assertContains(result, "<|im_start|>user\nHow are you?<|im_end|>")
    }

    @Test
    fun templateSelection() {
        assertEquals(ChatTemplate.Llama, PromptFormatter.templateFor("llama-3.2-1b"))
        assertEquals(ChatTemplate.Gemma, PromptFormatter.templateFor("gemma-2b-it"))
        assertEquals(ChatTemplate.Phi, PromptFormatter.templateFor("phi-3-mini"))
        assertEquals(ChatTemplate.ChatML, PromptFormatter.templateFor("mistral-7b"))
        assertEquals(ChatTemplate.ChatML, PromptFormatter.templateFor("unknown-model"))
    }

    @Test
    fun emptySystemPrompt() {
        val prompt = LlmPrompt(
            systemPrompt = emptyList(),
            messages = listOf(
                LlmMessage.User(parts = listOf(LlmPart.Text("Hello")))
            )
        )
        val result = PromptFormatter.format(prompt, ChatTemplate.ChatML)
        assertFalse(result.contains("<|im_start|>system"))
        assertContains(result, "<|im_start|>user\nHello<|im_end|>")
    }
}
