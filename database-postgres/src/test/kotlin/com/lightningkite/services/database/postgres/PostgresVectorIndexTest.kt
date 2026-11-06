package com.lightningkite.services.database.postgres

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.test.*
import com.lightningkite.services.embedding.EmbeddingModelId
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.EmptySerializersModule
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Needs pgvector: Docker for the pgvector container, or POSTGRES_VECTOR_TEST_URL. */
class PostgresVectorIndexTests : VectorIndexTests() {
    private val manualUrl = System.getenv("POSTGRES_VECTOR_TEST_URL")

    override val database: VectorDatabase by lazy {
        PostgresDatabase("test", TestSettingContext(EmptySerializersModule())) {
            if (manualUrl != null) PooledDatabase(Database.connect(manualUrl, driver = "org.postgresql.Driver"), null)
            else PostgresDockerContainer.getContainer()!!.let {
                PooledDatabase(Database.connect(it.jdbcUrl, driver = "org.postgresql.Driver", user = it.username, password = it.password), null)
            }
        }
    }

    @BeforeTest
    fun requirePgvector() {
        assumeTrue("pgvector needs Docker or POSTGRES_VECTOR_TEST_URL", manualUrl != null || PostgresDockerContainer.isAvailable)
    }
}

/** What runs without pgvector: storage on an embedded Postgres. */
class PostgresVectorIndexStorageTest {
    private val database = PostgresDatabase("test", TestSettingContext(EmptySerializersModule())) {
        PooledDatabase(Database.connect(embedded.postgresDatabase), null)
    }

    private companion object {
        val embedded: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
    }

    @Test
    fun storesEntriesInOneBatch() = runTest {
        val definition = VectorIndexDefinition("vectorStorage", String.serializer(), VectorSpace(EmbeddingModelId("test-model"), 3))
        // prepare() would also need pgvector, so the table alone is created here.
        database.prepare(DatabaseTableDefinition(VectorIndexEntry.serializer(String.serializer()), definition.storageName))
        val index = database.index(definition)
        index.delete(index.ids().toList())
        index.upsert(listOf(VectorIndexEntry("a", Embedding.of(1f, 0f, 0f)), VectorIndexEntry("b", Embedding.of(0f, 1f, 0f))))
        index.upsert(listOf(VectorIndexEntry("a", Embedding.of(0f, 0f, 1f))))
        assertEquals(VectorIndexEntry("a", Embedding.of(0f, 0f, 1f)), index.get(listOf("a")).single())
        assertEquals(setOf("a", "b"), index.ids().toList().toSet())
        index.delete(listOf("b"))
        assertEquals(setOf("a"), index.ids().toList().toSet())
    }
}
