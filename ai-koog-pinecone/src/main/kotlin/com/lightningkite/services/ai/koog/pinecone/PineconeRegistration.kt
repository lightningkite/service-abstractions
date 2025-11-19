package com.lightningkite.services.ai.koog.pinecone

import com.lightningkite.services.ai.koog.rag.VectorStorageSettings
import io.pinecone.clients.Pinecone
import java.net.URI

/**
 * Registers Pinecone as an available implementation for [VectorStorageSettings].
 *
 * Pinecone is a fully managed vector database service optimized for similarity search
 * at scale, providing low-latency queries and automatic scaling.
 *
 * ## VectorStorage
 *
 * URL format: `pinecone://index-name?apiKey=...&namespace=...&param=value`
 *
 * Supported parameters:
 * - apiKey: Pinecone API key (required, can use ${ENV_VAR} syntax)
 * - namespace: Namespace for vector isolation/multi-tenancy (optional, default: empty string)
 *
 * Before using Pinecone:
 * 1. Create a Pinecone account at https://www.pinecone.io/
 * 2. Create an index with the appropriate dimension matching your embedding model
 * 3. Note your API key from the Pinecone console
 * 4. Optionally create namespaces for multi-tenant applications
 *
 * Example configurations:
 * ```
 * # Basic Pinecone configuration with environment variable
 * pinecone://my-index?apiKey=${PINECONE_API_KEY}
 *
 * # With namespace for multi-tenancy
 * pinecone://embeddings?apiKey=${PINECONE_API_KEY}&namespace=tenant-123
 * ```
 *
 * Common vector dimensions by embedding model:
 * - OpenAI text-embedding-3-small: 1536 dimensions
 * - OpenAI text-embedding-3-large: 3072 dimensions
 * - Cohere embed-english-v3.0: 1024 dimensions
 * - Sentence Transformers all-MiniLM-L6-v2: 384 dimensions
 * - Ollama nomic-embed-text: 768 dimensions
 *
 * ## Index Creation
 *
 * You must create a Pinecone index before using this implementation:
 *
 * ```bash
 * # Using Pinecone CLI
 * pinecone create-index my-index --dimension 1536 --metric cosine
 *
 * # Or via Python SDK
 * import pinecone
 * pinecone.create_index("my-index", dimension=1536, metric="cosine")
 * ```
 *
 * Supported distance metrics:
 * - `cosine`: Cosine similarity (recommended for most use cases)
 * - `euclidean`: Euclidean distance
 * - `dotproduct`: Dot product similarity
 */
public object PineconeRegistration {
    init {
        // Register Pinecone VectorStorage
        VectorStorageSettings.register("pinecone") { name, url, context ->
            val uri = URI(url)
            val params = parseQueryParams(url)

            // Extract index name from host part of URL
            val indexName = uri.host?.takeIf { it.isNotEmpty() }
                ?: uri.authority?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Pinecone index name must be specified in URL: pinecone://index-name?...")

            // Extract API key (required)
            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("PINECONE_API_KEY")
                ?: throw IllegalArgumentException("Pinecone API key not provided in URL or PINECONE_API_KEY environment variable")

            // Extract optional parameters
            val namespace = params["namespace"] ?: ""

            // Create Pinecone client
            val pinecone = Pinecone.Builder(apiKey).build()

            // Create the vector storage instance
            PineconeVectorStorage<Any>(
                client = pinecone,
                indexName = indexName,
                namespace = namespace
            )
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

    /**
     * Parses query parameters from a URL string.
     */
    private fun parseQueryParams(url: String): Map<String, String> {
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
     * Resolves environment variable references in the format ${VAR_NAME}.
     */
    private fun resolveEnvVars(value: String): String {
        val envVarPattern = """\$\{([^}]+)\}""".toRegex()
        return envVarPattern.replace(value) { matchResult ->
            val varName = matchResult.groupValues[1]
            System.getenv(varName) ?: matchResult.value
        }
    }
}

// Trigger initialization by referencing the object
private val register = PineconeRegistration
