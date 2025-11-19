package com.lightningkite.services.ai.koog.opensearch

import com.lightningkite.services.ai.koog.rag.VectorStorageSettings
import kotlinx.coroutines.runBlocking
import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import java.net.URI
import javax.net.ssl.SSLContext

/**
 * Registers AWS OpenSearch Service as an available implementation for [VectorStorageSettings].
 *
 * AWS OpenSearch Service is a managed service that provides vector search capabilities
 * through the k-NN (k-nearest neighbors) plugin, enabling similarity search for RAG applications.
 *
 * ## VectorStorage
 *
 * URL format: `opensearch://host:port/index-name?dimension=1536&user=admin&password=...&https=true`
 *
 * Supported parameters:
 * - dimension: Vector dimension (required, e.g., 1536 for OpenAI embeddings)
 * - user: Username for basic authentication (optional, can use ${ENV_VAR} syntax)
 * - password: Password for basic authentication (optional, can use ${ENV_VAR} syntax)
 * - https: Use HTTPS protocol (default: true)
 * - vectorField: Custom field name for vectors (default: "embedding")
 * - documentField: Custom field name for documents (default: "document")
 * - idField: Custom field name for IDs (default: "id")
 *
 * Before using AWS OpenSearch:
 * 1. Create an OpenSearch domain in AWS Console or via Terraform/CloudFormation
 * 2. Ensure k-NN plugin is enabled (enabled by default)
 * 3. Configure appropriate access policies or VPC settings
 * 4. Note your domain endpoint and credentials
 *
 * Example configurations:
 * ```
 * # AWS OpenSearch with environment variables
 * opensearch://my-domain.us-east-1.es.amazonaws.com:443/vectors?dimension=1536&user=${OPENSEARCH_USER}&password=${OPENSEARCH_PASSWORD}
 *
 * # Local OpenSearch for development
 * opensearch://localhost:9200/test-vectors?dimension=768&https=false&user=admin&password=admin
 *
 * # Custom field names
 * opensearch://my-domain.region.es.amazonaws.com:443/docs?dimension=1536&vectorField=vec&documentField=doc&user=admin&password=${PASS}
 * ```
 *
 * Common vector dimensions:
 * - OpenAI text-embedding-3-small: 1536 dimensions
 * - OpenAI text-embedding-3-large: 3072 dimensions
 * - Cohere embed-english-v3.0: 1024 dimensions
 * - Sentence Transformers all-MiniLM-L6-v2: 384 dimensions
 */
public object OpenSearchRegistration {
    init {
        // Register OpenSearch VectorStorage
        VectorStorageSettings.register("opensearch") { name, url, context ->
            val uri = URI(url)
            val params = parseQueryParams(url)

            // Extract host and port
            val host = uri.host ?: throw IllegalArgumentException("OpenSearch host must be specified in URL")
            val port = uri.port.takeIf { it > 0 } ?: 443
            val useHttps = params["https"]?.toBoolean() ?: true

            // Extract index name from path
            val indexName = uri.path.removePrefix("/").takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("OpenSearch index name must be specified in URL path: opensearch://host:port/index-name")

            // Extract vector dimension (required)
            val dimension = params["dimension"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Vector dimension must be specified: ?dimension=1536")

            // Extract authentication credentials
            val username = params["user"]?.let(::resolveEnvVars)
            val password = params["password"]?.let(::resolveEnvVars)

            // Extract custom field names
            val vectorField = params["vectorField"] ?: "embedding"
            val documentField = params["documentField"] ?: "document"
            val idField = params["idField"] ?: "id"

            // Build OpenSearch client
            val httpHost = HttpHost(if (useHttps) "https" else "http", host, port)

            val transportBuilder = ApacheHttpClient5TransportBuilder.builder(httpHost)

            // Configure authentication if credentials provided
            if (username != null && password != null) {
                val credentialsProvider = BasicCredentialsProvider().apply {
                    setCredentials(
                        AuthScope(httpHost),
                        UsernamePasswordCredentials(username, password.toCharArray())
                    )
                }

                transportBuilder.setHttpClientConfigCallback { httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)

                    // Configure SSL/TLS for HTTPS
                    if (useHttps) {
                        val sslContext: SSLContext = SSLContextBuilder.create()
                            .loadTrustMaterial(null) { _, _ -> true } // Accept all certificates
                            .build()

                        val tlsStrategy = ClientTlsStrategyBuilder.create()
                            .setSslContext(sslContext)
                            .build()

                        val connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                            .setTlsStrategy(tlsStrategy)
                            .build()

                        httpClientBuilder.setConnectionManager(connectionManager)
                    }

                    httpClientBuilder
                }
            }

            val transport = transportBuilder.build()
            val client = OpenSearchClient(transport)

            // Create the vector storage instance
            val storage = OpenSearchVectorStorage<Any>(
                client = client,
                indexName = indexName,
                vectorDimension = dimension,
                vectorFieldName = vectorField,
                documentFieldName = documentField,
                idFieldName = idField
            )

            // Ensure index exists with proper k-NN settings
            runBlocking {
                storage.ensureIndex()
            }

            storage
        }
    }

    /**
     * Ensures OpenSearch implementations are registered.
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
private val register = OpenSearchRegistration
