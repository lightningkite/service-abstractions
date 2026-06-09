package com.lightningkite.services.ai.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.bedrock.*
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.*
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.*
import ai.koog.prompt.message.*
import ai.koog.prompt.streaming.StreamFrame
import aws.sdk.kotlin.runtime.auth.credentials.*
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import com.lightningkite.services.*
import io.ktor.client.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Deprecated("Use LLMClientAndModelSettings instead", ReplaceWith("LLMClientAndModel.Settings"))
public typealias LLMClientAndModelSettings = LLMClientAndModel.Settings

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
        tools: List<ToolDescriptor> = emptyList(),
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
        tools: List<ToolDescriptor> = emptyList(),
    ): Flow<StreamFrame> = client.executeStreaming(prompt, model, tools)

    /**
     * Executes a prompt and returns a list of LLM choices.
     *
     * @param prompt The prompt to execute
     * @param tools Optional list of tools that can be used by the LLM
     * @return List of LLM choices
     */
    public suspend fun executeMultipleChoices(
        prompt: Prompt,
        tools: List<ToolDescriptor> = emptyList(),
    ): List<LLMChoice> = client.executeMultipleChoices(prompt, model, tools)

    /**
     * Analyzes the provided prompt for violations of content policies or other moderation criteria.
     *
     * @param prompt The input prompt to be analyzed for moderation.
     * @param model The language model to be used for conducting the moderation analysis.
     * @return The result of the moderation analysis, encapsulated in a ModerationResult object.
     */
    public suspend fun moderate(prompt: Prompt): ModerationResult = client.moderate(prompt, model)


    /**
     * Settings for instantiating a Koog [LLMClient].
     *
     * LLMClient is Koog's abstraction for LLM interactions. Koog provides built-in
     * executors for all major LLM providers, making it easy to switch between them.
     *
     * The URL scheme determines which LLM provider to use:
     * - `openai://model-name?apiKey=...` - OpenAI (GPT models)
     * - `anthropic://model-name?apiKey=...` - Anthropic (Claude models)
     * - `google://model-name?apiKey=...` - Google (Gemini models)
     * - `ollama://model-name?baseUrl=...` - Ollama (local models)
     * - `ollama-auto://model-name?baseUrl=...` - Ollama with auto-start/auto-pull
     * - `openrouter://model-name?apiKey=...` - OpenRouter
     * - `bedrock://model-name?region=...` - AWS Bedrock
     * - `mock://` - Mock client for testing
     *
     * Example usage:
     * ```kotlin
     * val settings = LLMClientAndModel.Settings("openai://gpt-4o?apiKey=\${OPENAI_API_KEY}")
     * val llm: LLMClientAndModel = settings("my-llm", context)
     *
     * // Use the client and model together
     * val response = llm.execute(prompt)
     * ```
     *
     * @property url Connection string defining the provider and configuration
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String,
    ) : Setting<LLMClientAndModel> {

        public companion object : UrlSettingParser<LLMClientAndModel>() {
            public fun openai(model: LLModel, apiKey: String? = null): Settings =
                Settings("openai://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))

            public fun anthropic(model: LLModel, apiKey: String? = null): Settings =
                Settings("anthropic://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))

            public fun google(model: LLModel, apiKey: String? = null): Settings =
                Settings("google://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))

            public fun ollama(model: LLModel, apiKey: String? = null): Settings =
                Settings("ollama://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))

            public fun openrouter(model: LLModel, apiKey: String? = null): Settings =
                Settings("openrouter://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))

            /**
             * Creates settings for AWS Bedrock using the default credential chain.
             *
             * Uses the AWS default credential chain (environment variables, IAM role, etc.)
             * which is appropriate for Lambda, EC2, ECS, and other AWS environments.
             *
             * @param model The Bedrock model to use (from BedrockModels)
             * @param region Optional AWS region override (defaults to AWS_REGION env var or us-east-1)
             */
            public fun bedrock(model: LLModel, region: String? = null): Settings =
                Settings("bedrock://${model.id}" + (region?.let { "?region=$it" } ?: ""))

            /**
             * Creates settings for AWS Bedrock with explicit static credentials.
             *
             * Use this when you need to provide AWS credentials explicitly rather than
             * relying on the default credential chain. Supports environment variable
             * resolution using ${VAR_NAME} syntax.
             *
             * @param model The Bedrock model to use (from BedrockModels)
             * @param accessKeyId AWS access key ID (or ${ENV_VAR} reference)
             * @param secretAccessKey AWS secret access key (or ${ENV_VAR} reference)
             * @param region Optional AWS region override (defaults to AWS_REGION env var or us-east-1)
             */
            public fun bedrock(
                model: LLModel,
                accessKeyId: String,
                secretAccessKey: String,
                region: String? = null,
            ): Settings =
                Settings("bedrock://${accessKeyId}:${secretAccessKey}@${model.id}" + (region?.let { "?region=$it" }
                    ?: ""))

            public fun bedrock(
                model: LLModel,
                profile: String,
                region: String? = null,
            ): Settings =
                Settings("bedrock://${profile}@${model.id}" + (region?.let { "?region=$it" }
                    ?: ""))

            /**
             * Creates settings for Ollama with automatic server start and model pull.
             *
             * This is a convenience method that automatically:
             * - Starts the Ollama server if not running
             * - Pulls the model if not available locally
             *
             * @param model The model to use
             * @param baseUrl Ollama server URL (default: http://localhost:11434)
             */
            public fun ollamaAuto(model: LLModel, baseUrl: String? = null): Settings =
                Settings("ollama-auto://${model.id}" + (baseUrl?.let { "?baseUrl=$it" } ?: ""))

            public val knownModels: Map<Pair<LLMProvider, String>, LLModel> = listOf(
                OpenAIModels.Moderation.Omni,
                OpenAIModels.Chat.O4Mini,
                OpenAIModels.Chat.O3Mini,
                OpenAIModels.Chat.O3,
                OpenAIModels.Chat.O1,
                OpenAIModels.Chat.GPT4o,
                OpenAIModels.Chat.GPT4_1,
                OpenAIModels.Chat.GPT5,
                OpenAIModels.Chat.GPT5Mini,
                OpenAIModels.Chat.GPT5Nano,
                OpenAIModels.Chat.GPT5Codex,
                OpenAIModels.Audio.GptAudio,
                OpenAIModels.Audio.GPT4oMiniAudio,
                OpenAIModels.Audio.GPT4oAudio,
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

            /**
             * Registers a `scheme://model-name?apiKey=...` provider. The apiKey query parameter
             * supports `${ENV_VAR}` substitution; if absent the [envVar] environment variable is
             * consulted instead. Throws if neither source supplies a key.
             */
            private fun registerApiKeyProvider(
                scheme: String,
                envVar: String,
                providerLabel: String,
                build: (apiKey: String) -> LLMClient,
            ) {
                register(scheme) { _, url, _ ->
                    val params = parseUrlParams(url)
                    val modelName = url.substringAfter("://", "").substringBefore("?")
                    val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                        ?: System.getenv(envVar)
                        ?: throw IllegalArgumentException(
                            "$providerLabel API key not provided in URL or $envVar environment variable"
                        )
                    val client = build(apiKey)
                    val model = knownModels[client.llmProvider() to modelName]
                        ?: throw IllegalStateException(
                            "Unknown model '$modelName'.  Known model names: ${knownModels.keys}"
                        )
                    LLMClientAndModel(client, model)
                }
            }

            init {
                register("mock") { name, url, context ->
                    val p = object : LLMProvider("mock", "Mock") {}
                    LLMClientAndModel(
                        object : LLMClient {
                            override suspend fun execute(
                                prompt: Prompt,
                                model: LLModel,
                                tools: List<ToolDescriptor>,
                            ): List<Message.Response> = listOf(
                                Message.Assistant(
                                    "Mock LLM used, no real response possible.",
                                    ResponseMetaInfo(Clock.System.now())
                                )
                            )

                            override fun close() {}
                            override fun llmProvider(): LLMProvider = p
                            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
                                ModerationResult(false, mapOf())

                            override suspend fun executeMultipleChoices(
                                prompt: Prompt,
                                model: LLModel,
                                tools: List<ToolDescriptor>,
                            ): List<LLMChoice> = listOf()

                            override fun executeStreaming(
                                prompt: Prompt,
                                model: LLModel,
                                tools: List<ToolDescriptor>,
                            ): Flow<StreamFrame> = emptyFlow()

                            override suspend fun models(): List<LLModel> = listOf(
                                LLModel(
                                    p,
                                    "mock",
                                    listOf(LLMCapability.Embed, LLMCapability.Tools, LLMCapability.Completion),
                                    100_000,
                                    null
                                )
                            )
                        },
                        LLModel(p, "mock", listOf(), 0L, 0L)
                    )
                }

                // TODO: share each provider's HttpClient via a SharedResources.Key rather than
                //       constructing a fresh one per Setting invocation (each instance has its
                //       own connection pool). Currently only OpenAILLMClient is given an explicit
                //       HttpClient; the other Koog clients build their own.
                registerApiKeyProvider("openai", "OPENAI_API_KEY", "OpenAI") { apiKey ->
                    OpenAILLMClient(
                        apiKey = apiKey,
                        settings = OpenAIClientSettings(),
                        baseClient = HttpClient(),
                        clock = Clock.System,
                    )
                }
                registerApiKeyProvider("anthropic", "ANTHROPIC_API_KEY", "Anthropic") { AnthropicLLMClient(apiKey = it) }
                registerApiKeyProvider("google", "GOOGLE_API_KEY", "Google") { GoogleLLMClient(apiKey = it) }

                // Register Ollama
                register("ollama") { name, url, context ->
                    val params = parseUrlParams(url)
                    val modelName = url.substringAfter("://", "").substringBefore("?")
                    val baseUrl = params["baseUrl"] ?: "http://localhost:11434"
                    val autoStart = params["autoStart"]?.toBooleanStrictOrNull() ?: false
                    val autoPull = params["autoPull"]?.toBooleanStrictOrNull() ?: autoStart

                    // Handle auto-start and auto-pull if requested.
                    // runBlocking is intentional here: the Setting factory is synchronous and
                    // ensureReady may perform I/O. Consider lazy init via Deferred if startup
                    // latency is a concern.
                    if (autoStart || autoPull) {
                        val manager = OllamaManager(baseUrl)
                        kotlinx.coroutines.runBlocking {
                            manager.ensureReady(
                                model = modelName,
                                startServer = autoStart,
                                pullModel = autoPull
                            )
                        }
                    }

                    val client = OllamaClient(baseUrl = baseUrl)
                    val model = knownModels.get(client.llmProvider() to modelName)
                        ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                    LLMClientAndModel(client, model)
                }

                // Register Ollama with auto-start enabled by default
                register("ollama-auto") { name, url, context ->
                    val params = parseUrlParams(url)
                    val modelName = url.substringAfter("://", "").substringBefore("?")
                    val baseUrl = params["baseUrl"] ?: "http://localhost:11434"
                    val autoStart = params["autoStart"]?.toBooleanStrictOrNull() ?: true
                    val autoPull = params["autoPull"]?.toBooleanStrictOrNull() ?: true

                    // Auto-start and auto-pull by default.
                    // runBlocking is intentional here: the Setting factory is synchronous and
                    // ensureReady may perform I/O. Consider lazy init via Deferred if startup
                    // latency is a concern.
                    val manager = OllamaManager(baseUrl)
                    kotlinx.coroutines.runBlocking {
                        manager.ensureReady(
                            model = modelName,
                            startServer = autoStart,
                            pullModel = autoPull
                        )
                    }

                    val client = OllamaClient(baseUrl = baseUrl)
                    val model = knownModels.get(client.llmProvider() to modelName)
                        ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                    LLMClientAndModel(client, model)
                }

                registerApiKeyProvider("openrouter", "OPENROUTER_API_KEY", "OpenRouter") { OpenRouterLLMClient(apiKey = it) }

                // Register AWS Bedrock
                // URL formats:
                //   bedrock://{model-id}?region={region}  - Uses AWS default credential chain
                //   bedrock://{accessKeyId}:{secretKey}@{model-id}?region={region}  - Static credentials
                register("bedrock") { _, url, _ ->
                    val params = parseUrlParams(url)
                    val region = params["region"]?.let(::resolveEnvVars)
                        ?: System.getenv("AWS_REGION")
                        ?: "us-east-1"

                    // Parse the authority part to check for credentials
                    val authority = url.substringAfter("://", "").substringBefore("?")
                    val (credentialsProvider, modelName) = if (authority.contains("@")) {
                        // Format: accessKeyId:secretKey@model-id
                        val credentials = authority.substringBefore("@")
                        val model = authority.substringAfter("@")
                        if (credentials.contains(':')) {
                            val accessKeyId = resolveEnvVars(credentials.substringBefore(":"))
                            val secretKey = resolveEnvVars(credentials.substringAfter(":"))
                            StaticCredentialsProvider(Credentials(accessKeyId, secretKey)) to model
                        } else {
                            ProfileCredentialsProvider(credentials) to model
                        }
                    } else {
                        // No credentials in URL - use default chain
                        // This automatically picks up IAM role credentials in Lambda/EC2/ECS
                        DefaultChainCredentialsProvider() to authority
                    }

                    val settings = BedrockClientSettings(region = region)
                    val client = BedrockLLMClient(
                        identityProvider = credentialsProvider,
                        settings = settings
                    )
                    val model = knownModels[client.llmProvider() to modelName]
                        ?: throw IllegalStateException("Unknown Bedrock model '$modelName'. Known Bedrock models: ${knownModels.keys.filter { it.first.id == "bedrock" }}")
                    LLMClientAndModel(client, model)
                }
            }
        }

        override fun invoke(name: String, context: SettingContext): LLMClientAndModel {
            val owner = object : Namespaced {
                override val name: String = name
                override val context: SettingContext = context
            }
            return parse(name, url, context).withTracing(owner)
        }
    }
}

/**
 * Wraps an [LLMClient] to record a metrics span around every call.
 *
 * Span attributes follow the OpenTelemetry semantic conventions for generative AI:
 * - `ai.provider` — value of [LLMClient.llmProvider]
 * - `ai.model` — model ID
 *
 * Note: token counts (`llm.token_count.input` / `llm.token_count.output`) are not yet
 * populated because Koog's [Message.Response] does not currently expose usage metadata.
 * TODO: add token attributes once Koog exposes usage in response metadata.
 */
internal class TracingLLMClient(
    private val delegate: LLMClient,
    private val owner: Namespaced,
    private val modelId: String,
) : LLMClient {

    private suspend inline fun <R> traced(operation: String, crossinline block: suspend () -> R): R =
        owner.metricsTrace(
            operation,
            attributes = MetricAttributes(
                mapOf(
                    "ai.provider" to delegate.llmProvider().id,
                    "ai.model" to modelId,
                )
            )
        ) { block() }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> = traced("execute") { delegate.execute(prompt, model, tools) }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)
    // TODO: wrap streaming with span — requires collecting the flow which changes semantics.

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<LLMChoice> = traced("executeMultipleChoices") { delegate.executeMultipleChoices(prompt, model, tools) }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        traced("moderate") { delegate.moderate(prompt, model) }

    override suspend fun models(): List<LLModel> = delegate.models()
    override fun llmProvider(): LLMProvider = delegate.llmProvider()
    override fun close() = delegate.close()
}

/**
 * Wraps this [LLMClientAndModel] with a metrics-span tracing layer scoped to [owner].
 * Span/metric emission is a no-op when no metrics backend is configured.
 */
internal fun LLMClientAndModel.withTracing(owner: Namespaced): LLMClientAndModel =
    LLMClientAndModel(TracingLLMClient(client, owner, model.id), model)

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