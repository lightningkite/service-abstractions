package com.lightningkite.services.files.azure

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.azure.storage.blob.BlobServiceClientBuilder
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.test.FileSystemTests
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * The shared suite with the file system authenticating as an Azure identity instead of the account
 * key, so every signed URL comes from a user delegation key. Needs Azurite from `scripts/azurite.sh`,
 * which accepts any unsigned token with a plausible issuer and audience.
 */
class AzureBlobIdentityTest : FileSystemTests() {

    private val fakeIdentity = TokenCredential {
        val now = System.currentTimeMillis() / 1000
        val tenant = "72f988bf-86f1-41af-91ab-2d7cd011db47"
        val claims = """{"aud":"https://storage.azure.com","iss":"https://sts.windows.net/$tenant/",""" +
            """"iat":${now - 60},"nbf":${now - 60},"exp":${now + 3600},"oid":"11059fcc-5514-4800-b5f8-49808b9cfab6","tid":"$tenant"}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val jwt = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray()) + "." +
            encoder.encodeToString(claims.toByteArray()) + "."
        Mono.just(AccessToken(jwt, OffsetDateTime.now().plusHours(1)))
    }

    override val system: AzureBlobExternalFileSystem? by lazy {
        val container = BlobServiceClientBuilder()
            .endpoint("https://127.0.0.1:10000/devstoreaccount1")
            .credential(fakeIdentity)
            .buildClient()
            .getBlobContainerClient("identity-test")
        try {
            container.createIfNotExists()
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.none { it is java.net.ConnectException }) throw e
            println("Skipping Azure Blob identity tests; start scripts/azurite.sh")
            return@lazy null
        }
        AzureBlobExternalFileSystem("test", container, signedUrlDuration = 1.hours, context = TestSettingContext())
    }

    override fun uploadHeaders(builder: HttpRequestBuilder) {
        builder.headers.append("x-ms-blob-type", "BlockBlob")
    }

    @Test
    fun copyPreservesContent() {
        val system = system ?: return
        runBlocking {
            val source = system.root.then("copy/source.txt")
            val destination = system.root.then("copy/destination.txt")
            try {
                source.put(TypedData(Data.Text("copied"), MediaType.Text.Plain))
                source.copyTo(destination)
                assertEquals("copied", destination.get()!!.data.text())
            } finally {
                source.delete()
                destination.delete()
            }
        }
    }

    @Test
    fun rejectsSigningBeyondDelegationKeyLifetime() {
        val system = system ?: return
        assertFailsWith<IllegalArgumentException> {
            AzureBlobExternalFileSystem("test", system.container, signedUrlDuration = 6.days, context = TestSettingContext())
        }
        assertFailsWith<IllegalArgumentException> { system.root.then("x.txt").uploadUrl(6.days) }
    }

    @Test
    fun rejectsTamperedKeyStart() {
        val system = system ?: return
        val signed = system.root.then("signed.txt").signedUrl
        val yesterday = OffsetDateTime.parse(Regex("skt=([^&]+)").find(signed)!!.groupValues[1].replace("%3A", ":")).minusDays(1)
        assertFailsWith<IllegalArgumentException> {
            system.parseExternalUrl(signed.replace(Regex("skt=[^&]+"), "skt=" + yesterday.toString().replace(":", "%3A")))
        }
    }

    @Test
    fun settingsWithoutKeyUseDefaultAzureCredential() {
        @Suppress("UNUSED_EXPRESSION")
        AzureBlobExternalFileSystem
        ExternalFileSystem.Settings("azure-blob://someaccount@somecontainer?signedUrlDuration=1d")
            .invoke("test", TestSettingContext()) as AzureBlobExternalFileSystem
    }
}
