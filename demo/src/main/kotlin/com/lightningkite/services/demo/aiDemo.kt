package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.inference
import com.lightningkite.services.ai.plainText
import com.lightningkite.services.ai.userMessage
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the AI subsystem. Unlike most subsystems, `:ai` has no local/fake provider -
 * every registered scheme (`anthropic://`, `openai://`, `bedrock://`, `ollama://`,
 * `embedded://`) talks to a real model. Set the AI_URL environment variable to a settings
 * URL for one of those providers (with credentials, if required) to run this demo, e.g.:
 *
 *   AI_URL="anthropic://model=claude-3-5-haiku-latest" ./gradlew :demo:runAiDemo
 */
fun main() = runBlocking {
    val url = System.getenv("AI_URL")
    if (url == null) {
        println("Set AI_URL to a provider settings URL to run this demo (e.g. anthropic://, openai://, ollama://).")
        println("There is no fake/local AI provider in service-abstractions - a real one is required.")
        return@runBlocking
    }

    val context = TestSettingContext()
    val ai = LlmAccess.Settings(url)("ai", context)

    val models = ai.getModels()
    println("Available models: ${models.map { it.id }}")

    val result = ai.inference(
        model = models.first().id,
        prompt = LlmPrompt(messages = listOf(userMessage("Say hello in one short sentence."))),
    )
    println("Response: ${result.message.plainText()}")
}
