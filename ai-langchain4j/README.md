# LangChain4J Integration for Service Abstractions

This module provides Settings-based configuration for [LangChain4J](https://github.com/langchain4j/langchain4j), allowing you to use LangChain4J's interfaces with URL-based configuration.

## Overview

Instead of wrapping LangChain4J's well-designed abstractions, these modules simply provide Settings classes that instantiate LangChain4J services from URL strings. This lets you:

- Configure LLM providers via URLs (e.g., `openai-chat://gpt-4?apiKey=...`)
- Switch between providers without code changes
- Use environment variables for API keys (e.g., `${OPENAI_API_KEY}`)
- Leverage all of LangChain4J's existing functionality

## Modules

- **`:ai-langchain4j`** - Core Settings classes for LangChain4J interfaces
- **`:ai-langchain4j-openai`** - OpenAI chat and embedding models
- **`:ai-langchain4j-anthropic`** - Anthropic Claude models
- **`:ai-langchain4j-ollama`** - Ollama (local) models
- **`:ai-langchain4j-pinecone`** - Pinecone vector database

## Quick Start

### 1. Add Dependencies

```kotlin
dependencies {
    implementation(project(":ai-langchain4j"))
    implementation(project(":ai-langchain4j-openai"))  // Or other providers
}
```

### 2. Define Settings

```kotlin
import com.lightningkite.services.ai.*
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val chatModel: ChatLanguageModelSettings =
        ChatLanguageModelSettings("openai-chat://gpt-4?apiKey=\${OPENAI_API_KEY}"),

    val embeddingModel: EmbeddingModelSettings =
        EmbeddingModelSettings("openai-embedding://text-embedding-3-small?apiKey=\${OPENAI_API_KEY}"),

    val vectorStore: EmbeddingStoreSettings =
        EmbeddingStoreSettings("pinecone://my-index?apiKey=\${PINECONE_API_KEY}&environment=us-east-1")
)
```

### 3. Instantiate Services

```kotlin
import com.lightningkite.services.SettingContext
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore

val context: SettingContext = // ... your context

// Instantiate LangChain4J services
val chatModel: ChatLanguageModel = settings.chatModel("gpt-chat", context)
val embeddingModel: EmbeddingModel = settings.embeddingModel("embeddings", context)
val vectorStore: EmbeddingStore<*> = settings.vectorStore("vectors", context)

// Use them directly with LangChain4J APIs
val response = chatModel.generate("Explain quantum computing in simple terms")
println(response.content().text())
```

## Supported URL Schemes

### Chat Models

**OpenAI:**
```
openai-chat://gpt-4-turbo?apiKey=${OPENAI_API_KEY}&temperature=0.7
openai-chat://gpt-3.5-turbo?apiKey=sk-...&maxTokens=1000
```

**Anthropic:**
```
anthropic-chat://claude-3-5-sonnet-20241022?apiKey=${ANTHROPIC_API_KEY}
anthropic-chat://claude-3-5-haiku-20241022?apiKey=sk-ant-...&temperature=0.7
```

**Ollama (Local):**
```
ollama-chat://llama3.2?baseUrl=http://localhost:11434
ollama-chat://mistral?temperature=0.7
```

### Embedding Models

**OpenAI:**
```
openai-embedding://text-embedding-3-small?apiKey=${OPENAI_API_KEY}
openai-embedding://text-embedding-3-large?apiKey=sk-...
```

**Ollama:**
```
ollama-embedding://nomic-embed-text?baseUrl=http://localhost:11434
ollama-embedding://mxbai-embed-large
```

### Vector Stores

**Pinecone:**
```
pinecone://my-index?apiKey=${PINECONE_API_KEY}&environment=us-east-1
```

## Complete Example

```kotlin
import com.lightningkite.services.*
import com.lightningkite.services.ai.*
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule

@Serializable
data class MyAppSettings(
    val chat: ChatLanguageModelSettings =
        ChatLanguageModelSettings("openai-chat://gpt-4?apiKey=\${OPENAI_API_KEY}")
)

fun main() {
    // Create a SettingContext
    val context = object : SettingContext {
        override val projectName = "my-ai-app"
        override val publicUrl = "https://example.com"
        override val internalSerializersModule = SerializersModule {}
        override val openTelemetry = null
        override val sharedResources = SharedResources()
    }

    // Load settings
    val settings = MyAppSettings()

    // Instantiate the chat model
    val chatModel: ChatLanguageModel = settings.chat("main-chat", context)

    // Use it!
    val response = chatModel.generate(
        UserMessage.from("Write a haiku about Kotlin")
    )

    println(response.content().text())
}
```

## Environment Variables

The Settings classes support environment variable substitution using `${VAR_NAME}` syntax:

```kotlin
// In your settings
ChatLanguageModelSettings("openai-chat://gpt-4?apiKey=\${OPENAI_API_KEY}")

// Export the environment variable
export OPENAI_API_KEY=sk-...
```

If the environment variable is not found, it will fall back to checking standard environment variables (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, etc.).

## Adding New Providers

To add support for a new LangChain4J provider:

1. Create a new module (e.g., `:ai-langchain4j-google`)
2. Add the LangChain4J integration as a dependency
3. Register your implementation in an init block:

```kotlin
package com.lightningkite.services.ai.google

import com.lightningkite.services.ai.*

internal object GoogleChatLanguageModelRegistration {
    init {
        ChatLanguageModelSettings.register("google-chat") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            GoogleAiChatModel.builder()
                .apiKey(params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("GOOGLE_API_KEY"))
                .modelName(modelName.ifEmpty { "gemini-pro" })
                .build()
        }
    }
}

// Trigger initialization
private val register = GoogleChatLanguageModelRegistration
```

## License

Part of the service-abstractions library.
