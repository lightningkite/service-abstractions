package com.lightningkite.services.ai

import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import dev.langchain4j.model.chat.ChatLanguageModel
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Settings for instantiating a LangChain4J [ChatLanguageModel].
 *
 * ChatLanguageModel provides synchronous chat completions with language models.
 *
 * The URL scheme determines which LLM provider implementation to use:
 * - `openai-chat://model-name?apiKey=...` - OpenAI (GPT models)
 * - `anthropic-chat://model-name?apiKey=...` - Anthropic (Claude models)
 * - `ollama-chat://model-name?baseUrl=...` - Ollama (local models)
 * - Other schemes registered by implementation modules
 *
 * Example usage:
 * ```kotlin
 * val settings = ChatLanguageModelSettings("openai-chat://gpt-4-turbo?apiKey=\${OPENAI_API_KEY}")
 * val model: ChatLanguageModel = settings("my-chat-model", context)
 * val response = model.generate("Explain quantum computing")
 * ```
 *
 * @property url Connection string defining the provider and configuration
 */
@Serializable
@JvmInline
public value class ChatLanguageModelSettings(
    public val url: String
) : Setting<ChatLanguageModel> {

    public companion object : UrlSettingParser<ChatLanguageModel>()

    override fun invoke(name: String, context: SettingContext): ChatLanguageModel {
        return parse(name, url, context)
    }
}
