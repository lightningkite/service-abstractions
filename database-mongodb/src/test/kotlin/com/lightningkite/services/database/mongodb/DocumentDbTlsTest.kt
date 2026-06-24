package com.lightningkite.services.database.mongodb

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.Database
import com.mongodb.MongoClientSettings
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DocumentDbTlsTest {
    @Test
    fun embeddedRdsBundleIsPackagedAndParseable() {
        val certs = MongoDatabase::class.java.getResourceAsStream("/rds-global-bundle.pem")
            .let { assertNotNull(it, "rds-global-bundle.pem must be on the classpath") }
            .use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        // The Amazon RDS global bundle contains the full set of regional/root CAs (108 at time of writing).
        assertTrue(certs.size > 50, "expected the full RDS CA bundle, got ${certs.size} certs")
    }

    @Test
    fun documentDbTlsGetsCustomTrustContextButOthersDoNot() {
        val ctx = TestSettingContext()
        // Force MongoDatabase's companion to register the mongodb URL handlers.
        MongoDatabase("warmup", clientSettings = MongoClientSettings.builder().build(), databaseName = "x", context = ctx)

        val docDb = Database.Settings(
            "mongodb://u:p@cluster.cluster-abc.us-west-2.docdb.amazonaws.com:27017/default?tls=true&retryWrites=false"
        ).invoke("docdb", ctx) as MongoDatabase
        // A DocumentDB host with TLS gets our explicit RDS-trusting context (build succeeds only if the
        // embedded bundle loaded and an SSLContext was constructed from it).
        val docDbContext = docDb.clientSettings.sslSettings.context
        assertNotNull(docDbContext)

        val atlasLike = Database.Settings(
            "mongodb://u:p@cluster0.example.mongodb.net:27017/default?tls=true"
        ).invoke("other", ctx) as MongoDatabase
        // A non-DocumentDB TLS host is left on the driver default — we must not force the RDS-only context
        // onto connections that rely on public CAs.
        assertNotSame(docDbContext, atlasLike.clientSettings.sslSettings.context)
    }
}
