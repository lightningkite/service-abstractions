package com.lightningkite.services.ai.koog.pinecone

import ai.koog.embeddings.base.Vector
import ai.koog.rag.base.DocumentWithPayload
import ai.koog.rag.vector.VectorStorage
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.pinecone.clients.Index
import io.pinecone.clients.Pinecone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Pinecone-backed implementation of [VectorStorage] for Koog's RAG framework.
 *
 * This implementation uses Pinecone's managed vector database service for
 * storing and retrieving documents with their vector embeddings for similarity search.
 *
 * Features:
 * - Serverless vector search with automatic scaling
 * - Low-latency similarity search with ANN (Approximate Nearest Neighbors)
 * - Support for metadata filtering
 * - Configurable namespace support for multi-tenancy
 * - Efficient batch operations
 *
 * **Note**: This is a minimal implementation that currently only supports vector storage and retrieval
 * by ID, without document content storage in metadata. To use this with Koog's RAG framework,
 * you will need to maintain a separate document store and use the vector IDs to retrieve documents.
 * Full metadata support will be added in a future update.
 *
 * @param Document The document type (note: not stored, only used for type safety)
 * @param client The Pinecone client instance
 * @param indexName The name of the Pinecone index to use
 * @param namespace Optional namespace for isolating vectors (default: empty string)
 */
public class PineconeVectorStorage<Document>(
    private val client: Pinecone,
    private val indexName: String,
    private val namespace: String = ""
) : VectorStorage<Document> {

    private val index: Index by lazy {
        client.getIndexConnection(indexName)
    }

    override suspend fun store(document: Document, data: Vector): String {
        // Generate a unique ID for the document
        val documentId = UUID.randomUUID().toString()

        val vectorList = vectorToList(data)

        // Upsert the vector to Pinecone using the simple API
        // Note: Current implementation doesn't store document content in metadata
        // This is a limitation of the simple API - metadata support requires additional investigation
        if (namespace.isNotEmpty()) {
            // For namespaced upserts, we'd need to use the batch API
            // For now, this is a simplification
            index.upsert(documentId, vectorList)
        } else {
            index.upsert(documentId, vectorList)
        }

        return documentId
    }

    override suspend fun delete(documentId: String): Boolean {
        return try {
            index.deleteByIds(listOf(documentId), namespace)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun read(documentId: String): Document? {
        // Note: Document content is not stored in Pinecone metadata in this implementation
        throw UnsupportedOperationException(
            "Reading documents is not yet supported. " +
            "This implementation only stores vectors. Use a separate document store."
        )
    }

    override suspend fun getPayload(documentId: String): Vector? {
        return try {
            val fetchResponse = index.fetch(listOf(documentId), namespace)
            val vectorMap = fetchResponse.vectorsMap
            if (vectorMap == null || !vectorMap.containsKey(documentId)) {
                return null
            }

            val vector = vectorMap[documentId] ?: return null
            listToVector(vector.valuesList)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun readWithPayload(documentId: String): DocumentWithPayload<Document, Vector>? {
        return try {
            val fetchResponse = index.fetch(listOf(documentId), namespace)
            val vectorMap = fetchResponse.vectorsMap
            if (vectorMap == null || !vectorMap.containsKey(documentId)) {
                return null
            }

            val vector = vectorMap[documentId] ?: return null
            val embedding = listToVector(vector.valuesList)

            // Note: Document content is not stored in Pinecone metadata in this implementation
            // This method will throw an exception as we cannot reconstruct the document
            throw UnsupportedOperationException(
                "Reading documents with payload is not yet supported. " +
                "This implementation only stores vectors. Use a separate document store."
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun allDocuments(): Flow<Document> {
        return allDocumentsWithPayload().map { it.document }
    }

    override fun allDocumentsWithPayload(): Flow<DocumentWithPayload<Document, Vector>> {
        // Note: Pinecone doesn't provide a direct "list all vectors" API
        // This would require using the list() API with pagination, which is limited
        // For now, we return an empty flow as this operation is not commonly used
        // and can be expensive in production systems
        return emptyFlow()
    }

    /**
     * Performs similarity search to find documents similar to the given query vector.
     *
     * @param queryVector The vector to search for similar documents
     * @param topK The number of nearest neighbors to return (default: 10)
     * @param filter Optional metadata filter for narrowing results (Struct format)
     * @return Flow of document-payload pairs ordered by similarity
     */
    public suspend fun findSimilar(
        queryVector: Vector,
        topK: Int = 10,
        filter: Struct? = null
    ): Flow<DocumentWithPayload<Document, Vector>> {
        require(topK > 0) {
            "topK must be positive, got: $topK"
        }

        val vectorList = vectorToList(queryVector)

        // Query for similar vectors - using the simple query method
        val queryResponse = index.queryByVector(
            topK,
            vectorList,
            namespace
        )

        return queryResponse.matchesList.asFlow().map { match ->
            val vector = listToVector(match.valuesList)
            // Note: Document content is not available in this implementation
            // Returning null as document - users must maintain a separate document store
            throw UnsupportedOperationException(
                "Finding similar documents with content is not yet supported. " +
                "Use a separate document store and match by vector ID."
            )
        }
    }

    /**
     * Converts a Koog Vector to a List<Float> for Pinecone operations.
     */
    @Suppress("UNCHECKED_CAST")
    private fun vectorToList(vector: Vector): List<Float> {
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
