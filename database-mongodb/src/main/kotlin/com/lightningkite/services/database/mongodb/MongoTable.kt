package com.lightningkite.services.database.mongodb

import com.lightningkite.GeoCoordinateGeoJsonSerializer
import com.lightningkite.services.data.*
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.Aggregate
import com.lightningkite.services.database.CollectionChanges
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.DataClassPathPartial
import com.lightningkite.services.database.DataClassPathSerializer
import com.lightningkite.services.database.DenseVectorSearchParams
import com.lightningkite.services.database.Embedding
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.ScoredResult
import com.lightningkite.services.database.SimilarityMetric
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.SparseEmbedding
import com.lightningkite.services.database.SparseVectorSearchParams
import com.lightningkite.services.database.VectorIndex
import com.lightningkite.services.database.collectChunked
import com.lightningkite.services.database.indexes
import com.lightningkite.services.database.innerElement
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.simplify
import com.lightningkite.services.database.walk
import com.mongodb.MongoCommandException
import com.mongodb.client.model.*
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.modules.overwriteWith
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.conversions.Bson
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

public class MongoTable<Model : Any>(
    override val serializer: KSerializer<Model>,
    public val atlasSearch: Boolean,
    private val access: MongoCollectionAccess,
    private val context: SettingContext
) : Table<Model> {
    internal val bson: KBson = KBson(context.internalSerializersModule)

    public val indexedTextFields: List<DataClassPathPartial<Model>>? by lazy {
        val ser = DataClassPathSerializer(serializer)
        serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields?.map {
            ser.fromString(it)
        }
    }

    private suspend inline fun <T> access(crossinline action: suspend MongoCollection<BsonDocument>.() -> T): T {
        return access.run {
            prepare()
            action()
        }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = access {
        if (models.none()) return@access emptyList()
        val asList = models.toList()
        insertMany(asList.map { bson.stringify(serializer, it) })
        return@access asList
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = updateOne(condition, Modification.Assign(model), orderBy)

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        if (orderBy.isNotEmpty()) return updateOneIgnoringResult(cs, Modification.Assign(model), orderBy)
        return access {
            replaceOne(
                cs.bson(serializer, bson = bson, atlasSearch = atlasSearch),
                bson.stringify(serializer, model)
            ).matchedCount != 0L
        }
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return EntryChange(null, null)
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return EntryChange(null, null)
        val m = simplifiedModification.bson(serializer, bson = bson)
        return access {
            // TODO: Ugly hack for handling weird upserts
            if (m.upsert(model, serializer, bson)) {
                findOneAndUpdate(
                    cs.bson(serializer, bson = bson, atlasSearch = atlasSearch),
                    m.document,
                    FindOneAndUpdateOptions()
                        .returnDocument(ReturnDocument.BEFORE)
                        .upsert(m.options.isUpsert)
                        .bypassDocumentValidation(m.options.bypassDocumentValidation)
                        .collation(m.options.collation)
                        .arrayFilters(m.options.arrayFilters)
                        .hint(m.options.hint)
                        .hintString(m.options.hintString)
                )?.let { bson.parse(serializer, it) }?.let { EntryChange(it, modification(it)) }
                    ?: EntryChange(null, model)
            } else {
                findOneAndUpdate(
                    cs.bson(serializer, bson = bson, atlasSearch = atlasSearch),
                    m.document,
                    FindOneAndUpdateOptions()
                        .returnDocument(ReturnDocument.BEFORE)
                        .upsert(m.options.isUpsert)
                        .bypassDocumentValidation(m.options.bypassDocumentValidation)
                        .collation(m.options.collation)
                        .arrayFilters(m.options.arrayFilters)
                        .hint(m.options.hint)
                        .hintString(m.options.hintString)
                )?.let { bson.parse(serializer, it) }?.let { EntryChange(it, modification(it)) }
                    ?: run {
                        insertOne(bson.stringify(serializer, model)); EntryChange(
                        null,
                        model
                    )
                    }
            }
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return false
        val m = simplifiedModification.bson(serializer, bson = bson)
        return access {
            // TODO: Ugly hack for handling weird upserts
            if (m.upsert(model, serializer, bson = bson)) {
                updateOne(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch), m.document, m.options).matchedCount > 0
            } else {
                if (updateOne(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch), m.document, m.options).matchedCount != 0L) {
                    true
                } else {
                    insertOne(bson.stringify(serializer, model))
                    false
                }
            }
        }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return EntryChange(null, null)
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return EntryChange(null, null)
        val m = simplifiedModification.bson(serializer, bson = bson)
        val before = access<Model?> {
            findOneAndUpdate(
                cs.bson(serializer, bson = bson, atlasSearch = atlasSearch),
                m.document,
                FindOneAndUpdateOptions()
                    .returnDocument(ReturnDocument.BEFORE)
                    .let { if (orderBy.isEmpty()) it else it.sort(sort(orderBy)) }
                    .upsert(m.options.isUpsert)
                    .bypassDocumentValidation(m.options.bypassDocumentValidation)
                    .collation(m.options.collation)
                    .arrayFilters(m.options.arrayFilters)
                    .hint(m.options.hint)
                    .hintString(m.options.hintString)
            )?.let { bson.parse(serializer, it) }
        } ?: return EntryChange(null, null)
        val after = modification(before)
        return EntryChange(before, after)
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return false
        val m = simplifiedModification.bson(serializer, bson = bson)
        return access { updateOne(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch), m.document, m.options).matchedCount != 0L }
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return CollectionChanges()
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return CollectionChanges()
        val m = simplifiedModification.bson(serializer, bson = bson)
        val changes = ArrayList<EntryChange<Model>>()
        // TODO: Don't love that we have to do this in chunks, but I guess we'll live.  Could this be done with pipelines?
        access {
            find(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)).collectChunked(1000) { list ->
                updateMany(Filters.`in`("_id", list.map { it["_id"] }), m.document, m.options)
                list.asSequence().map { bson.parse(serializer, it) }
                    .forEach {
                        changes.add(EntryChange(it, modification(it)))
                    }
            }
        }
        return CollectionChanges(changes = changes)
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return 0
        val m = simplifiedModification.bson(serializer, bson = bson)
        return access {
            updateMany(
                cs.bson(serializer, bson = bson, atlasSearch = atlasSearch),
                m.document,
                m.options
            ).matchedCount.toInt()
        }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        val cs = condition.simplify()
        if (cs is Condition.Never) return null
        return access {
            // TODO: Hack, needs some retry logic at a minimum
            withDocumentClass<BsonDocument>().find(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch))
                .let { if (orderBy.isEmpty()) it else it.sort(sort(orderBy)) }
                .limit(1).firstOrNull()?.let {
                    val id = it["_id"]
                    deleteOne(Filters.eq("_id", id))
                    bson.parse(serializer, it)
                }
        }
    }

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        if (orderBy.isNotEmpty()) return deleteOne(condition, orderBy) != null
        return access { deleteOne(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)).deletedCount > 0 }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return listOf()
        val remove = ArrayList<Model>()
        access {
            // TODO: Don't love that we have to do this in chunks, but I guess we'll live.  Could this be done with pipelines?
            withDocumentClass<BsonDocument>().find(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)).collectChunked(1000) { list ->
                deleteMany(Filters.`in`("_id", list.map { it["_id"] }))
                list.asSequence().map { bson.parse(serializer, it) }
                    .forEach {
                        remove.add(it)
                    }
            }
        }
        return remove
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        return access { deleteMany(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)).deletedCount.toInt() }
    }


    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return emptyFlow()
        return access {
            var anyFts: Condition.FullTextSearch<*>? = null
            condition.walk { if (it is Condition.FullTextSearch) anyFts = it }

            aggregate<BsonDocument>(
                buildList {
                    if(anyFts != null && atlasSearch) {
                        add(documentOf(
                            "\$search" to documentOf(
                                "index" to "default",
                                "text" to documentOf(
                                    "query" to anyFts!!.value,
                                    "fuzzy" to documentOf(),
                                    "path" to documentOf("wildcard" to "*"),
                                    "matchCriteria" to if(anyFts!!.requireAllTermsPresent) "all" else "any"
                                )
                            )
                        ))
                        add(Aggregates.project(Projections.metaSearchScore("search_score").toBsonDocument().apply {
                            for(field in serializer.descriptor.elementNames) put(field, BsonBoolean(true))
                        }))
                        add(Aggregates.match(cs.bson(serializer, atlasSearch = true, bson = bson)))
                    } else {
                        add(Aggregates.match(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)))
                    }

                    if (anyFts != null && !atlasSearch) {
                        add(Aggregates.project(Projections.metaSearchScore("text_search_score").toBsonDocument().apply {
                            for(field in serializer.descriptor.elementNames) put(field, BsonBoolean(true))
                        }))
                        add(Aggregates.sort(sort(orderBy, Sorts.metaTextScore("text_search_score"))))
                    } else if(orderBy.isNotEmpty()) {
                        add(Aggregates.sort(sort(orderBy)))
                    }
                    add(Aggregates.skip(skip))
                    add(Aggregates.limit(limit))
                }
            )
                .let {
                    if (orderBy.any { it.ignoreCase }) {
                        it.collation(Collation.builder().locale("en").build())
                    } else it
                }
                .maxTime(maxQueryMs, TimeUnit.MILLISECONDS)
                .map {
                    bson.parse(serializer, it)
                }
        }
    }

    @Serializable
    private data class KeyHolder<Key>(val _id: Key)

    override suspend fun count(condition: Condition<Model>): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        return access { countDocuments(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)).toInt() }
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
    ): Map<Key, Int> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return mapOf()
        return access {
            aggregate<BsonDocument>(
                listOf(
                    Aggregates.match(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)),
                    Aggregates.group("\$" + groupBy.mongo, Accumulators.sum("count", 1))
                )
            )
                .toList()
                .associate {
                    bson.parse(
                        KeyHolder.serializer(groupBy.serializer),
                        it
                    )._id to it.getNumber("count").intValue()
                }
        }
    }

    private fun Aggregate.asValueBson(propertyName: String) = when (this) {
        Aggregate.Sum -> Accumulators.sum("value", "\$" + propertyName)
        Aggregate.Average -> Accumulators.avg("value", "\$" + propertyName)
        Aggregate.StandardDeviationPopulation -> Accumulators.stdDevPop("value", "\$" + propertyName)
        Aggregate.StandardDeviationSample -> Accumulators.stdDevSamp("value", "\$" + propertyName)
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>,
    ): Double? {
        val cs = condition.simplify()
        if (cs is Condition.Never) return null
        return access {
            aggregate(
                listOf(
                    Aggregates.match(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)),
                    Aggregates.group(null, aggregate.asValueBson(property.mongo))
                )
            )
                .toList()
                .map {
                    if (it.isNull("value")) null
                    else it.getNumber("value").doubleValue()
                }
                .firstOrNull()
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>,
    ): Map<Key, Double?> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return mapOf()
        return access {
            aggregate(
                listOf(
                    Aggregates.match(cs.bson(serializer, bson = bson, atlasSearch = atlasSearch)),
                    Aggregates.group("\$" + groupBy.mongo, aggregate.asValueBson(property.mongo))
                )
            )
                .toList()
                .associate {
                    bson.parse(
                        KeyHolder.serializer(groupBy.serializer),
                        it
                    )._id to (if (it.isNull("value")) null else it.getNumber("value").doubleValue())
                }
        }
    }

    private data class NeededVectorIndex(
        val field: String,
        val dimensions: Int,
        val metric: SimilarityMetric,
        val sparse: Boolean,
        val indexType: String,
    )

    private fun getVectorIndexes(): List<NeededVectorIndex> {
        val result = mutableListOf<NeededVectorIndex>()
        for (i in 0 until serializer.descriptor.elementsCount) {
            serializer.descriptor.getElementAnnotations(i).forEach { annotation ->
                if (annotation is VectorIndex) {
                    result.add(
                        NeededVectorIndex(
                            field = serializer.descriptor.getElementName(i),
                            dimensions = annotation.dimensions,
                            metric = annotation.metric,
                            sparse = annotation.sparse,
                            indexType = annotation.indexType,
                        )
                    )
                }
            }
        }
        return result
    }

    private var preparedAlready = false

    /**
     * Wait for a search index to become queryable.
     * MongoDB Atlas and mongot build search indexes asynchronously, so after creating
     * an index we need to poll until it's ready before we can use it for queries.
     */
    private suspend fun MongoCollection<BsonDocument>.waitForSearchIndexReady(
        indexName: String,
        timeoutMs: Long = 60_000,
        pollIntervalMs: Long = 500
    ) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val index = listSearchIndexes().name(indexName).toList().firstOrNull()
            if (index != null) {
                val queryable = index.getBoolean("queryable", false)
                val status = index.getString("status")
                if (queryable && status == "READY") {
                    return
                }
            }
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        context.report { Exception(
            "Search index '$indexName' on ${this.namespace.fullName} did not become ready within ${timeoutMs}ms"
        ) }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalSerializationApi::class)
    private suspend fun MongoCollection<BsonDocument>.prepare() {
        if (preparedAlready) return
        coroutineScope {
            val requireCompletion = ArrayList<Job>()

            // Create vector search indexes for Atlas
            if (atlasSearch) {
                getVectorIndexes().forEach { vectorIndex ->
                    requireCompletion += launch {
                        val indexName = "${vectorIndex.field}_vector_index"
                        val similarity = when (vectorIndex.metric) {
                            SimilarityMetric.Cosine -> "cosine"
                            SimilarityMetric.Euclidean -> "euclidean"
                            SimilarityMetric.DotProduct -> "dotProduct"
                            SimilarityMetric.Manhattan -> {
                                context.report { Exception(
                                    "Manhattan distance is not supported for MongoDB Atlas vector indexes on ${this@prepare.namespace.fullName}.${vectorIndex.field}"
                                ) }
                                return@launch
                            }
                        }

                        if (vectorIndex.sparse) {
                            context.report { Exception(
                                "Sparse vector indexes are not yet supported by MongoDB Atlas on ${this@prepare.namespace.fullName}.${vectorIndex.field}"
                            ) }
                            return@launch
                        }

                        // Collect filter fields from @Index annotations
                        val filterFields = serializer.descriptor.indexes()
                            .filter { it.fields.size == 1 } // Only single-field indexes can be filters
                            .map { it.fields.first() }
                            .filter { it != vectorIndex.field } // Don't include the vector field itself

                        val existing = listSearchIndexes().name(indexName).toList().firstOrNull()
                        if (existing == null) {
                            // Build fields list with vector field and filter fields
                            val fieldsDefinition = buildList {
                                add(documentOf(
                                    "type" to "vector",
                                    "path" to vectorIndex.field,
                                    "numDimensions" to vectorIndex.dimensions,
                                    "similarity" to similarity
                                ))
                                // Add filter fields for @Index annotated fields
                                filterFields.forEach { filterField ->
                                    add(documentOf(
                                        "type" to "filter",
                                        "path" to filterField
                                    ))
                                }
                            }

                            // Use SearchIndexModel with type="vectorSearch" for vector indexes
                            val searchIndexModel = SearchIndexModel(
                                indexName,
                                documentOf("fields" to fieldsDefinition),
                                SearchIndexType.vectorSearch()
                            )
                            try {
                                createSearchIndexes(listOf(searchIndexModel)).toList()
                                // Wait for the index to become queryable (mongot builds it asynchronously)
                                waitForSearchIndexReady(indexName)
                            } catch (e: MongoCommandException) {
                                if (e.errorCode == 26) {
                                    access.wholeDb {
                                        createCollection(this@prepare.namespace.collectionName)
                                    }
                                    createSearchIndexes(listOf(searchIndexModel)).toList()
                                    // Wait for the index to become queryable (mongot builds it asynchronously)
                                    waitForSearchIndexReady(indexName)
                                } else throw e
                            }
                        } else {
                            // Check if index needs updating
                            val existingFields = existing.getEmbedded(listOf("latestDefinition", "fields"), List::class.java) as? List<*>
                            val existingVectorField = existingFields?.firstOrNull { field ->
                                (field as? org.bson.Document)?.getString("type") == "vector"
                            } as? org.bson.Document

                            val needsUpdate = existingVectorField?.let { field ->
                                field.getString("path") != vectorIndex.field ||
                                field.getInteger("numDimensions") != vectorIndex.dimensions ||
                                field.getString("similarity") != similarity
                            } ?: true

                            if (needsUpdate) {
                                // Build updated fields list with vector field and filter fields
                                val updatedFieldsDefinition = buildList {
                                    add(documentOf(
                                        "type" to "vector",
                                        "path" to vectorIndex.field,
                                        "numDimensions" to vectorIndex.dimensions,
                                        "similarity" to similarity
                                    ))
                                    filterFields.forEach { filterField ->
                                        add(documentOf(
                                            "type" to "filter",
                                            "path" to filterField
                                        ))
                                    }
                                }
                                try {
                                    updateSearchIndex(
                                        indexName,
                                        documentOf("fields" to updatedFieldsDefinition)
                                    )
                                } catch (e: NoSuchElementException) {
                                    // suppress dumb issue in library
                                }
                            }
                        }
                    }
                }
            }

            serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.let {
                requireCompletion += launch {
                    if(atlasSearch) {
                        val name = "default"
                        val keys = documentOf()
                        val ser = DataClassPathSerializer(serializer)
                        for(key in it.fields) {
                            val path = ser.fromString(key)
                            @Suppress("UNCHECKED_CAST")
                            fun KSerializer<*>.unwrap(): KSerializer<*> {
                                return when {
                                    this.descriptor.isNullable -> this.innerElement()
                                    this.descriptor.kind == StructureKind.LIST -> this.innerElement()
                                    this.descriptor.kind == SerialKind.CONTEXTUAL -> context.internalSerializersModule.getContextual<Any>(this.descriptor.capturedKClass as KClass<Any>) as KSerializer<*>
                                    else -> this
                                }
                            }
                            val type = path.serializerAny.unwrap()
                            val mongoType = when(type.descriptor.serialName) {
                                "kotlin.Boolean" -> "boolean"
                                "kotlinx.datetime.Instant" -> "date"
                                "kotlin.Byte",
                                "kotlin.Short",
                                "kotlin.Int",
                                "kotlin.Long",
                                "kotlin.UByte",
                                "kotlin.UShort",
                                "kotlin.UInt",
                                "kotlin.ULong",
                                "kotlin.Float",
                                "kotlin.Double",
                                -> "number"
                                "kotlin.String" -> "string"
                                "com.lightningkite.UUID" -> "uuid"
                                else -> continue
                            }
                            keys[key] = listOf(documentOf("type" to mongoType))
                        }
                        val existing = listSearchIndexes().name(name).toList().firstOrNull()
                        if(existing == null) {
                            try {
                                createSearchIndex(
                                    name, documentOf(
                                        "mappings" to documentOf(
                                            "dynamic" to false,
                                            "fields" to keys
                                        )
                                    )
                                )
                            } catch(e: MongoCommandException) {
                                if(e.errorCode == 26) {
                                    access.wholeDb {
                                        createCollection(this@prepare.namespace.collectionName)
                                    }
                                    createSearchIndex(
                                        name, documentOf(
                                            "mappings" to documentOf(
                                                "dynamic" to false,
                                                "fields" to keys
                                            )
                                        )
                                    )
                                } else throw e
                            }
                        } else if(it.fields.any {
                                existing.getEmbedded(listOf("latestDefinition", "mappings", "fields", it), Any::class.java) == null
                            }) {
                            try {
                                updateSearchIndex(
                                    name, documentOf(
                                        "mappings" to documentOf(
                                            "dynamic" to false,
                                            "fields" to keys
                                        )
                                    )
                                )
                            } catch(e: NoSuchElementException) {
                                // suppress dumb issue in library
                            }
                        }
                    } else {
                        val name = "${namespace.fullName}TextIndex"
                        val options = IndexOptions().name(name)
                        val keys = documentOf(*it.fields.map { it.replace("?", "") to "text" }.toTypedArray())
                        try {
                            createIndex(keys, options)
                        } catch (e: MongoCommandException) {
                            if (e.errorCode == 85) {
                                //there is an exception if the parameters of an existing index are changed.
                                //then drop the index and create a new one
                                try {
                                    dropIndex(name)
                                    createIndex(
                                        keys,
                                        options
                                    )
                                } catch (e2: MongoCommandException) {
                                    context.report { Exception(
                                        "Creating text index failed on ${this@prepare.namespace.fullName}",
                                        e
                                    ) }
                                    context.report { Exception(
                                        "Creating text index failed on ${this@prepare.namespace.fullName} even after attempted removal",
                                        e2
                                    ) }
                                }
                            } else {
                                context.report { e }
                            }
                        }
                    }
                }
            }
            serializer.descriptor.indexes().forEach {
                if (it.type == GeoCoordinateGeoJsonSerializer.descriptor.serialName) {
                    requireCompletion += launch {
                        val nameOrDefault = it.name ?: it.fields[0].plus("_geo")
                        try {
                            createIndex(Indexes.geo2dsphere(it.fields), IndexOptions().name(nameOrDefault))
                        } catch (e: MongoCommandException) {
                            // Reform index if it already exists but with some difference in options
                            if (e.errorCode == 85) {
                                try {
                                    dropIndex(nameOrDefault)
                                    createIndex(Indexes.geo2dsphere(it.fields), IndexOptions().name(nameOrDefault))
                                } catch (e2: MongoCommandException) {
                                    context.report { Exception(
                                        "Creating geo index failed on ${this@prepare.namespace.fullName}",
                                        e
                                    ) }
                                        context.report { Exception(
                                        "Creating geo index failed on ${this@prepare.namespace.fullName} even after attempted removal",
                                        e2
                                    ) }
                                }
                            } else {
                                context.report { e }
                            }
                        }
                    }
                } else  {
                    requireCompletion += launch {
                        val keys = Sorts.orderBy(it.fields.map { field -> if(field.startsWith('-')) Sorts.descending(field.drop(1)) else Sorts.ascending(field) })
                        val options = IndexOptions().unique(it.unique).background(!it.unique).name(it.name)
                        try {
                            createIndex(keys, options)
                        } catch (e: MongoCommandException) {
                            // Reform index if it already exists but with some difference in options
                            if (e.errorCode == 85) {
                                try {
                                    dropIndex(keys)
                                    createIndex(keys, options)
                                } catch (e2: MongoCommandException) {
                                    context.report { Exception(
                                        "Creating ${if(it.unique) "unique " else ""}index failed on ${this@prepare.namespace.fullName}",
                                        e
                                    ) }
                                    context.report { Exception(
                                        "Creating ${if(it.unique) "unique " else ""}index failed on ${this@prepare.namespace.fullName} even after attempted removal",
                                        e2
                                    ) }
                                }
                            } else {
                                context.report { e }
                            }
                        }
                    }
                }
            }
            requireCompletion.joinAll()
        }
        preparedAlready = true
    }

    private fun sort(orderBy: List<SortPart<Model>>, lastly: Bson? = null): Bson = Sorts.orderBy(orderBy.map {
        if (it.ascending)
            Sorts.ascending(it.field.mongo)
        else
            Sorts.descending(it.field.mongo)
    } + listOfNotNull(lastly))

    override suspend fun findSimilar(
        vectorField: DataClassPath<Model, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<Model>> {
        if (!atlasSearch) {
            throw UnsupportedOperationException(
                "Vector search requires MongoDB Atlas or MongoDB Community 8.2+ with mongot. " +
                "Set atlasSearch=true in your MongoDB connection settings."
            )
        }

        val cs = condition.simplify()
        if (cs is Condition.Never) return emptyFlow()

        // Map similarity metric to MongoDB Atlas format
        val similarity = when (params.metric) {
            SimilarityMetric.Cosine -> "cosine"
            SimilarityMetric.Euclidean -> "euclidean"
            SimilarityMetric.DotProduct -> "dotProduct"
            SimilarityMetric.Manhattan -> throw UnsupportedOperationException(
                "Manhattan distance is not supported by MongoDB Atlas vector search. " +
                "Use Cosine, Euclidean, or DotProduct instead."
            )
        }

        // Build the vector search pipeline
        return access {
            val pipeline = buildList {
                // $vectorSearch must be the first stage
                add(documentOf(
                    "\$vectorSearch" to documentOf(
                        "index" to "${vectorField.mongo}_vector_index",
                        "path" to vectorField.mongo,
                        "queryVector" to params.queryVector.values.toList(),
                        "numCandidates" to (params.numCandidates ?: (params.limit * 10)),
                        "limit" to params.limit,
                        "similarity" to similarity
                    ).apply {
                        // Add exact search if requested
                        if (params.exact) {
                            this["exact"] = true
                        }
                        // Add filter condition if not always
                        if (cs !is Condition.Always) {
                            this["filter"] = cs.bson(serializer, atlasSearch = true, bson = bson)
                        }
                    }
                ))

                // Add $project stage to get the vector search score
                add(Aggregates.project(
                    Projections.fields(
                        Projections.metaVectorSearchScore("vector_search_score"),
                        Projections.include(*serializer.descriptor.elementNames.toList().toTypedArray())
                    )
                ))
            }

            aggregate<BsonDocument>(pipeline)
                .maxTime(maxQueryMs, TimeUnit.MILLISECONDS)
                .mapNotNull { doc ->
                    val score = doc.getNumber("vector_search_score")?.doubleValue()?.toFloat() ?: return@mapNotNull null

                    // Apply minScore filter if specified
                    val minScore = params.minScore
                    if (minScore != null && score < minScore) {
                        return@mapNotNull null
                    }

                    // MongoDB Atlas already returns scores in 0-1 range for cosine
                    // For euclidean, we need to normalize using 1/(1+distance)
                    val normalizedScore = when (params.metric) {
                        SimilarityMetric.Euclidean -> 1f / (1f + score)
                        SimilarityMetric.Cosine, SimilarityMetric.DotProduct -> score
                        else -> score
                    }

                    val model = bson.parse(serializer, doc)
                    ScoredResult(model, normalizedScore)
                }
        }
    }

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<Model, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<Model>> {
        throw UnsupportedOperationException(
            "Sparse vector search is not supported by MongoDB. " +
            "MongoDB currently only supports dense vectors (Embedding). " +
            "Consider using dense embeddings or a different database backend that supports sparse vectors."
        )
    }
}