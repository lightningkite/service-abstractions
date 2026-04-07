package com.lightningkite.services.ai.koog.rag

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.ai.koog.LLMClientAndModel.Settings
import com.lightningkite.services.ai.koog.LLMClientAndModel.Settings.Companion.knownModels
import com.lightningkite.services.ai.koog.OllamaManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Settings for instantiating Koog's [Embedder].
 *
 * Embedders convert text into vector embeddings for semantic search and RAG applications.
 * This Settings class provides URL-based configuration for various embedding providers.
 *
 * The URL scheme determines which embedding provider to use:
 * - `ollama://model-name?baseUrl=...` - Ollama (local, no API key required)
 * - `ollama-auto://model-name?baseUrl=...` - Ollama with auto-start and auto-pull
 * - `openai://model-name?apiKey=...` - OpenAI embeddings
 *
 * Example usage:
 * ```kotlin
 * // Ollama (local)
 * val settings = EmbedderSettings("ollama://nomic-embed-text")
 * val embedder = settings("my-embedder", context)
 *
 * // OpenAI
 * val settings = EmbedderSettings("openai://text-embedding-3-small?apiKey=\${OPENAI_API_KEY}")
 * val embedder = settings("my-embedder", context)
 *
 * // Use the embedder
 * val vector = embedder.embed("Some text to embed")
 * ```
 *
 * @property url Connection string defining the embedding provider and configuration
 */
@Serializable
@JvmInline
public value class EmbedderSettings(
    public val url: String
) : Setting<Embedder> {

    public companion object : UrlSettingParser<Embedder>() {
        public fun openai(model: LLModel, apiKey: String? = null): EmbedderSettings = EmbedderSettings("openai://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun anthropic(model: LLModel, apiKey: String? = null): EmbedderSettings = EmbedderSettings("anthropic://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun google(model: LLModel, apiKey: String? = null): EmbedderSettings = EmbedderSettings("google://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun ollama(model: LLModel, apiKey: String? = null): EmbedderSettings = EmbedderSettings("ollama://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))
        public fun openrouter(model: LLModel, apiKey: String? = null): EmbedderSettings = EmbedderSettings("openrouter://${model.id}" + (apiKey?.let { "?apiKey=$it" } ?: ""))

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
            region: String? = null
        ): Settings =
            Settings("bedrock://${accessKeyId}:${secretAccessKey}@${model.id}" + (region?.let { "?region=$it" } ?: ""))

        public fun bedrock(
            model: LLModel,
            profile: String,
            region: String? = null
        ): Settings =
            Settings("bedrock://${profile}@${model.id}" + (region?.let { "?region=$it" } ?: ""))

        /**
         * Creates settings for Ollama embeddings with automatic server start and model pull.
         *
         * This is a convenience method that automatically:
         * - Starts the Ollama server if not running
         * - Pulls the embedding model if not available locally
         *
         * @param model The embedding model to use
         * @param baseUrl Ollama server URL (default: http://localhost:11434)
         */
        public fun ollamaAuto(model: LLModel, baseUrl: String? = null): EmbedderSettings =
            EmbedderSettings("ollama-auto://${model.id}" + (baseUrl?.let { "?baseUrl=$it" } ?: ""))

        init {

            // Register OpenAI
            register("openai") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("OPENAI_API_KEY")
                    ?: throw IllegalArgumentException("OpenAI API key not provided in URL or OPENAI_API_KEY environment variable")

                val client = OpenAILLMClient(apiKey = apiKey)
                val model = knownModels[client.llmProvider() to modelName]
                    ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMEmbedder(client, model)
            }

            // Register Google (Gemini)
            register("google") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                    ?: System.getenv("GOOGLE_API_KEY")
                    ?: throw IllegalArgumentException("Google API key not provided in URL or GOOGLE_API_KEY environment variable")

                val client = GoogleLLMClient(apiKey = apiKey)
                val model = knownModels[client.llmProvider() to modelName]
                    ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMEmbedder(client, model)
            }

            // Register Ollama
            register("ollama") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val baseUrl = params["baseUrl"] ?: "http://localhost:11434"
                val autoStart = params["autoStart"]?.toBooleanStrictOrNull() ?: false
                val autoPull = params["autoPull"]?.toBooleanStrictOrNull() ?: autoStart

                // Handle auto-start and auto-pull if requested
                if (autoStart || autoPull) {
                    val manager = OllamaManager(baseUrl)
                    runBlocking {
                        manager.ensureReady(
                            model = modelName,
                            startServer = autoStart,
                            pullModel = autoPull
                        )
                    }
                }

                val client = OllamaClient(baseUrl = baseUrl)
                val model = knownModels[client.llmProvider() to modelName]
                    ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMEmbedder(client, model)
            }

            // Register Ollama with auto-start enabled by default
            register("ollama-auto") { name, url, context ->
                val params = parseUrlParams(url)
                val modelName = url.substringAfter("://", "").substringBefore("?")
                val baseUrl = params["baseUrl"] ?: "http://localhost:11434"
                val autoStart = params["autoStart"]?.toBooleanStrictOrNull() ?: true
                val autoPull = params["autoPull"]?.toBooleanStrictOrNull() ?: true

                // Auto-start and auto-pull by default
                val manager = OllamaManager(baseUrl)
                runBlocking {
                    manager.ensureReady(
                        model = modelName,
                        startServer = autoStart,
                        pullModel = autoPull
                    )
                }

                val client = OllamaClient(baseUrl = baseUrl)
                val model = knownModels[client.llmProvider() to modelName]
                    ?: throw IllegalStateException("Unknown model '$modelName'.  Known model names: ${knownModels.keys}")
                LLMEmbedder(client, model)
            }

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
                    if(credentials.contains(':')) {
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
                LLMEmbedder(client, model)
            }
        }
    }

    override fun invoke(
        name: String,
        context: SettingContext
    ): Embedder {
        return parse(name, url, context)
    }
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