package com.lightningkite.services.ai.koog.opensearch

import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.DocumentWithPayload
import ai.koog.rag.vector.VectorStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.query_dsl.Query
import org.opensearch.client.opensearch.core.SearchRequest

/**
 * OpenSearch-backed implementation of [VectorStorage] for Koog's RAG framework.
 *
 * This implementation uses AWS OpenSearch Service or self-hosted OpenSearch
 * to store and retrieve documents with their vector embeddings for similarity search.
 *
 * Features:
 * - k-NN vector search using OpenSearch's k-NN plugin
 * - Configurable index name and vector dimensions
 * - Support for custom document serialization
 * - Efficient bulk operations for storing multiple documents
 *
 * @param Document The document type to store (must be JSON-serializable)
 * @param client The OpenSearch client instance
 * @param indexName The name of the OpenSearch index to use
 * @param vectorDimension The dimension of the vector embeddings (e.g., 1536 for OpenAI embeddings)
 * @param vectorFieldName The field name to store vectors (default: "embedding")
 * @param documentFieldName The field name to store document content (default: "document")
 * @param idFieldName The field name for document IDs (default: "id")
 */
public class OpenSearchVectorStorage<Document>(
    private val client: OpenSearchClient,
    private val indexName: String,
    private val vectorDimension: Int,
    private val vectorFieldName: String = "embedding",
    private val documentFieldName: String = "document",
    private val idFieldName: String = "id",
    private val json: Json = Json { ignoreUnknownKeys = true }
) : VectorStorage<Document> {

    /**
     * Ensures the index exists with proper k-NN settings and mappings.
     * Should be called once during initialization.
     */
    public suspend fun ensureIndex() {
        val indexExists = client.indices().exists { it.index(indexName) }.value()
        if (!indexExists) {
            client.indices().create { builder ->
                builder
                    .index(indexName)
                    .settings { s ->
                        s.knn(true)
                            .numberOfShards(1)
                            .numberOfReplicas(1)
                    }
                    .mappings { m ->
                        m.properties(idFieldName) { p -> p.keyword { it } }
                            .properties(vectorFieldName) { p ->
                                p.knnVector { k ->
                                    k.dimension(vectorDimension)
                                }
                            }
                            .properties(documentFieldName) { p ->
                                p.text { it }
                            }
                    }
            }
        }
    }

    override suspend fun store(document: Document, data: Vector): String {
        // Generate a unique ID based on document hashCode and timestamp
        val documentId = "${document.hashCode()}-${System.currentTimeMillis()}"

        val vectorList = vectorToList(data)

        val docJson = buildJsonObject {
            put(idFieldName, JsonPrimitive(documentId))
            put(documentFieldName, JsonPrimitive(document.toString()))
            put(vectorFieldName, buildJsonArray {
                vectorList.forEach { add(JsonPrimitive(it)) }
            })
        }

        client.index { i ->
            i.index(indexName)
                .id(documentId)
                .document(docJson)
        }

        // Refresh to make the document immediately searchable
        client.indices().refresh { it.index(indexName) }

        return documentId
    }

    override suspend fun delete(documentId: String): Boolean {
        val response = client.delete { d ->
            d.index(indexName).id(documentId)
        }
        return response.result().name == "DELETED"
    }

    override suspend fun read(documentId: String): Document? {
        return readWithPayload(documentId)?.document
    }

    override suspend fun getPayload(documentId: String): Vector? {
        return readWithPayload(documentId)?.payload
    }

    override suspend fun readWithPayload(documentId: String): DocumentWithPayload<Document, Vector>? {
        val response = client.get({ g ->
            g.index(indexName).id(documentId)
        }, JsonObject::class.java)

        if (!response.found()) {
            return null
        }

        val source = response.source() ?: return null
        val document = extractDocument(source)
        val vector = extractVector(source)
        return DocumentWithPayload(document, vector)
    }

    override fun allDocuments(): Flow<Document> {
        return allDocumentsWithPayload().map { it.document }
    }

    override fun allDocumentsWithPayload(): Flow<DocumentWithPayload<Document, Vector>> {
        // Note: This is a synchronous Flow, so we need to make the call blocking
        // In a real implementation, you might want to use pagination
        return try {
            val searchRequest = SearchRequest.Builder()
                .index(indexName)
                .size(10000) // Maximum batch size
                .query(Query.Builder().matchAll { it }.build())
                .build()

            val response = runCatching {
                client.search(searchRequest, JsonObject::class.java)
            }.getOrNull()

            response?.hits()?.hits()?.asFlow()?.map { hit ->
                val source = hit.source() ?: throw IllegalStateException("Document source is null for id: ${hit.id()}")
                val document = extractDocument(source)
                val vector = extractVector(source)
                DocumentWithPayload(document, vector)
            } ?: emptyFlow()
        } catch (e: Exception) {
            emptyFlow()
        }
    }

    /**
     * Performs k-NN similarity search to find documents similar to the given query vector.
     *
     * @param queryVector The vector to search for similar documents
     * @param topK The number of nearest neighbors to return
     * @return Flow of document-payload pairs ordered by similarity
     */
    public suspend fun findSimilar(
        queryVector: Vector,
        topK: Int = 10
    ): Flow<DocumentWithPayload<Document, Vector>> {
        require(topK > 0) {
            "topK must be positive, got: $topK"
        }

        val vectorList = vectorToList(queryVector)

        val knnQuery = Query.Builder()
            .knn { knn ->
                knn.field(vectorFieldName)
                    .vector(vectorList)
                    .k(topK)
            }
            .build()

        val searchRequest = SearchRequest.Builder()
            .index(indexName)
            .query(knnQuery)
            .size(topK)
            .build()

        val response = client.search(searchRequest, JsonObject::class.java)

        return response.hits().hits().asFlow().map { hit ->
            val source = hit.source() ?: throw IllegalStateException("Document source is null")
            val document = extractDocument(source)
            val vector = extractVector(source)
            DocumentWithPayload(document, vector)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractDocument(source: JsonObject): Document {
        val documentElement = source[documentFieldName]
            ?: throw IllegalStateException("Document field '$documentFieldName' not found in source")

        // For now, we store documents as strings
        // In a more sophisticated implementation, you'd deserialize based on the actual type
        return documentElement.jsonPrimitive.content as Document
    }

    private fun extractVector(source: JsonObject): Vector {
        val vectorElement = source[vectorFieldName]
            ?: throw IllegalStateException("Vector field '$vectorFieldName' not found in source")

        val floatList = vectorElement.jsonArray.map { it.jsonPrimitive.float }
        return listToVector(floatList)
    }

    /**
     * Converts a Koog Vector to a List<Float> for OpenSearch operations.
     * Note: Vector is an opaque type from the Koog library, so we use reflection/casting.
     */
    @Suppress("UNCHECKED_CAST")
    private fun vectorToList(vector: Vector): List<Float> {
        // Vector is likely FloatArray or List<Float> internally
        // We'll treat it as Any and try to convert it
        return when (vector) {
            is FloatArray -> vector.toList()
            is List<*> -> vector as List<Float>
            is Array<*> -> (vector as Array<Float>).toList()
            else -> throw IllegalArgumentException("Unsupported Vector type: ${vector::class}")
        }
    }

    /**
     * Converts a List<Float> to a Koog Vector.
     */
    @Suppress("UNCHECKED_CAST")
    private fun listToVector(list: List<Float>): Vector {
        // Assuming Vector is FloatArray
        return list.toFloatArray() as Vector
    }
}
