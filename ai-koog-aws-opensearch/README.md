# AWS OpenSearch Vector Storage for Koog

This module provides an AWS OpenSearch implementation for Koog's `VectorStorage` interface, enabling vector similarity search for Retrieval-Augmented Generation (RAG) applications.

## Features

- **k-NN Vector Search**: Leverages OpenSearch's k-NN plugin for efficient similarity search
- **Flexible Configuration**: Support for both AWS OpenSearch Service and self-hosted OpenSearch
- **Secure Authentication**: Basic authentication with environment variable support
- **Customizable Fields**: Configure custom field names for vectors, documents, and IDs
- **Type-Safe**: Full Kotlin type safety with generic document support

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":ai-koog"))
    implementation(project(":ai-koog-aws-opensearch"))
}
```

## Quick Start

### Basic Usage

```kotlin
import com.lightningkite.services.ai.koog.rag.VectorStorageSettings
import com.lightningkite.services.SettingContext

// Configure vector storage with URL
val settings = VectorStorageSettings<String>(
    url = "opensearch://my-domain.us-east-1.es.amazonaws.com:443/vectors?dimension=1536&user=\${OPENSEARCH_USER}&password=\${OPENSEARCH_PASSWORD}"
)

// Instantiate the storage
val storage = settings("my-vector-storage", context)

// Store a document with its embedding
val documentId = storage.store(
    document = "The quick brown fox jumps over the lazy dog",
    data = floatArrayOf(0.1f, 0.2f, ...) // 1536-dimensional embedding
)

// Find similar documents
val queryEmbedding = floatArrayOf(0.15f, 0.25f, ...)
val similar = storage.findSimilar(queryEmbedding, k = 5)
```

### URL Configuration

The OpenSearch vector storage is configured via URL parameters:

**Format**: `opensearch://host:port/index-name?param=value&...`

**Required Parameters**:
- `dimension`: Vector dimension (e.g., `1536` for OpenAI text-embedding-3-small)

**Optional Parameters**:
- `user`: Username for authentication (supports `${ENV_VAR}` syntax)
- `password`: Password for authentication (supports `${ENV_VAR}` syntax)
- `https`: Use HTTPS (default: `true`)
- `vectorField`: Custom field name for vectors (default: `embedding`)
- `documentField`: Custom field name for documents (default: `document`)
- `idField`: Custom field name for IDs (default: `id`)

### Examples

#### AWS OpenSearch Service

```kotlin
val settings = VectorStorageSettings<MyDocument>(
    url = "opensearch://search-my-domain-abc123.us-east-1.es.amazonaws.com:443/products?dimension=1536&user=\${AWS_OPENSEARCH_USER}&password=\${AWS_OPENSEARCH_PASSWORD}"
)
```

#### Local Development

```kotlin
val settings = VectorStorageSettings<String>(
    url = "opensearch://localhost:9200/test-vectors?dimension=768&https=false&user=admin&password=admin"
)
```

#### Custom Field Names

```kotlin
val settings = VectorStorageSettings<Document>(
    url = "opensearch://my-domain.region.es.amazonaws.com:443/docs?dimension=1536&vectorField=vec&documentField=content&idField=doc_id&user=admin&password=\${PASS}"
)
```

## Vector Dimensions by Model

Common embedding model dimensions:

| Model | Dimension |
|-------|-----------|
| OpenAI text-embedding-3-small | 1536 |
| OpenAI text-embedding-3-large | 3072 |
| Cohere embed-english-v3.0 | 1024 |
| Sentence Transformers all-MiniLM-L6-v2 | 384 |
| Ollama nomic-embed-text | 768 |

## AWS OpenSearch Setup

### 1. Create an OpenSearch Domain

Using AWS Console:
1. Navigate to Amazon OpenSearch Service
2. Click "Create domain"
3. Choose deployment type (Production or Development/testing)
4. Configure domain settings (instance type, storage, etc.)
5. Set up access policies or VPC configuration
6. Enable fine-grained access control (optional but recommended)

Using Terraform:

```hcl
resource "aws_opensearch_domain" "vector_store" {
  domain_name    = "my-vector-store"
  engine_version = "OpenSearch_2.11"

  cluster_config {
    instance_type  = "t3.small.search"
    instance_count = 1
  }

  ebs_options {
    ebs_enabled = true
    volume_size = 10
  }

  advanced_options = {
    "rest.action.multi.allow_explicit_index" = "true"
  }

  node_to_node_encryption {
    enabled = true
  }

  encrypt_at_rest {
    enabled = true
  }
}
```

### 2. Configure Authentication

Set environment variables:

```bash
export OPENSEARCH_USER="your-username"
export OPENSEARCH_PASSWORD="your-password"
```

Or use AWS Secrets Manager and reference in your application.

### 3. Verify k-NN Plugin

The k-NN plugin is enabled by default in AWS OpenSearch. To verify:

```bash
curl -X GET "https://your-domain.region.es.amazonaws.com/_cat/plugins?v"
```

## Terraform Support

The module includes Terraform generation support for automatically provisioning AWS OpenSearch domains.

### Basic Terraform Usage

```kotlin
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.ai.koog.opensearch.awsOpenSearchDomain

context(emitter: TerraformEmitterAws) {
    vectorStorageNeed.awsOpenSearchDomain(
        vectorDimension = 1536,  // Required: match your embedding model
        indexName = "vectors",
        instanceType = "t3.small.search",
        instanceCount = 1,
        volumeSize = 10
    )
}
```

This will generate:
- AWS OpenSearch domain with k-NN plugin enabled
- Random password generation for master user (stored in Terraform state)
- Proper encryption, HTTPS, and fine-grained access control
- IAM policies for Lambda/application access
- Automatic VectorStorageSettings configuration

### Terraform Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `vectorDimension` | Int | *required* | Vector dimension for k-NN index |
| `indexName` | String | "vectors" | OpenSearch index name |
| `instanceType` | String | "t3.small.search" | Instance type |
| `instanceCount` | Int | 1 | Number of instances |
| `volumeSize` | Int | 10 | EBS volume size (GB) |
| `masterUsername` | String | "admin" | Master user name |
| `masterPassword` | String? | null | Master password (random if null) |

### Production Terraform Example

```kotlin
context(emitter: TerraformEmitterAws) {
    vectorStorageNeed.awsOpenSearchDomain(
        vectorDimension = 1536,
        indexName = "production-vectors",
        instanceType = "r6g.large.search",  // Memory-optimized
        instanceCount = 2,                   // Multi-instance for HA
        volumeSize = 100,
        masterUsername = "opensearch_admin",
        masterPassword = null  // Auto-generated secure password
    )
}
```

### Generated Terraform Output

The Terraform function automatically generates:
1. **Random Password** (if masterPassword is null)
2. **OpenSearch Domain** with:
   - k-NN plugin enabled
   - Encryption at rest and in transit
   - Fine-grained access control
   - TLS 1.2 minimum
3. **IAM Policies** for OpenSearch HTTP actions
4. **VectorStorageSettings** with connection URL including credentials

## Advanced Usage

### Direct Client Access

You can also use the `OpenSearchVectorStorage` class directly for more control:

```kotlin
import com.lightningkite.services.ai.koog.opensearch.OpenSearchVectorStorage
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import org.apache.hc.core5.http.HttpHost

// Create OpenSearch client
val httpHost = HttpHost("https", "my-domain.region.es.amazonaws.com", 443)
val transport = ApacheHttpClient5TransportBuilder.builder(httpHost).build()
val client = OpenSearchClient(transport)

// Create vector storage
val storage = OpenSearchVectorStorage<MyDocument>(
    client = client,
    indexName = "my-vectors",
    vectorDimension = 1536
)

// Ensure index exists
storage.ensureIndex()

// Use the storage
val id = storage.store(myDocument, embedding)
```

### Similarity Search with Filtering

```kotlin
import org.opensearch.client.opensearch._types.query_dsl.Query

// Find similar documents with additional filters
val filter = Query.Builder()
    .term { t -> t.field("category").value("electronics") }
    .build()

val results = storage.findSimilar(
    queryVector = queryEmbedding,
    k = 10,
    filter = filter
)
```

## Troubleshooting

### Connection Issues

If you encounter connection errors:

1. **Verify endpoint**: Ensure the OpenSearch domain endpoint is correct
2. **Check security groups**: Verify your application has network access to OpenSearch
3. **Authentication**: Confirm credentials are valid
4. **HTTPS**: AWS OpenSearch requires HTTPS by default

### Index Creation Failures

If index creation fails:

1. **Permissions**: Ensure your user has `indices:admin/create` permissions
2. **k-NN plugin**: Verify the k-NN plugin is enabled
3. **Dimension**: Check that the vector dimension matches your embedding model

### Performance Optimization

For production workloads:

1. **Batch operations**: Use bulk indexing for multiple documents
2. **Index refresh**: Consider disabling automatic refresh during bulk loads
3. **Shard allocation**: Configure appropriate number of shards based on data size
4. **Instance sizing**: Use memory-optimized instances for large vector datasets

## License

This module is part of the service-abstractions library and follows the same license terms.
