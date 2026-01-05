package com.lightningkite.services.database.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.cassandra.serialization.CassandraSerialization
import com.lightningkite.services.database.cassandra.serialization.toCqlType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer

private val logger = KotlinLogging.logger("CassandraTable")

/** Maximum number of parallel queries for OR optimizations */
private const val MAX_PARALLEL_OR_QUERIES = 10

/**
 * Cassandra implementation of the Table interface.
 */
@OptIn(ExperimentalSerializationApi::class)
public class CassandraTable<Model : Any>(
    override val serializer: KSerializer<Model>,
    private val session: CqlSession,
    private val keyspace: String,
    private val tableName: String,
    private val context: SettingContext
) : Table<Model> {

    private val schema: CassandraSchema<Model> = CassandraSchema.fromSerializer(serializer, tableName)
    private val serialization = CassandraSerialization(context.internalSerializersModule)
    private val quotedKeyspace = keyspace.quoteCql()
    private val quotedTableName = tableName.quoteCql()
    private val fullyQualifiedTable = "$quotedKeyspace.$quotedTableName"
    private val cqlGenerator = CqlGenerator(schema, fullyQualifiedTable)
    private val analyzer = ConditionAnalyzer(schema)
    private val computedHandler = ComputedColumnsHandler(schema, serializer)

    private var prepared = false
    private val preparedStatements = mutableMapOf<String, PreparedStatement>()

    /**
     * Ensures the table schema exists in Cassandra.
     */
    public suspend fun ensureSchema() {
        if (prepared) return
        withContext(Dispatchers.IO) {
            createTableIfNotExists()
            createIndexes()
            prepared = true
        }
    }

    private suspend fun createTableIfNotExists() {
        val columns = buildList {
            for (i in 0 until serializer.descriptor.elementsCount) {
                val name = serializer.descriptor.getElementName(i).quoteCql()
                val type = serializer.descriptor.getElementDescriptor(i).toCqlType()
                add("$name $type")
            }
        }

        val partitionKeyNames = schema.partitionKeys.map { it.name.quoteCql() }
        val clusteringKeyNames = schema.clusteringColumns.map { it.name.quoteCql() }

        val pkPart = if (partitionKeyNames.size == 1) {
            partitionKeyNames.first()
        } else {
            "(${partitionKeyNames.joinToString(", ")})"
        }

        val primaryKey = if (clusteringKeyNames.isEmpty()) {
            "PRIMARY KEY ($pkPart)"
        } else {
            "PRIMARY KEY ($pkPart, ${clusteringKeyNames.joinToString(", ")})"
        }

        val clusteringOrder = if (clusteringKeyNames.isNotEmpty()) {
            val orderClauses = schema.clusteringColumns.map { col ->
                val dir = if (col.descending) "DESC" else "ASC"
                "${col.name.quoteCql()} $dir"
            }
            " WITH CLUSTERING ORDER BY (${orderClauses.joinToString(", ")})"
        } else ""

        val cql = """
            CREATE TABLE IF NOT EXISTS $fullyQualifiedTable (
                ${columns.joinToString(",\n                ")},
                $primaryKey
            )$clusteringOrder
        """.trimIndent()

        session.executeAsync(cql).toCompletableFuture().await()

        // After table creation, check for schema migrations
        migrateSchema()
    }

    private suspend fun migrateSchema() {
        // Query existing columns from system schema
        val existingColumns = try {
            val query = """
                SELECT column_name
                FROM system_schema.columns
                WHERE keyspace_name = ? AND table_name = ?
            """.trimIndent()
            val result = session.executeAsync(
                SimpleStatement.newInstance(query, keyspace, tableName)
            ).toCompletableFuture().await()

            buildSet {
                for (row in result.currentPage()) {
                    row.getString("column_name")?.let { add(it) }
                }
            }
        } catch (e: InvalidQueryException) {
            // AWS Keyspaces doesn't support system_schema queries - this is expected
            logger.debug { "system_schema query not supported (likely AWS Keyspaces), skipping column detection for $fullyQualifiedTable" }
            emptySet<String>()
        } catch (e: Exception) {
            // Unexpected error - log and continue without migration
            logger.warn(e) { "Failed to query schema for $fullyQualifiedTable, skipping migration" }
            emptySet<String>()
        }

        // Determine which columns need to be added
        val modelColumns = buildMap {
            for (i in 0 until serializer.descriptor.elementsCount) {
                val name = serializer.descriptor.getElementName(i)
                val type = serializer.descriptor.getElementDescriptor(i).toCqlType()
                put(name, type)
            }
        }

        val columnsToAdd = if (existingColumns.isEmpty()) {
            // If we couldn't query schema, don't try to add anything
            // Table was just created, so all columns should be present
            emptySet<String>()
        } else {
            modelColumns.keys - existingColumns
        }

        // Add missing columns
        for (columnName in columnsToAdd) {
            val columnType = modelColumns[columnName] ?: continue
            try {
                val alterCql = """
                    ALTER TABLE $fullyQualifiedTable
                    ADD ${columnName.quoteCql()} $columnType
                """.trimIndent()
                session.executeAsync(alterCql).toCompletableFuture().await()
                logger.info { "Added column '$columnName' ($columnType) to $fullyQualifiedTable" }
            } catch (e: InvalidQueryException) {
                // Column already exists - this is expected in concurrent startup scenarios
                if (e.message?.contains("already exists", ignoreCase = true) == true ||
                    e.message?.contains("duplicate column", ignoreCase = true) == true) {
                    logger.debug { "Column '$columnName' already exists in $fullyQualifiedTable (concurrent migration)" }
                } else {
                    // Other InvalidQueryException - likely a schema constraint issue
                    logger.warn(e) { "Failed to add column '$columnName' to $fullyQualifiedTable: ${e.message}" }
                }
            } catch (e: Exception) {
                // Unexpected error - log but continue to try other columns
                logger.error(e) { "Unexpected error adding column '$columnName' to $fullyQualifiedTable" }
            }
        }

        // Note: Cassandra/Keyspaces doesn't easily support dropping columns
        // Removed columns will simply be ignored during deserialization
        // This is safe because the serialization layer uses ignoreUnknownKeys = true
    }

    private suspend fun createIndexes() {
        // Create SASI indexes
        for ((column, info) in schema.sasiIndexes) {
            val mode = when (info.mode) {
                SasiMode.PREFIX -> "PREFIX"
                SasiMode.CONTAINS -> "CONTAINS"
                SasiMode.SPARSE -> "SPARSE"
            }
            val analyzer = when (info.analyzer) {
                SasiAnalyzer.NONE -> ""
                SasiAnalyzer.STANDARD -> ", 'analyzer_class': 'org.apache.cassandra.index.sasi.analyzer.StandardAnalyzer'"
                SasiAnalyzer.NON_TOKENIZING -> ", 'analyzer_class': 'org.apache.cassandra.index.sasi.analyzer.NonTokenizingAnalyzer'"
            }
            val caseSensitive = if (info.caseSensitive) "" else ", 'case_sensitive': 'false'"

            val indexName = "${tableName}_${column}_sasi_idx".quoteCql()
            val cql = """
                CREATE CUSTOM INDEX IF NOT EXISTS $indexName
                ON $fullyQualifiedTable (${column.quoteCql()})
                USING 'org.apache.cassandra.index.sasi.SASIIndex'
                WITH OPTIONS = {'mode': '$mode'$caseSensitive$analyzer}
            """.trimIndent()

            try {
                session.executeAsync(cql).toCompletableFuture().await()
                logger.debug { "Created SASI index on $fullyQualifiedTable.$column (mode=$mode)" }
            } catch (e: InvalidQueryException) {
                when {
                    e.message?.contains("already exists", ignoreCase = true) == true -> {
                        logger.debug { "SASI index on $fullyQualifiedTable.$column already exists" }
                    }
                    e.message?.contains("SASI", ignoreCase = true) == true ||
                    e.message?.contains("custom index", ignoreCase = true) == true -> {
                        // SASI not available (e.g., AWS Keyspaces doesn't support SASI)
                        logger.warn { "SASI indexes not supported on this Cassandra instance. Column '$column' will not be indexed. Consider using @SaiIndex instead." }
                    }
                    else -> {
                        logger.warn(e) { "Failed to create SASI index on $fullyQualifiedTable.$column: ${e.message}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error creating SASI index on $fullyQualifiedTable.$column" }
            }
        }

        // Create SAI indexes
        for (column in schema.saiIndexes) {
            val indexName = "${tableName}_${column}_sai_idx".quoteCql()
            val cql = """
                CREATE INDEX IF NOT EXISTS $indexName
                ON $fullyQualifiedTable (${column.quoteCql()})
                USING 'sai'
            """.trimIndent()

            try {
                session.executeAsync(cql).toCompletableFuture().await()
                schema.actualSaiIndexes.add(column)
                logger.debug { "Created SAI index on $fullyQualifiedTable.$column" }
            } catch (e: InvalidQueryException) {
                when {
                    e.message?.contains("already exists", ignoreCase = true) == true -> {
                        schema.actualSaiIndexes.add(column) // Index already exists, so it's usable
                        logger.debug { "SAI index on $fullyQualifiedTable.$column already exists" }
                    }
                    e.message?.contains("SAI", ignoreCase = true) == true ||
                    e.message?.contains("sai", ignoreCase = true) == true -> {
                        // SAI not available (requires Cassandra 5.0+ or DataStax Astra)
                        // Don't add to actualSaiIndexes - this column is not indexed
                        logger.warn { "SAI indexes not supported on this Cassandra instance. Column '$column' will not be indexed. Upgrade to Cassandra 5.0+ for SAI support." }
                    }
                    else -> {
                        logger.warn(e) { "Failed to create SAI index on $fullyQualifiedTable.$column: ${e.message}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error creating SAI index on $fullyQualifiedTable.$column" }
            }
        }

        // Mark that SAI index verification is complete
        schema.saiIndexesVerified = true
    }

    // ===== Table Interface Implementation =====

    /**
     * Find records matching the condition.
     *
     * Note: The `maxQueryMs` parameter is not currently supported by the Cassandra driver.
     * Cassandra has its own timeout mechanisms configured at the driver/cluster level.
     */
    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        @Suppress("UNUSED_PARAMETER") maxQueryMs: Long
    ): Flow<Model> {
        ensureSchema()

        val normalizedCondition = ConditionNormalizer.normalize(condition.simplify())

        // Short-circuit for impossible conditions
        if (normalizedCondition is Condition.Never) {
            return emptyFlow()
        }

        // Check for Inside (IN) optimization on SAI columns
        val insideOptimization = tryInsideOptimization(normalizedCondition)
        if (insideOptimization != null) {
            return executeOrOptimizedQuery(insideOptimization, orderBy, skip, limit)
        }

        // Check for OR optimization opportunity
        val orOptimization = tryOrOptimization(normalizedCondition)
        if (orOptimization != null) {
            return executeOrOptimizedQuery(orOptimization, orderBy, skip, limit)
        }

        val analysis = analyzer.analyze(normalizedCondition)
        val canPushSort = cqlGenerator.canPushSort(orderBy)

        // Log warnings for query performance issues
        if (analysis.warnings.isNotEmpty()) {
            logger.warn { "Query on $tableName has performance warnings: ${analysis.warnings.joinToString("; ")}" }
        }
        if (analysis.requiresFullScan) {
            logger.warn { "Query on $tableName requires full table scan - consider adding indexes or restructuring the query" }
        }

        return flow {
            val rows: Flow<Row> = if (analysis.cqlCondition != null && analysis.cqlCondition !is Condition.Never) {
                val query = cqlGenerator.generateSelect(
                    condition = analysis.cqlCondition,
                    orderBy = if (canPushSort) orderBy else emptyList(),
                    // Only apply limit at CQL level if no app filter and sort is pushed
                    limit = if (analysis.appFilter == null && canPushSort) limit + skip else null
                )

                executeQuery(query)
            } else {
                // Full scan fallback
                val query = CqlQuery("SELECT * FROM $fullyQualifiedTable", emptyList())
                executeQuery(query)
            }

            // Collect and filter all rows
            val models = mutableListOf<Model>()
            rows.collect { row ->
                val model = serialization.deserializeRow(row, serializer)

                // Apply app-side filter
                val passesFilter = analysis.appFilter?.invoke(model) ?: true
                if (passesFilter) {
                    models.add(model)
                }
            }

            // Apply app-side sorting if needed
            val sortedModels = if (!canPushSort && orderBy.isNotEmpty()) {
                val comparator = orderBy.comparator
                if (comparator != null) {
                    models.sortedWith(comparator)
                } else {
                    models
                }
            } else {
                models
            }

            // Apply skip and limit
            var count = 0
            var skipped = 0
            for (model in sortedModels) {
                if (skipped < skip) {
                    skipped++
                } else if (count < limit) {
                    emit(model)
                    count++
                } else {
                    break
                }
            }
        }
    }

    /**
     * Checks if the condition is an OR that can be optimized via parallel queries.
     * Returns the list of pushable branch conditions, or null if optimization isn't applicable.
     */
    private fun tryOrOptimization(condition: Condition<Model>): List<Condition<Model>>? {
        if (condition !is Condition.Or) return null

        val pushableBranches = mutableListOf<Condition<Model>>()

        for (branch in condition.conditions) {
            val branchAnalysis = analyzer.analyze(branch)
            // Only use OR optimization if ALL branches are fully pushable (no app-side filter)
            if (branchAnalysis.cqlCondition == null || branchAnalysis.appFilter != null) {
                return null
            }
            pushableBranches.add(branchAnalysis.cqlCondition)
        }

        // Need at least 2 branches for OR optimization to make sense
        return if (pushableBranches.size >= 2) pushableBranches else null
    }

    /**
     * Checks if the condition contains an Inside (IN) on a SAI-indexed column that can be
     * optimized by splitting into parallel equality queries.
     *
     * SAI indexes don't support IN queries, but they support equality. So we can transform:
     *   field IN (a, b, c) → run field=a, field=b, field=c in parallel
     *
     * Also handles Inside inside AND:
     *   cond1 AND field IN (a, b, c) → run (cond1 AND field=a), (cond1 AND field=b), (cond1 AND field=c)
     *
     * Returns null if no optimization is applicable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryInsideOptimization(condition: Condition<Model>): List<Condition<Model>>? {
        return when (condition) {
            is Condition.OnField<*, *> -> {
                val onField = condition as Condition.OnField<Model, Any?>
                tryExpandInsideOnField(onField)
            }

            is Condition.And -> {
                // Check if any sub-condition is an expandable Inside
                for ((index, sub) in condition.conditions.withIndex()) {
                    if (sub is Condition.OnField<*, *>) {
                        val onField = sub as Condition.OnField<Model, Any?>
                        val expansion = tryExpandInsideOnField(onField)
                        if (expansion != null) {
                            // Distribute: create branch for each expanded value combined with other conditions
                            val others = condition.conditions.filterIndexed { i, _ -> i != index }
                            val branches = expansion.map { expanded ->
                                if (others.isEmpty()) {
                                    expanded
                                } else {
                                    Condition.And(others + expanded)
                                }
                            }
                            // Verify all branches are fully pushable
                            val allPushable = branches.all { branch ->
                                val analysis = analyzer.analyze(branch)
                                analysis.cqlCondition != null && analysis.appFilter == null
                            }
                            if (allPushable && branches.size >= 2) {
                                return branches
                            }
                        }
                    }
                }
                null
            }

            else -> null
        }
    }

    /**
     * Tries to expand an OnField condition with Inside into multiple equality conditions.
     * Only applies to SAI-indexed columns (not partition keys or clustering columns which support IN natively).
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryExpandInsideOnField(onField: Condition.OnField<Model, Any?>): List<Condition<Model>>? {
        val fieldName = onField.key.name
        val inner = onField.condition

        // Only optimize if:
        // 1. It's an Inside condition
        // 2. The field is SAI-indexed
        // 3. NOT a partition key or clustering column (they support IN natively in CQL)
        if (inner !is Condition.Inside<*>) return null
        if (!schema.hasWorkingSaiIndex(fieldName)) return null
        if (schema.isPartitionKey(fieldName) || schema.isClusteringColumn(fieldName)) return null

        val inside = inner as Condition.Inside<Any?>
        val values = inside.values.toList()

        // Need at least 2 values for parallel optimization to make sense
        if (values.size < 2) return null

        // Expand to equality conditions
        return values.map { value ->
            Condition.OnField(onField.key, Condition.Equal(value)) as Condition<Model>
        }
    }

    /**
     * Executes an OR query by running parallel queries for each branch and deduplicating.
     * Uses a semaphore to limit concurrent queries to avoid overwhelming the cluster.
     */
    private fun executeOrOptimizedQuery(
        branches: List<Condition<Model>>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int
    ): Flow<Model> = flow {
        val canPushSort = cqlGenerator.canPushSort(orderBy)

        // Limit concurrent queries to avoid overwhelming the cluster
        val parallelismLimit = Semaphore(MAX_PARALLEL_OR_QUERIES)

        // Run all branch queries in parallel (limited) and collect results
        val allModels = coroutineScope {
            branches.map { branchCondition ->
                async {
                    parallelismLimit.withPermit {
                        val query = cqlGenerator.generateSelect(
                            condition = branchCondition,
                            orderBy = emptyList(), // Sort after dedup
                            limit = null // Can't limit individual branches
                        )
                        val models = mutableListOf<Model>()
                        executeQuery(query).collect { row ->
                            models.add(serialization.deserializeRow(row, serializer))
                        }
                        models
                    }
                }
            }.awaitAll().flatten()
        }

        // Deduplicate by primary key (partition key + clustering columns)
        val deduped = deduplicateByPrimaryKey(allModels)

        // Apply sorting
        val sortedModels = if (orderBy.isNotEmpty()) {
            val comparator = orderBy.comparator
            if (comparator != null) {
                deduped.sortedWith(comparator)
            } else {
                deduped
            }
        } else {
            deduped
        }

        // Apply skip and limit
        var count = 0
        var skipped = 0
        for (model in sortedModels) {
            if (skipped < skip) {
                skipped++
            } else if (count < limit) {
                emit(model)
                count++
            } else {
                break
            }
        }
    }

    /**
     * Deduplicates models by their primary key values.
     */
    private fun deduplicateByPrimaryKey(models: List<Model>): List<Model> {
        val seen = mutableSetOf<List<Any?>>()
        val result = mutableListOf<Model>()

        for (model in models) {
            val pkValues = buildList {
                for (pk in schema.partitionKeys) {
                    add(pk.get(model))
                }
                for (ck in schema.clusteringColumns) {
                    add(ck.get(model))
                }
            }

            if (seen.add(pkValues)) {
                result.add(model)
            }
        }

        return result
    }

    private fun executeQuery(query: CqlQuery): Flow<Row> = flow {
        val statement = SimpleStatement.newInstance(query.cql, *query.parameters.toTypedArray())
        val result = session.executeAsync(statement).toCompletableFuture().await()

        var currentPage: AsyncResultSet = result
        while (true) {
            for (row in currentPage.currentPage()) {
                emit(row)
            }

            if (currentPage.hasMorePages()) {
                currentPage = currentPage.fetchNextPage().toCompletableFuture().await()
            } else {
                break
            }
        }
    }

    override suspend fun count(condition: Condition<Model>): Int {
        ensureSchema()

        val normalizedCondition = ConditionNormalizer.normalize(condition.simplify())

        // Short-circuit for impossible conditions
        if (normalizedCondition is Condition.Never) {
            return 0
        }

        val analysis = analyzer.analyze(normalizedCondition)

        return if (analysis.cqlCondition != null && analysis.appFilter == null) {
            val query = cqlGenerator.generateCount(analysis.cqlCondition)
            val statement = SimpleStatement.newInstance(query.cql, *query.parameters.toTypedArray())
            val result = session.executeAsync(statement).toCompletableFuture().await()
            result.one()?.getLong(0)?.toInt() ?: 0
        } else {
            // Fall back to counting after app-side filter
            find(condition).count()
        }
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>
    ): Map<Key, Int> {
        // Cassandra doesn't natively support GROUP BY with COUNT for arbitrary columns
        // We have to materialize and count client-side
        val results = mutableMapOf<Key, Int>()
        find(condition).collect { model ->
            val key = groupBy.get(model)
            if (key != null) {
                results[key] = (results[key] ?: 0) + 1
            }
        }
        return results
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>
    ): Double? {
        // Cassandra doesn't support arbitrary aggregations well
        // We compute client-side
        var sum = 0.0
        var count = 0
        var sumSquares = 0.0

        find(condition).collect { model ->
            val value = property.get(model)?.toDouble()
            if (value != null) {
                sum += value
                count++
                sumSquares += value * value
            }
        }

        if (count == 0) return null

        return when (aggregate) {
            Aggregate.Sum -> sum
            Aggregate.Average -> sum / count
            Aggregate.StandardDeviationPopulation -> {
                val mean = sum / count
                val variance = (sumSquares / count) - (mean * mean)
                kotlin.math.sqrt(variance)
            }
            Aggregate.StandardDeviationSample -> {
                if (count < 2) return null
                val mean = sum / count
                val variance = (sumSquares - 2 * mean * sum + count * mean * mean) / (count - 1)
                kotlin.math.sqrt(variance)
            }
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>
    ): Map<Key, Double?> {
        // Group-aggregate client-side
        data class Accumulator(var sum: Double = 0.0, var count: Int = 0, var sumSquares: Double = 0.0)

        val groups = mutableMapOf<Key, Accumulator>()

        find(condition).collect { model ->
            val key = groupBy.get(model) ?: return@collect
            val value = property.get(model)?.toDouble() ?: return@collect
            val acc = groups.getOrPut(key) { Accumulator() }
            acc.sum += value
            acc.count++
            acc.sumSquares += value * value
        }

        return groups.mapValues { (_, acc) ->
            if (acc.count == 0) return@mapValues null
            when (aggregate) {
                Aggregate.Sum -> acc.sum
                Aggregate.Average -> acc.sum / acc.count
                Aggregate.StandardDeviationPopulation -> {
                    val mean = acc.sum / acc.count
                    val variance = (acc.sumSquares / acc.count) - (mean * mean)
                    kotlin.math.sqrt(variance)
                }
                Aggregate.StandardDeviationSample -> {
                    if (acc.count < 2) null
                    else {
                        val mean = acc.sum / acc.count
                        val variance = (acc.sumSquares - 2 * mean * acc.sum + acc.count * mean * mean) / (acc.count - 1)
                        kotlin.math.sqrt(variance)
                    }
                }
            }
        }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> {
        ensureSchema()

        val result = mutableListOf<Model>()
        for (model in models) {
            val values = serialization.serializeToMap(model, serializer)
            val computedValues = computedHandler.computeDerivedValues(model, values)

            val columns = computedValues.keys.toList()
            // Use IF NOT EXISTS for atomic duplicate detection (lightweight transaction)
            val cql = cqlGenerator.generateInsert(columns, ifNotExists = true)
            val params = columns.map { computedValues[it] }

            val statement = SimpleStatement.newInstance(cql, *params.toTypedArray())
            val insertResult = session.executeAsync(statement).toCompletableFuture().await()

            // Check if the insert was applied (IF NOT EXISTS returns [applied] column)
            val row = insertResult.one()
            val applied = row?.getBoolean("[applied]") ?: true
            if (!applied) {
                throw UniqueViolationException(
                    cause = null,
                    key = "_id",
                    table = tableName
                )
            }
            result.add(model)
        }
        return result
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        ensureSchema()
        val existing = find(condition, orderBy, 0, 1).firstOrNull()
        return if (existing != null) {
            atomicUpdate(existing, model)
            EntryChange(existing, model)
        } else {
            EntryChange(null, null)
        }
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        ensureSchema()
        val existing = find(condition, orderBy, 0, 1).firstOrNull()
        return if (existing != null) {
            atomicUpdate(existing, model)
            true
        } else {
            false
        }
    }

    /**
     * Upserts a record: if a record matching `condition` exists, applies `modification` to it;
     * otherwise inserts `model`.
     *
     * Uses atomic operations where possible:
     * - Update path: uses atomicUpdate for safe modification
     * - Insert path: uses IF NOT EXISTS to handle concurrent inserts
     */
    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> {
        ensureSchema()

        // First check if a record matching the condition exists
        val existing = find(condition, emptyList(), 0, 1).firstOrNull()

        return if (existing != null) {
            // Record exists - apply modification using atomicUpdate
            val updated = modification(existing)
            atomicUpdate(existing, updated)
            EntryChange(existing, updated)
        } else {
            // No matching record - insert the model using IF NOT EXISTS
            // If insert fails due to race, re-check and update instead
            val values = serialization.serializeToMap(model, serializer)
            val computedValues = computedHandler.computeDerivedValues(model, values)
            val columns = computedValues.keys.toList()
            val params = columns.map { computedValues[it] }

            val insertCql = cqlGenerator.generateInsert(columns, ifNotExists = true)
            val insertResult = session.executeAsync(
                SimpleStatement.newInstance(insertCql, *params.toTypedArray())
            ).toCompletableFuture().await()

            val row = insertResult.one()
            val applied = row?.getBoolean("[applied]") ?: true

            if (applied) {
                EntryChange(null, model)
            } else {
                // Insert failed - record was inserted by another process, apply modification instead
                val nowExisting = find(condition, emptyList(), 0, 1).firstOrNull()
                if (nowExisting != null) {
                    val updated = modification(nowExisting)
                    atomicUpdate(nowExisting, updated)
                    EntryChange(nowExisting, updated)
                } else {
                    // Edge case: record was inserted and then deleted - retry insert
                    insert(listOf(model))
                    EntryChange(null, model)
                }
            }
        }
    }

    /**
     * Upserts a record: if a record matching `condition` exists, applies `modification` to it;
     * otherwise inserts `model`.
     *
     * Returns whether an existing record was found (true) or a new one was inserted (false).
     *
     * Uses atomic operations where possible:
     * - Update path: uses atomicUpdate for safe modification
     * - Insert path: uses IF NOT EXISTS to handle concurrent inserts
     */
    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean {
        ensureSchema()

        // First check if a record matching the condition exists
        val existing = find(condition, emptyList(), 0, 1).firstOrNull()

        return if (existing != null) {
            // Record exists - apply modification using atomicUpdate
            val updated = modification(existing)
            atomicUpdate(existing, updated)
            true
        } else {
            // No matching record - insert the model using IF NOT EXISTS
            val values = serialization.serializeToMap(model, serializer)
            val computedValues = computedHandler.computeDerivedValues(model, values)
            val columns = computedValues.keys.toList()
            val params = columns.map { computedValues[it] }

            val insertCql = cqlGenerator.generateInsert(columns, ifNotExists = true)
            val insertResult = session.executeAsync(
                SimpleStatement.newInstance(insertCql, *params.toTypedArray())
            ).toCompletableFuture().await()

            val row = insertResult.one()
            val applied = row?.getBoolean("[applied]") ?: true

            if (applied) {
                false // Inserted new record
            } else {
                // Insert failed - record was inserted by another process, apply modification
                val nowExisting = find(condition, emptyList(), 0, 1).firstOrNull()
                if (nowExisting != null) {
                    val updated = modification(nowExisting)
                    atomicUpdate(nowExisting, updated)
                }
                true // Updated existing record
            }
        }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        ensureSchema()
        val existing = find(condition, orderBy, 0, 1).firstOrNull()
        return if (existing != null) {
            val updated = modification(existing)
            atomicUpdate(existing, updated)
            EntryChange(existing, updated)
        } else {
            EntryChange(null, null)
        }
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        ensureSchema()
        val existing = find(condition, orderBy, 0, 1).firstOrNull()
        return if (existing != null) {
            val updated = modification(existing)
            atomicUpdate(existing, updated)
            true
        } else {
            false
        }
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> {
        ensureSchema()
        val changes = mutableListOf<EntryChange<Model>>()
        find(condition).collect { existing ->
            val updated = modification(existing)
            atomicUpdate(existing, updated)
            changes.add(EntryChange(existing, updated))
        }
        return CollectionChanges(changes)
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int {
        ensureSchema()
        var count = 0
        find(condition).collect { existing ->
            val updated = modification(existing)
            atomicUpdate(existing, updated)
            count++
        }
        return count
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        val existing = find(condition, orderBy, 0, 1).firstOrNull()
        if (existing != null) {
            deleteByPrimaryKey(existing)
        }
        return existing
    }

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val existing = find(condition, orderBy, 0, 1).firstOrNull()
        return if (existing != null) {
            deleteByPrimaryKey(existing)
            true
        } else {
            false
        }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        val deleted = mutableListOf<Model>()
        find(condition).collect { existing ->
            deleteByPrimaryKey(existing)
            deleted.add(existing)
        }
        return deleted
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        var count = 0
        find(condition).collect { existing ->
            deleteByPrimaryKey(existing)
            count++
        }
        return count
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun deleteByPrimaryKey(model: Model) {
        val pkNames = schema.partitionKeys.map { it.name.quoteCql() }
        val ckNames = schema.clusteringColumns.map { it.name.quoteCql() }
        val allKeys = pkNames + ckNames

        val whereClauses = allKeys.joinToString(" AND ") { "$it = ?" }
        val params = (schema.partitionKeys.map { convertToCassandraType(it.property.get(model)) } +
                schema.clusteringColumns.map { convertToCassandraType(it.property.get(model)) }).toTypedArray()

        val cql = "DELETE FROM $fullyQualifiedTable WHERE $whereClauses"
        session.executeAsync(SimpleStatement.newInstance(cql, *params)).toCompletableFuture().await()
    }

    /**
     * Checks if two models have the same primary key (partition key + clustering columns).
     */
    private fun primaryKeyEquals(old: Model, new: Model): Boolean {
        for (pk in schema.partitionKeys) {
            if (pk.get(old) != pk.get(new)) return false
        }
        for (ck in schema.clusteringColumns) {
            if (ck.get(old) != ck.get(new)) return false
        }
        return true
    }

    /**
     * Performs an atomic update from old model to new model.
     *
     * If primary key is unchanged: uses UPDATE statement
     * If primary key changed: uses BATCH with DELETE + INSERT for atomicity
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun atomicUpdate(old: Model, new: Model) {
        if (primaryKeyEquals(old, new)) {
            // Primary key unchanged - use UPDATE
            // We need to include ALL non-key columns (including null ones) to properly update
            val newValues = serialization.serializeToMap(new, serializer)
            val computedValues = computedHandler.computeDerivedValues(new, newValues)

            val pkNames = schema.partitionKeys.map { it.name }.toSet()
            val ckNames = schema.clusteringColumns.map { it.name }.toSet()
            val keyNames = pkNames + ckNames

            // Get all field names from the serializer, not just non-null ones
            val allFieldNames = (0 until serializer.descriptor.elementsCount)
                .map { serializer.descriptor.getElementName(it) }
            val nonKeyColumns = allFieldNames.filter { it !in keyNames }

            if (nonKeyColumns.isNotEmpty()) {
                val setClauses = nonKeyColumns.joinToString(", ") { "${it.quoteCql()} = ?" }
                val whereClauses = (schema.partitionKeys.map { it.name } + schema.clusteringColumns.map { it.name })
                    .joinToString(" AND ") { "${it.quoteCql()} = ?" }

                val updateCql = "UPDATE $fullyQualifiedTable SET $setClauses WHERE $whereClauses"
                // Use computedValues if present, otherwise null (which is correct for Cassandra)
                val updateParams = nonKeyColumns.map { computedValues[it] } +
                    schema.partitionKeys.map { convertToCassandraType(it.property.get(old)) } +
                    schema.clusteringColumns.map { convertToCassandraType(it.property.get(old)) }

                session.executeAsync(
                    SimpleStatement.newInstance(updateCql, *updateParams.toTypedArray())
                ).toCompletableFuture().await()
            }
        } else {
            // Primary key changed - use BATCH for atomicity
            val deleteStatement = buildDeleteStatement(old)
            val insertStatement = buildInsertStatement(new)

            val batch = BatchStatement.newInstance(DefaultBatchType.LOGGED, deleteStatement, insertStatement)
            session.executeAsync(batch).toCompletableFuture().await()
        }
    }

    /**
     * Builds a DELETE statement for a model.
     */
    private fun buildDeleteStatement(model: Model): SimpleStatement {
        val pkNames = schema.partitionKeys.map { it.name.quoteCql() }
        val ckNames = schema.clusteringColumns.map { it.name.quoteCql() }
        val allKeys = pkNames + ckNames

        val whereClauses = allKeys.joinToString(" AND ") { "$it = ?" }
        val params = (schema.partitionKeys.map { convertToCassandraType(it.property.get(model)) } +
                schema.clusteringColumns.map { convertToCassandraType(it.property.get(model)) }).toTypedArray()

        val cql = "DELETE FROM $fullyQualifiedTable WHERE $whereClauses"
        return SimpleStatement.newInstance(cql, *params)
    }

    /**
     * Builds an INSERT statement for a model.
     */
    private fun buildInsertStatement(model: Model): SimpleStatement {
        val values = serialization.serializeToMap(model, serializer)
        val computedValues = computedHandler.computeDerivedValues(model, values)

        val columns = computedValues.keys.toList()
        val cql = cqlGenerator.generateInsert(columns, ifNotExists = false)
        val params = columns.map { computedValues[it] }

        return SimpleStatement.newInstance(cql, *params.toTypedArray())
    }

    // ===== Vector Search (Not natively supported by Cassandra) =====

    override suspend fun findSimilar(
        vectorField: DataClassPath<Model, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long
    ): Flow<ScoredResult<Model>> {
        throw UnsupportedOperationException(
            "Vector search is not natively supported by Apache Cassandra. " +
            "Consider using DataStax Astra DB which provides vector search capabilities, " +
            "or implement client-side similarity search."
        )
    }

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<Model, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long
    ): Flow<ScoredResult<Model>> {
        throw UnsupportedOperationException(
            "Sparse vector search is not supported by Apache Cassandra."
        )
    }
}
