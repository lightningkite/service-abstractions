package com.lightningkite.services.database.mongodb

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.test.*
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.BeforeTest

/**
 * Integration tests for DocumentDB compatibility.
 *
 * Tests run automatically using a local DocumentDB Docker container (DocumentDB local).
 * You can also point at a real AWS DocumentDB cluster by setting DOCUMENTDB_TEST_URL:
 *
 *   DOCUMENTDB_TEST_URL="mongodb://user:pass@cluster.docdb.amazonaws.com:27017/test?retryWrites=false&tls=true&tlsCAFile=/path/to/global-bundle.pem&replicaSet=rs0"
 *
 * DocumentDB-specific notes:
 * - Atlas Search and vector search are not supported (atlasSearch=false)
 * - retryWrites=false is required
 * - Regex conditions use BsonRegularExpression (not separate $options field)
 * - Geo queries use $nearSphere with GeoJSON geometry (not $geoWithin/$centerSphere)
 */
object DocumentDbTestDatabase {
    val url: String? = System.getenv("DOCUMENTDB_TEST_URL")

    val isAvailable: Boolean by lazy {
        if (url != null) return@lazy true
        DocumentDbDockerContainer.ensureStarted()
    }

    val mongoClient: MongoDatabase? by lazy {
        if (!isAvailable) return@lazy null
        val connectionUrl = url ?: DocumentDbDockerContainer.connectionUrl
        val databaseName = Regex("""mongodb(?:\+srv)?://[^/]*/([^?]+).*""")
            .matchEntire(connectionUrl)?.groupValues?.getOrNull(1) ?: "test"
        // DocumentDB local uses a self-signed certificate, so we need to trust all certs in local testing.
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }
        MongoDatabase(
            name = "documentdb-test",
            databaseName = databaseName,
            clientSettings = MongoClientSettings.builder()
                .retryWrites(false)
                .applyConnectionString(ConnectionString(connectionUrl))
                .applyToSslSettings { it.enabled(true).invalidHostNameAllowed(true).context(sslContext) }
                .build(),
            atlasSearch = false,
            supportsCollation = false,
            context = TestSettingContext()
        )
    }
}

private fun docDb(): Database = DocumentDbTestDatabase.mongoClient
    ?: error("DOCUMENTDB_TEST_URL not set — this should have been caught by @BeforeTest")

private fun skipIfUnavailable() {
    if (!DocumentDbTestDatabase.isAvailable)
        throw org.junit.AssumptionViolatedException("Skipping: DocumentDB local container unavailable and DOCUMENTDB_TEST_URL not set")
}

class DocumentDbConditionTests : ConditionTests() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}

class DocumentDbModificationTests : ModificationTests() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}

class DocumentDbOperationsTests : OperationsTests() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}

class DocumentDbSortTest : SortTest() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}

class DocumentDbAggregationsTest : AggregationsTest() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}

class DocumentDbIndexTests : IndexTests() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}

class DocumentDbInlinePropertiesTests : InlinePropertiesTests() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}

class DocumentDbMetaTest : MetaTest() {
    override val database: Database get() = docDb()
    @BeforeTest fun checkAvailable() = skipIfUnavailable()
    @kotlin.test.Test fun start() {}
}
