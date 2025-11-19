# Koog Integration for Service Abstractions

This module provides Settings-based configuration for [Koog](https://github.com/JetBrains/koog), JetBrains' official Kotlin framework for building AI agents.

## Overview

Koog is designed specifically for Kotlin and includes built-in clients for all major LLM providers in a single artifact. This makes it ideal for:

- **Kotlin Multiplatform** projects (JVM, JS, iOS, etc.)
- **Smaller binaries** - all providers in one dependency
- **Consistent API** - same interface across all providers
- **Enterprise features** - fault tolerance, observability, MCP support

## Quick Start

### 1. Add Dependencies

```kotlin
dependencies {
    implementation(project(":ai-koog"))
}
```

Note: Koog requires JDK 17+ for JVM targets.

### 2. Define Settings

```kotlin
import com.lightningkite.services.ai.koog.*
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val executor: PromptExecutorSettings =
        PromptExecutorSettings("koog-openai://gpt-4?apiKey=\${OPENAI_API_KEY}")
)
```

### 3. Instantiate and Use

```kotlin
import ai.koog.agents.core.AIAgent
import ai.koog.agents.providers.openai.OpenAIModels
import com.lightningkite.services.SettingContext

val context: SettingContext = // ... your context

// Instantiate Koog's PromptExecutor
val executor = settings.executor("my-executor", context)

// Use with Koog's AIAgent
val agent = AIAgent(
    executor = executor,
    systemPrompt = "You are a helpful assistant.",
    llmModel = OpenAIModels.Chat.GPT4o
)

// Run the agent
val response = agent.run("Explain quantum computing in simple terms")
println(response)
```

## Supported Providers

All providers are included in the single `:ai-koog` module:

### OpenAI

```kotlin
PromptExecutorSettings("koog-openai://gpt-4?apiKey=\${OPENAI_API_KEY}")
PromptExecutorSettings("koog-openai://gpt-4o-mini?apiKey=sk-...")
```

### Anthropic (Claude)

```kotlin
PromptExecutorSettings("koog-anthropic://claude-3-5-sonnet-20241022?apiKey=\${ANTHROPIC_API_KEY}")
PromptExecutorSettings("koog-anthropic://claude-3-5-haiku-20241022?apiKey=sk-ant-...")
```

### Google (Gemini)

```kotlin
PromptExecutorSettings("koog-google://gemini-2.0-flash-exp?apiKey=\${GOOGLE_API_KEY}")
PromptExecutorSettings("koog-google://gemini-1.5-pro?apiKey=...")
```

### Ollama (Local)

```kotlin
PromptExecutorSettings("koog-ollama://llama3.2?baseUrl=http://localhost:11434")
PromptExecutorSettings("koog-ollama://mistral")
```

### OpenRouter

```kotlin
PromptExecutorSettings("koog-openrouter://anthropic/claude-3-opus?apiKey=\${OPENROUTER_API_KEY}")
```

## Complete Example

```kotlin
import ai.koog.agents.core.AIAgent
import ai.koog.agents.core.PromptExecutor
import ai.koog.agents.providers.openai.OpenAIModels
import com.lightningkite.services.*
import com.lightningkite.services.ai.koog.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule

@Serializable
data class MyAppSettings(
    val openai: PromptExecutorSettings =
        PromptExecutorSettings("koog-openai://gpt-4?apiKey=\${OPENAI_API_KEY}"),
    val anthropic: PromptExecutorSettings =
        PromptExecutorSettings("koog-anthropic://claude-3-5-sonnet-20241022?apiKey=\${ANTHROPIC_API_KEY}"),
    val local: PromptExecutorSettings =
        PromptExecutorSettings("koog-ollama://llama3.2")
)

suspend fun main() {
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

    // Instantiate executors
    val openaiExecutor: PromptExecutor = settings.openai("openai", context)
    val anthropicExecutor: PromptExecutor = settings.anthropic("anthropic", context)
    val localExecutor: PromptExecutor = settings.local("local", context)

    // Create agents
    val gptAgent = AIAgent(
        executor = openaiExecutor,
        systemPrompt = "You are a helpful coding assistant.",
        llmModel = OpenAIModels.Chat.GPT4o
    )

    val claudeAgent = AIAgent(
        executor = anthropicExecutor,
        systemPrompt = "You are a creative writing assistant.",
        llmModel = AnthropicModels.Sonnet_4
    )

    // Use them
    println(gptAgent.run("Write a Kotlin function to reverse a string"))
    println(claudeAgent.run("Write a haiku about Kotlin"))
}
```

## Koog vs LangChain4J

| Feature | Koog | LangChain4J |
|---------|------|-------------|
| Language | Pure Kotlin | Java (Kotlin-friendly) |
| Multiplatform | ✅ JVM, JS, iOS, Wasm | ❌ JVM only |
| Binary Size | Small (single artifact) | Large (per-provider artifacts) |
| Providers | 5 providers in `koog-agents` | 30+ providers in separate modules |
| Framework Focus | Agent orchestration | LLM abstraction layer |
| Kotlin DSL | Native | Java builders |

Choose **Koog** if you want:
- Kotlin Multiplatform support
- Smaller dependency footprint
- Native Kotlin DSL and coroutines
- Agent-first design with fault tolerance

Choose **LangChain4J** if you want:
- More mature ecosystem
- More LLM providers (30+ vs 6)
- Java compatibility
- Fine-grained dependency control

## Environment Variables

Both Koog Settings support environment variable substitution using `${VAR_NAME}` syntax:

```kotlin
PromptExecutorSettings("koog-openai://gpt-4?apiKey=\${OPENAI_API_KEY}")
```

If the environment variable is not found, it will fall back to checking standard environment variables.

## Platform Requirements

- **JVM**: JDK 17 or higher
- **JS**: Modern JavaScript engines
- **iOS**: Kotlin/Native targets
- **Wasm**: WasmJS support

## Additional Resources

- [Koog Documentation](https://docs.koog.ai/)
- [Koog GitHub](https://github.com/JetBrains/koog)
- [Koog API Reference](https://api.koog.ai/)
- [JetBrains AI Blog](https://blog.jetbrains.com/ai/)
