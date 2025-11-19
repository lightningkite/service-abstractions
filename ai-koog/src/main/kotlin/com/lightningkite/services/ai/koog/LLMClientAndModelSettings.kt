package com.lightningkite.services.ai.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OllamaModels
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.ai.koog.rag.EmbedderSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Clock

/**
 * Settings for instantiating a Koog [LLMClient].
 *
 * LLMClient is Koog's abstraction for LLM interactions. Koog provides built-in
 * executors for all major LLM providers, making it easy to switch between them.
 *
 * The URL scheme determines which LLM provider to use:
 * - `koog-openai://model-name?apiKey=...` - OpenAI (GPT models)
 * - `koog-anthropic://model-name?apiKey=...` - Anthropic (Claude models)
 * - `koog-google://model-name?apiKey=...` - Google (Gemini models)
 * - `koog-ollama://model-name?baseUrl=...` - Ollama (local models)
 * - `koog-openrouter://model-name?apiKey=...` - OpenRouter
 * - Other schemes registered by Koog or custom implementations
 *
 * Example usage:
 * ```kotlin
 * val settings = LLMClientSettings("koog-openai://gpt-4?apiKey=\${OPENAI_API_KEY}")
 * val executor: LLMClient = settings("my-executor", context)
 *
 * // Use with Koog's AIAgent
 * val agent = AIAgent(
 *     executor = executor,
 *     systemPrompt = "You are a helpful assistant.",
 *     llmModel = OpenAIModels.Chat.GPT4o
 * )
 * ```
 *
 * @property url Connection string defining the provider and configuration
 */
@Serializable
@JvmInline
public value class LLMClientAndModelSettings(
    public val url: String
) : Setting<LLMClientAndModel> {

    public companion object : UrlSettingParser<LLMClientAndModel>() {
        public fun openai(model: LLModel, apiKey: String? = null): LLMClientAndModelSettings = LLMClientAndModelSettings("openai://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun anthropic(model: LLModel, apiKey: String? = null): LLMClientAndModelSettings = LLMClientAndModelSettings("anthropic://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun google(model: LLModel, apiKey: String? = null): LLMClientAndModelSettings = LLMClientAndModelSettings("google://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun ollama(model: LLModel, apiKey: String? = null): LLMClientAndModelSettings = LLMClientAndModelSettings("ollama://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun openrouter(model: LLModel, apiKey: String? = null): LLMClientAndModelSettings = LLMClientAndModelSettings("openrouter://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))

        public val knownModels: Map<Pair<LLMProvider, String>, LLModel> = listOf(
            OpenAIModels.Moderation.Omni,
            OpenAIModels.Reasoning.O4Mini,
            OpenAIModels.Reasoning.O3Mini,
            OpenAIModels.Reasoning.O3,
            OpenAIModels.Reasoning.O1,
            OpenAIModels.Chat.GPT4o,
            OpenAIModels.Chat.GPT4_1,
            OpenAIModels.Chat.GPT5,
            OpenAIModels.Chat.GPT5Mini,
            OpenAIModels.Chat.GPT5Nano,
            OpenAIModels.Chat.GPT5Codex,
            OpenAIModels.Audio.GptAudio,
            OpenAIModels.Audio.GPT4oMiniAudio,
            OpenAIModels.Audio.GPT4oAudio,
            OpenAIModels.CostOptimized.GPT4_1Nano,
            OpenAIModels.CostOptimized.GPT4_1Mini,
            OpenAIModels.CostOptimized.GPT4oMini,
            OpenAIModels.CostOptimized.O4Mini,
            OpenAIModels.CostOptimized.O3Mini,
            OpenAIModels.Embeddings.TextEmbedding3Small,
            OpenAIModels.Embeddings.TextEmbedding3Large,
            OpenAIModels.Embeddings.TextEmbeddingAda002,
            BedrockModels.AnthropicClaude3Opus,
            BedrockModels.AnthropicClaude4Opus,
            BedrockModels.AnthropicClaude41Opus,
            BedrockModels.AnthropicClaude4Sonnet,
            BedrockModels.AnthropicClaude4_5Sonnet,
            BedrockModels.AnthropicClaude4_5Haiku,
            BedrockModels.AnthropicClaude3Sonnet,
            BedrockModels.AnthropicClaude35SonnetV2,
            BedrockModels.AnthropicClaude35Haiku,
            BedrockModels.AnthropicClaude3Haiku,
            BedrockModels.AnthropicClaude21,
            BedrockModels.AnthropicClaudeInstant,
            BedrockModels.AmazonNovaMicro,
            BedrockModels.AmazonNovaLite,
            BedrockModels.AmazonNovaPro,
            BedrockModels.AmazonNovaPremier,
            BedrockModels.AI21JambaLarge,
            BedrockModels.AI21JambaMini,
            BedrockModels.MetaLlama3_0_8BInstruct,
            BedrockModels.MetaLlama3_0_70BInstruct,
            BedrockModels.MetaLlama3_1_8BInstruct,
            BedrockModels.MetaLlama3_1_70BInstruct,
            BedrockModels.MetaLlama3_1_405BInstruct,
            BedrockModels.MetaLlama3_2_1BInstruct,
            BedrockModels.MetaLlama3_2_3BInstruct,
            BedrockModels.MetaLlama3_2_11BInstruct,
            BedrockModels.MetaLlama3_2_90BInstruct,
            BedrockModels.MetaLlama3_3_70BInstruct,
            BedrockModels.Embeddings.AmazonTitanEmbedText,
            BedrockModels.Embeddings.AmazonTitanEmbedTextV2,
            BedrockModels.Embeddings.CohereEmbedEnglishV3,
            BedrockModels.Embeddings.CohereEmbedMultilingualV3,
            OllamaModels.Groq.LLAMA_3_GROK_TOOL_USE_8B,
            OllamaModels.Groq.LLAMA_3_GROK_TOOL_USE_70B,
            OllamaModels.Meta.LLAMA_3_2_3B,
            OllamaModels.Meta.LLAMA_3_2,
            OllamaModels.Meta.LLAMA_4_SCOUT,
            OllamaModels.Meta.LLAMA_4,
            OllamaModels.Meta.LLAMA_GUARD_3,
            OllamaModels.Alibaba.QWEN_2_5_05B,
            OllamaModels.Alibaba.QWEN_3_06B,
            OllamaModels.Alibaba.QWQ_32B,
            OllamaModels.Alibaba.QWQ,
            OllamaModels.Alibaba.QWEN_CODER_2_5_32B,
            OllamaModels.Granite.GRANITE_3_2_VISION,
            AnthropicModels.Opus_3,
            AnthropicModels.Haiku_3,
            AnthropicModels.Sonnet_3_5,
            AnthropicModels.Haiku_3_5,
            AnthropicModels.Sonnet_3_7,
            AnthropicModels.Sonnet_4,
            AnthropicModels.Opus_4,
            AnthropicModels.Opus_4_1,
            AnthropicModels.Sonnet_4_5,
            AnthropicModels.Haiku_4_5,
            GoogleModels.Gemini2_0Flash,
            GoogleModels.Gemini2_0Flash001,
            GoogleModels.Gemini2_0FlashLite,
            GoogleModels.Gemini2_0FlashLite001,
            GoogleModels.Gemini2_5Pro,
            GoogleModels.Gemini2_5Flash,
            GoogleModels.Gemini2_5FlashLite,
            DeepSeekModels.DeepSeekChat,
            DeepSeekModels.DeepSeekReasoner,
            OpenRouterModels.Phi4Reasoning,
            OpenRouterModels.Claude3Opus,
            OpenRouterModels.Claude3Sonnet,
            OpenRouterModels.Claude3Haiku,
            OpenRouterModels.Claude3_5Sonnet,
            OpenRouterModels.Claude3_7Sonnet,
            OpenRouterModels.Claude4Sonnet,
            OpenRouterModels.Claude4_1Opus,
            OpenRouterModels.GPT4oMini,
            OpenRouterModels.GPT5Chat,
            OpenRouterModels.GPT5,
            OpenRouterModels.GPT5Mini,
            OpenRouterModels.GPT5Nano,
            OpenRouterModels.GPT_OSS_120b,
            OpenRouterModels.GPT4,
            OpenRouterModels.GPT4o,
            OpenRouterModels.GPT4Turbo,
            OpenRouterModels.GPT35Turbo,
            OpenRouterModels.Llama3,
            OpenRouterModels.Llama3Instruct,
            OpenRouterModels.Mistral7B,
            OpenRouterModels.Mixtral8x7B,
            OpenRouterModels.Claude3VisionSonnet,
            OpenRouterModels.Claude3VisionOpus,
            OpenRouterModels.Claude3VisionHaiku,
            OpenRouterModels.DeepSeekV30324,
            OpenRouterModels.Gemini2_5FlashLite,
            OpenRouterModels.Gemini2_5Flash,
            OpenRouterModels.Gemini2_5Pro,
            OpenRouterModels.Qwen2_5,
        ).associateBy { it.provider to it.id }

        init {
            // Register OpenAI
            register("openai") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("OPENAI_API_KEY")
                    ?: throw IllegalArgumentException("OpenAI API key not provided in URL or OPENAI_API_KEY environment variable")

                //apiKey: kotlin.String, settings: ai.koog.prompt.executor.clients.openai.OpenAIClientSettings = COMPILED_CODE, baseClient: io.ktor.client.HttpClient = COMPILED_CODE, clock: kotlinx.datetime.Clock = COMPILED_CODE
                OpenAILLMClient::class.java.constructors.forEach {
                    println("CTOR: ${it.name} ${it.parameters.joinToString { it.type.toString() }}")
                }
                val client = OpenAILLMClient(

                    apiKey = apiKey,
                    settings = OpenAIClientSettings(),
                    baseClient = HttpClient(),
                    clock = Clock.System,
                )
                val model = knownModels.get(client.llmProvider() to modelName) ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMClientAndModel(client, model)
            }

            // Register Anthropic
            register("anthropic") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("ANTHROPIC_API_KEY")
                    ?: throw IllegalArgumentException("Anthropic API key not provided in URL or ANTHROPIC_API_KEY environment variable")

                val client = AnthropicLLMClient(apiKey = apiKey)
                val model = knownModels.get(client.llmProvider() to modelName) ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMClientAndModel(client, model)
            }

            // Register Google (Gemini)
            register("google") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("GOOGLE_API_KEY")
                    ?: throw IllegalArgumentException("Google API key not provided in URL or GOOGLE_API_KEY environment variable")

                val client = GoogleLLMClient(apiKey = apiKey)
                val model = knownModels.get(client.llmProvider() to modelName) ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMClientAndModel(client, model)
            }

            // Register Ollama
            register("ollama") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val baseUrl = params["baseUrl"] ?: "http://localhost:11434"

                val client = OllamaClient(baseUrl = baseUrl)
                val model = knownModels.get(client.llmProvider() to modelName) ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMClientAndModel(client, model)
            }

            // Register OpenRouter
            register("openrouter") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("OPENROUTER_API_KEY")
                    ?: throw IllegalArgumentException("OpenRouter API key not provided in URL or OPENROUTER_API_KEY environment variable")

                val client = OpenRouterLLMClient(apiKey = apiKey)
                val model = knownModels.get(client.llmProvider() to modelName) ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMClientAndModel(client, model)
            }
        }
    }

    override fun invoke(name: String, context: SettingContext): LLMClientAndModel {
        return parse(name, url, context)
    }
}

public data class LLMClientAndModel(val client: LLMClient, val model: LLModel) {
    /**
     * Executes a prompt and returns a list of response messages.
     *
     * @param prompt The prompt to execute
     * @param model The LLM model to use
     * @param tools Optional list of tools that can be used by the LLM
     * @return List of response messages
     */
    public suspend fun execute(
        prompt: Prompt,
        tools: List<ToolDescriptor> = emptyList()
    ): List<Message.Response> = client.execute(prompt, model, tools)

    /**
     * Executes a prompt and returns a streaming flow of response chunks.
     *
     * @param prompt The prompt to execute
     * @param model The LLM model to use
     * @param tools Optional list of tools that can be used by the LLM
     * @return Flow of response chunks
     */
    public fun executeStreaming(
        prompt: Prompt,
        tools: List<ToolDescriptor> = emptyList()
    ): Flow<StreamFrame> = client.executeStreaming(prompt, model, tools)

    /**
     * Executes a prompt and returns a list of LLM choices.
     *
     * @param prompt The prompt to execute
     * @param tools Optional list of tools that can be used by the LLM
     * @param model The LLM model to use
     *  @return List of LLM choices
     */
    public suspend fun executeMultipleChoices(
        prompt: Prompt,
        tools: List<ToolDescriptor> = emptyList()
    ): List<LLMChoice> = client.executeMultipleChoices(prompt, model, tools)

    /**
     * Analyzes the provided prompt for violations of content policies or other moderation criteria.
     *
     * @param prompt The input prompt to be analyzed for moderation.
     * @param model The language model to be used for conducting the moderation analysis.
     * @return The result of the moderation analysis, encapsulated in a ModerationResult object.
     */
    public suspend fun moderate(prompt: Prompt): ModerationResult = client.moderate(prompt, model)
}

/**
 * Parses URL query parameters into a map.
 */
private fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()

    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
}

/**
 * Resolves environment variables in parameter values.
 *
 * Replaces ${ENV_VAR} with the value from System.getenv("ENV_VAR")
 */
private fun resolveEnvVars(value: String): String {
    val envVarPattern = """\$\{([^}]+)\}""".toRegex()
    return envVarPattern.replace(value) { matchResult ->
        val envVar = matchResult.groupValues[1]
        System.getenv(envVar) ?: matchResult.value
    }
}