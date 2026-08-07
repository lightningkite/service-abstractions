package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.embed
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the embedding subsystem. Unlike most subsystems, `:embedding` has no local/fake
 * provider - every registered scheme (`openai://`, `ollama://`, `bedrock://`) talks to a real
 * model. Set the EMBEDDING_URL environment variable to a settings URL for one of those
 * providers (with credentials, if required) to run this demo, e.g.:
 *
 *   EMBEDDING_URL="ollama://model=nomic-embed-text" ./gradlew :demo:runEmbeddingDemo
 */
fun main() = runBlocking {
    val url = System.getenv("EMBEDDING_URL")
    if (url == null) {
        println("Set EMBEDDING_URL to a provider settings URL to run this demo (e.g. openai://, ollama://).")
        println("There is no fake/local embedding provider in service-abstractions - a real one is required.")
        return@runBlocking
    }

    val context = TestSettingContext()
    val embedding = EmbeddingService.Settings(url)("embedding", context)

    val models = embedding.getModels()
    println("Available models: ${models.map { it.id }}")

    val vector = embedding.embed("Hello, world!", models.first().id)
    println("Embedding dimensions: ${vector.dimensions}")
}
