package com.lightningkite.services.ai.openai

import com.lightningkite.services.ai.LlmAccess
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvidersTest {

    @Test
    fun openai() {
        val s = LlmAccess.Settings.openai("gpt-4o", apiKey = "sk-test")
        assertEquals("openai://gpt-4o?apiKey=sk-test", s.url)
    }

    @Test
    fun openaiWithOrganization() {
        val s = LlmAccess.Settings.openai("gpt-4o", apiKey = "sk-test", organization = "org-123")
        assertTrue(s.url.contains("apiKey=sk-test"))
        assertTrue(s.url.contains("organization=org-123"))
    }

    @Test
    fun openRouter() {
        val s = LlmAccess.Settings.openRouter("anthropic/claude-sonnet-4", apiKey = "sk-or-test")
        assertTrue(s.url.startsWith("openai://anthropic/claude-sonnet-4?"))
        assertTrue(s.url.contains("apiKey=sk-or-test"))
        assertTrue(s.url.contains("baseUrl="))
        assertTrue(s.url.contains("openrouter.ai"))
    }

    @Test
    fun openRouterDefaultEnvVar() {
        val s = LlmAccess.Settings.openRouter("openai/gpt-4o")
        assertTrue(s.url.contains("OPENROUTER_API_KEY"))
    }

    @Test
    fun groq() {
        val s = LlmAccess.Settings.groq("llama-3.3-70b-versatile", apiKey = "gsk-test")
        assertTrue(s.url.startsWith("openai://llama-3.3-70b-versatile?"))
        assertTrue(s.url.contains("groq.com"))
    }

    @Test
    fun togetherAi() {
        val s = LlmAccess.Settings.togetherAi("meta-llama/Llama-3.3-70B-Instruct-Turbo", apiKey = "t-test")
        assertTrue(s.url.contains("together.xyz"))
    }

    @Test
    fun fireworks() {
        val s = LlmAccess.Settings.fireworks("accounts/fireworks/models/llama-v3p3-70b-instruct", apiKey = "fw-test")
        assertTrue(s.url.contains("fireworks.ai"))
    }

    @Test
    fun perplexity() {
        val s = LlmAccess.Settings.perplexity("sonar-pro", apiKey = "pplx-test")
        assertTrue(s.url.contains("perplexity.ai"))
    }

    @Test
    fun deepSeek() {
        val s = LlmAccess.Settings.deepSeek("deepseek-chat", apiKey = "ds-test")
        assertTrue(s.url.contains("deepseek.com"))
    }

    @Test
    fun mistral() {
        val s = LlmAccess.Settings.mistral("mistral-large-latest", apiKey = "m-test")
        assertTrue(s.url.contains("mistral.ai"))
    }

    @Test
    fun xai() {
        val s = LlmAccess.Settings.xai("grok-3", apiKey = "xai-test")
        assertTrue(s.url.contains("x.ai"))
    }

    @Test
    fun cerebras() {
        val s = LlmAccess.Settings.cerebras("llama-3.3-70b", apiKey = "cb-test")
        assertTrue(s.url.contains("cerebras.ai"))
    }

    @Test
    fun nvidiaNim() {
        val s = LlmAccess.Settings.nvidiaNim("meta/llama-3.3-70b-instruct", apiKey = "nv-test")
        assertTrue(s.url.contains("nvidia.com"))
    }

    @Test
    fun sambaNova() {
        val s = LlmAccess.Settings.sambaNova("Meta-Llama-3.3-70B-Instruct", apiKey = "sn-test")
        assertTrue(s.url.contains("sambanova.ai"))
    }

    @Test
    fun geminiOpenAi() {
        val s = LlmAccess.Settings.geminiOpenAi("gemini-2.5-pro", apiKey = "g-test")
        assertTrue(s.url.contains("googleapis.com"))
    }

    @Test
    fun azureOpenAi() {
        val s = LlmAccess.Settings.azureOpenAi(
            resourceName = "my-resource",
            deploymentId = "gpt-4o",
            apiKey = "az-test",
        )
        assertTrue(s.url.contains("my-resource.openai.azure.com"))
        assertTrue(s.url.contains("gpt-4o"))
    }

    @Test
    fun cohere() {
        val s = LlmAccess.Settings.cohere("command-r-plus", apiKey = "co-test")
        assertTrue(s.url.contains("cohere.ai"))
    }

    @Test
    fun databricks() {
        val s = LlmAccess.Settings.databricks(
            workspaceUrl = "https://my-ws.cloud.databricks.com",
            modelId = "my-model",
            apiKey = "db-test",
        )
        assertTrue(s.url.contains("databricks.com"))
    }

    @Test
    fun leptonAi() {
        val s = LlmAccess.Settings.leptonAi("llama-3.3-70b", apiKey = "lp-test")
        assertTrue(s.url.contains("lepton.ai"))
    }

    @Test
    fun ovhcloud() {
        val s = LlmAccess.Settings.ovhcloud("Mistral-7B-Instruct-v0.3", apiKey = "ovh-test")
        assertTrue(s.url.contains("ovh.net"))
    }

    @Test
    fun lmStudio() {
        val s = LlmAccess.Settings.lmStudio("qwen2.5-coder")
        assertTrue(s.url.contains("localhost"))
        assertTrue(s.url.contains("1234"))
        assertTrue(s.url.contains("lm-studio"))
    }

    @Test
    fun ollamaOpenAi() {
        val s = LlmAccess.Settings.ollamaOpenAi("llama3.3")
        assertTrue(s.url.contains("localhost"))
        assertTrue(s.url.contains("11434"))
    }

    @Test
    fun vllm() {
        val s = LlmAccess.Settings.vllm("meta-llama/Llama-3.3-70B-Instruct")
        assertTrue(s.url.contains("localhost"))
        assertTrue(s.url.contains("8000"))
    }

    @Test
    fun openAiCompatibleGeneric() {
        val s = LlmAccess.Settings.openAiCompatible(
            modelId = "my-model",
            baseUrl = "https://my-server.example.com/v1",
            apiKey = "test-key",
        )
        assertTrue(s.url.contains("my-server.example.com"))
    }
}
