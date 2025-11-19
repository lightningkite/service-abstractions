package com.lightningkite.services.ai.pinecone

import com.lightningkite.services.ai.*
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore

/**
 * Registers Pinecone vector database support for LangChain4J Settings.
 *
 * Pinecone is a managed vector database optimized for similarity search.
 *
 * ## EmbeddingStore
 *
 * URL format: `pinecone://index-name?apiKey=...&environment=...&param=value`
 *
 * Supported parameters:
 * - apiKey: Pinecone API key (required, can use ${ENV_VAR} syntax)
 * - environment: Pinecone environment (e.g., us-east-1-gcp, default: us-east-1)
 *
 * Before using Pinecone, you must:
 * 1. Create a Pinecone account at https://www.pinecone.io/
 * 2. Create an index with the appropriate dimension (matching your embedding model)
 * 3. Get your API key from the Pinecone console
 *
 * Example:
 * ```
 * pinecone://my-index?apiKey=${PINECONE_API_KEY}&environment=us-east-1
 * pinecone://embeddings?apiKey=pc-...&environment=us-east-1-gcp
 * ```
 *
 * Common index dimensions:
 * - OpenAI text-embedding-3-small: 1536 dimensions
 * - OpenAI text-embedding-3-large: 3072 dimensions
 * - Ollama nomic-embed-text: 768 dimensions
 */
public object PineconeRegistration {
    init {
        // Register EmbeddingStore
        EmbeddingStoreSettings.register("pinecone") { name, url, context ->
            val params = parseUrlParams(url)
            val indexName = extractModelName(url)

            if (indexName.isEmpty()) {
                throw IllegalArgumentException("Pinecone index name must be specified in URL: pinecone://index-name?...")
            }

            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("PINECONE_API_KEY")
                ?: throw IllegalArgumentException("Pinecone API key not provided in URL or PINECONE_API_KEY environment variable")

            val environment = params["environment"] ?: "us-east-1"

            // Note: Namespace support may vary by Pinecone SDK version
            // For now, we only support the core parameters
            @Suppress("DEPRECATION")
            PineconeEmbeddingStore.builder()
                .apiKey(apiKey)
                .environment(environment)
                .index(indexName)
                .build()
        }
    }

    /**
     * Ensures Pinecone implementations are registered.
     * Safe to call multiple times (idempotent).
     *
     * Note: Registration happens automatically when this class is loaded,
     * so calling this method is usually not necessary.
     */
    public fun ensure() {
        // Registration already happened in init block
    }
}

// Trigger initialization by referencing the object
private val register = PineconeRegistration
