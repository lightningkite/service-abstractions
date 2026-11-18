# Embeddings

`EmbeddingService` turns text into vectors (`Embedding`) for similarity search: OpenAI or OpenAI-compatible servers, AWS Bedrock (Titan, Cohere), or Ollama.  Store and search them with a [vector index](database.md#vector-search).

## Modules

| Module | Add it for |
|---|---|
| `embedding` | `EmbeddingService`, `EmbeddingModelId`.  Multiplatform.  No built-in scheme. |
| `embedding-openai` | `openai://`.  JVM. |
| `embedding-bedrock` | `bedrock://` with static or profile credentials.  Multiplatform. |
| `embedding-bedrock-aws-sdk` | `bedrock-sdk://` with the AWS SDK's refreshing credential chain.  JVM. |
| `embedding-ollama` | `ollama://`.  JVM. |
| `embedding-test` | Shared contract suites for providers. |

## Quick start

```kotlin
OpenAiEmbeddingSettings.ensureRegistered()  // registers openai://
val embeddings = EmbeddingService.Settings("openai://text-embedding-3-small?apiKey=\${OPENAI_API_KEY}")("embeddings", context)
val model = EmbeddingModelId("text-embedding-3-small")

val one: Embedding = embeddings.embed("How do I reset my password?", model)
val many: List<Embedding> = embeddings.embedTexts(listOf("first", "second"), model)
```

`embed(texts, model)` returns an `EmbeddingResult` with token usage as well.  `getModels()` lists models with their dimensions and prices.  Failures throw `EmbeddingException` (`Auth`, `RateLimit`, `InvalidModel`, ...); nothing is retried.

Use the same model for indexing and for queries: vectors from different models aren't comparable.  A `VectorSpace(model, dimensions)` records which one an index holds.

## Settings URLs

| Scheme | Module | Register with | Example |
|---|---|---|---|
| `openai` | `embedding-openai` | `OpenAiEmbeddingSettings.ensureRegistered()` | `openai://text-embedding-3-large?apiKey=${OPENAI_API_KEY}&dimensions=256` |
| `bedrock` | `embedding-bedrock` | `BedrockEmbeddingSettings.ensureRegistered()` | `bedrock://amazon.titan-embed-text-v2:0?region=us-east-1&dimensions=1024` |
| `bedrock-sdk` | `embedding-bedrock-aws-sdk` | `BedrockSdkEmbedding.ensureRegistered()` | `bedrock-sdk://amazon.titan-embed-text-v2:0?region=us-east-1` |
| `ollama` | `embedding-ollama` | `OllamaEmbeddingSchemeRegistrar.ensureRegistered()` | `ollama://nomic-embed-text?autoPull=true` |

Parameters and quirks: [Settings URL reference: Embeddings](settings-url-reference.md#embeddings).  Unlike the LLM schemes, these take a model in the URL; it's required.

## Testing

There's no fake scheme.  Use `ollama://nomic-embed-text` locally, or a hand-written `EmbeddingService` returning fixed vectors.  Provider authors: subclass the suites in `embedding-test`.

## Gotchas

- **`bedrock-sdk://` needs a region** (URL or `AWS_REGION`); the other Bedrock schemes default to `us-east-1`.
- **`dimensions`** truncates OpenAI v3 and Titan v2 vectors.  Changing it changes the vector space: rebuild the index.
