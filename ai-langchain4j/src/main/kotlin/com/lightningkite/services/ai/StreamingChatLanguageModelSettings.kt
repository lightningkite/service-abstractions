package com.lightningkite.services.ai

import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import dev.langchain4j.model.chat.StreamingChatLanguageModel
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Settings for instantiating a LangChain4J [StreamingChatLanguageModel].
 *
 * StreamingChatLanguageModel provides streaming chat completions, allowing you to receive
 * partial responses as they are generated (useful for displaying real-time output to users).
 *
 * The URL scheme determines which LLM provider implementation to use:
 * - `openai-chat://model-name?apiKey=...` - OpenAI (GPT models)
 * - `anthropic-chat://model-name?apiKey=...` - Anthropic (Claude models)
 * - `ollama-chat://model-name?baseUrl=...` - Ollama (local models)
 * - Other schemes registered by implementation modules
 *
 * Example usage:
 * ```kotlin
 * val settings = StreamingChatLanguageModelSettings("openai-chat://gpt-4-turbo?apiKey=\${OPENAI_API_KEY}")
 * val model: StreamingChatLanguageModel = settings("my-streaming-model", context)
 * model.generate("Tell me a story") { response ->
 *     response.onNext { token -> print(token) }
 *     response.onComplete { println("\nDone!") }
 * }
 * ```
 *
 * @property url Connection string defining the provider and configuration
 */
@Serializable
@JvmInline
public value class StreamingChatLanguageModelSettings(
    public val url: String
) : Setting<StreamingChatLanguageModel> {

    public companion object : UrlSettingParser<StreamingChatLanguageModel>()

    override fun invoke(name: String, context: SettingContext): StreamingChatLanguageModel {
        return parse(name, url, context)
    }
}
