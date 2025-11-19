# Pinecone Vector Storage for Koog

This module provides a Pinecone implementation for Koog's `VectorStorage` interface, enabling high-performance vector similarity search for Retrieval-Augmented Generation (RAG) applications.

## Features

- **Serverless Vector Search**: Fully managed service with automatic scaling
- **Low-Latency Queries**: Optimized for real-time similarity search
- **Namespace Support**: Multi-tenant vector isolation within a single index
- **Metadata Filtering**: Filter search results by document metadata
- **Type-Safe**: Full Kotlin type safety with generic document support
- **Easy Configuration**: Simple URL-based setup with environment variable support

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":ai-koog"))
    implementation(project(":ai-koog-pinecone"))
}
```

## Quick Start

### Basic Usage

```kotlin
import com.lightningkite.services.ai.koog.rag.VectorStorageSettings
import com.lightningkite.services.SettingContext

// Configure vector storage with URL
val settings = VectorStorageSettings<String>(
    url = "pinecone://my-index?apiKey=\${PINECONE_API_KEY}"
)

// Instantiate the storage
val storage = settings("my-vector-storage", context)

// Store a document with its embedding
val documentId = storage.store(
    document = "The quick brown fox jumps over the lazy dog",
    data = floatArrayOf(0.1f, 0.2f, ...) // Vector embedding
)

// Find similar documents
val queryEmbedding = floatArrayOf(0.15f, 0.25f, ...)
val similar = storage.findSimilar(queryEmbedding, topK = 5)
```

### URL Configuration

The Pinecone vector storage is configured via URL parameters:

**Format**: `pinecone://index-name?param=value&...`

**Required Parameters**:
- `apiKey`: Pinecone API key (supports `${ENV_VAR}` syntax, or uses `PINECONE_API_KEY` environment variable)

**Optional Parameters**:
- `namespace`: Namespace for vector isolation (default: empty string)
- `documentField`: Custom field name for documents in metadata (default: `document`)

### Examples

#### Basic Configuration

```kotlin
val settings = VectorStorageSettings<String>(
    url = "pinecone://my-index?apiKey=\${PINECONE_API_KEY}"
)
```

#### Multi-Tenant with Namespaces

```kotlin
val settings = VectorStorageSettings<MyDocument>(
    url = "pinecone://embeddings?apiKey=\${PINECONE_API_KEY}&namespace=tenant-123"
)
```

#### Custom Document Field

```kotlin
val settings = VectorStorageSettings<Document>(
    url = "pinecone://docs?apiKey=pc-xxx&documentField=content&namespace=production"
)
```

## Vector Dimensions by Model

When creating a Pinecone index, you must specify the vector dimension matching your embedding model:

| Model | Dimension |
|-------|-----------|
| OpenAI text-embedding-3-small | 1536 |
| OpenAI text-embedding-3-large | 3072 |
| Cohere embed-english-v3.0 | 1024 |
| Sentence Transformers all-MiniLM-L6-v2 | 384 |
| Ollama nomic-embed-text | 768 |

## Pinecone Setup

### 1. Create a Pinecone Account

1. Sign up at [https://www.pinecone.io/](https://www.pinecone.io/)
2. Get your API key from the Pinecone console

### 2. Create an Index

#### Using Pinecone Console

1. Navigate to "Indexes" in the Pinecone dashboard
2. Click "Create Index"
3. Configure:
   - **Name**: Your index name (e.g., `my-index`)
   - **Dimensions**: Match your embedding model (e.g., `1536` for OpenAI)
   - **Metric**: Choose similarity metric (usually `cosine`)
   - **Pod Type**: Select based on your needs (Starter, Standard, etc.)

#### Using Pinecone CLI

```bash
# Install Pinecone CLI
pip install pinecone-client

# Create an index
pinecone create-index my-index --dimension 1536 --metric cosine
```

#### Using Python SDK

```python
import pinecone

# Initialize Pinecone
pinecone.init(api_key="your-api-key")

# Create index
pinecone.create_index(
    name="my-index",
    dimension=1536,
    metric="cosine"
)
```

### 3. Configure Environment Variables

Set your API key as an environment variable:

```bash
export PINECONE_API_KEY="your-pinecone-api-key"
```

Or reference it directly in the URL:

```kotlin
val settings = VectorStorageSettings<String>(
    url = "pinecone://my-index?apiKey=pc-xxxxxxxxxxxxx"
)
```

## Distance Metrics

Pinecone supports multiple distance metrics for similarity search:

- **`cosine`**: Cosine similarity (recommended for most use cases, especially text embeddings)
- **`euclidean`**: Euclidean distance (L2 norm)
- **`dotproduct`**: Dot product similarity (use when embeddings are normalized)

Choose the metric when creating your index based on your embedding model's recommendations.

## Advanced Usage

### Using Namespaces for Multi-Tenancy

Namespaces allow you to partition vectors within a single index:

```kotlin
// Tenant 1
val tenant1Storage = VectorStorageSettings<String>(
    url = "pinecone://shared-index?apiKey=\${PINECONE_API_KEY}&namespace=tenant-1"
)("storage", context)

// Tenant 2
val tenant2Storage = VectorStorageSettings<String>(
    url = "pinecone://shared-index?apiKey=\${PINECONE_API_KEY}&namespace=tenant-2"
)("storage", context)

// Vectors are isolated between tenants
```

### Direct Client Access

For advanced use cases, you can use the `PineconeVectorStorage` class directly:

```kotlin
import com.lightningkite.services.ai.koog.pinecone.PineconeVectorStorage
import io.pinecone.clients.Pinecone

// Create Pinecone client
val pinecone = Pinecone.Builder("your-api-key").build()

// Create vector storage
val storage = PineconeVectorStorage<MyDocument>(
    client = pinecone,
    indexName = "my-index",
    namespace = "production"
)

// Use the storage
val id = storage.store(myDocument, embedding)
```

### Similarity Search with Metadata Filtering

```kotlin
// Find similar documents with metadata filters
val results = storage.findSimilar(
    queryVector = queryEmbedding,
    topK = 10,
    filter = mapOf(
        "category" to "electronics",
        "price_min" to 100.0
    )
)
```

## API Reference

### VectorStorage Interface

The Pinecone implementation provides the following methods from the `VectorStorage` interface:

#### `store(document: Document, data: Vector): String`
Stores a document with its vector embedding and returns a unique document ID.

#### `delete(documentId: String): Boolean`
Deletes a document by ID. Returns `true` if successful.

#### `read(documentId: String): Document?`
Retrieves a document by ID without its embedding.

#### `getPayload(documentId: String): Vector?`
Retrieves only the vector embedding for a document.

#### `readWithPayload(documentId: String): DocumentWithPayload<Document, Vector>?`
Retrieves both the document and its embedding.

#### `allDocuments(): Flow<Document>`
Returns a flow of all documents. *Note: Not commonly used with Pinecone due to API limitations.*

#### `allDocumentsWithPayload(): Flow<DocumentWithPayload<Document, Vector>>`
Returns a flow of all documents with embeddings. *Note: Not commonly used with Pinecone due to API limitations.*

### Pinecone-Specific Methods

#### `findSimilar(queryVector: Vector, topK: Int, filter: Map<String, Any>?): Flow<DocumentWithPayload<Document, Vector>>`
Performs similarity search to find the most similar documents to the query vector.

- **queryVector**: The embedding to search for
- **topK**: Number of results to return (default: 10)
- **filter**: Optional metadata filter to narrow results

## Performance Optimization

### Best Practices

1. **Batch Operations**: Store multiple vectors at once for better throughput
2. **Namespace Strategy**: Use namespaces to isolate different use cases or tenants
3. **Index Selection**: Choose appropriate pod type based on your scale:
   - **Starter**: Development and testing
   - **Standard**: Production workloads
   - **Performance-optimized**: High-throughput applications
4. **Metric Selection**: Use `cosine` for most text embedding use cases

### Scaling Considerations

- Pinecone automatically handles scaling for serverless indexes
- For pod-based indexes, monitor query latency and scale pods as needed
- Consider using multiple namespaces for logical separation without creating new indexes

## Troubleshooting

### Connection Issues

**Error**: "Failed to connect to Pinecone"

**Solutions**:
1. Verify your API key is correct
2. Check that the index exists in your Pinecone account
3. Ensure network connectivity to Pinecone's API

### Index Dimension Mismatch

**Error**: "Vector dimension mismatch"

**Solutions**:
1. Verify your index dimension matches your embedding model
2. Check that all vectors have the same dimension
3. Recreate the index with the correct dimension if needed

### Namespace Not Found

**Error**: "Namespace does not exist"

**Solutions**:
1. Namespaces are created automatically on first use
2. Verify the namespace name in your configuration
3. Check for typos in the namespace parameter

## Migration from Other Vector Stores

### From OpenSearch

```kotlin
// Before (OpenSearch)
val oldSettings = VectorStorageSettings<String>(
    "opensearch://host:9200/index?dimension=1536&user=admin&password=\${PASS}"
)

// After (Pinecone)
val newSettings = VectorStorageSettings<String>(
    "pinecone://my-index?apiKey=\${PINECONE_API_KEY}"
)
```

### From In-Memory Storage

```kotlin
// Before (In-Memory)
val devSettings = VectorStorageSettings<String>("rag-memory://")

// After (Pinecone for Production)
val prodSettings = VectorStorageSettings<String>(
    "pinecone://prod-index?apiKey=\${PINECONE_API_KEY}&namespace=production"
)
```

## Limitations

1. **No bulk list operation**: Pinecone doesn't provide an efficient "list all vectors" API, so `allDocuments()` returns an empty flow
2. **Document serialization**: Documents are currently stored as strings in metadata. For complex objects, consider custom serialization
3. **Metadata size limits**: Pinecone has limits on metadata size per vector (~40KB)

## Cost Considerations

Pinecone pricing is based on:
- **Pod type and count** (for pod-based indexes)
- **Storage and requests** (for serverless indexes)
- **Query volume**

See [Pinecone Pricing](https://www.pinecone.io/pricing/) for current rates.

## License

This module is part of the service-abstractions library and follows the same license terms.

## Resources

- [Pinecone Documentation](https://docs.pinecone.io/)
- [Pinecone Java SDK](https://github.com/pinecone-io/pinecone-java-client)
- [Koog RAG Framework](../ai-koog/README.md)
- [Vector Storage Settings](../ai-koog/README.md)
