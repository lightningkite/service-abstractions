package com.lightningkite.services.files.azure

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.ExternalPath
import com.lightningkite.services.files.test.FileSystemTests
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours

/**
 * Runs against `../secrets/azure-blob.txt` if present, otherwise against Azurite as started by
 * `scripts/azurite.sh`, using its well-known development account.
 * Skips when neither is reachable.
 */
class AzureBlobExternalFileSystemTest : FileSystemTests() {

    // JUnit makes an instance per test; sharing one file system avoids re-running the credential
    // chain (which shells out to `az`) and re-fetching the delegation key for every test.
    companion object {
        val sharedSystem: AzureBlobExternalFileSystem? by lazy {
            @Suppress("UNUSED_EXPRESSION")
            AzureBlobExternalFileSystem
            val url = File("../secrets/azure-blob.txt").takeIf { it.exists() }?.readText()?.trim()
                ?: "azure-blob://devstoreaccount1:Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==@test?endpoint=https://127.0.0.1:10000/devstoreaccount1"
            val fs = ExternalFileSystem.Settings(url).invoke("test", TestSettingContext()) as AzureBlobExternalFileSystem
            try {
                fs.container.createIfNotExists()
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.none { it is java.net.ConnectException }) throw e
                println("Skipping Azure Blob tests; storage not reachable")
                return@lazy null
            }
            fs
        }
    }

    override val system: AzureBlobExternalFileSystem? get() = sharedSystem

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
                val copied = destination.get()!!
                assertEquals("copied", copied.data.text())
                assertEquals(MediaType.Text.Plain, copied.mediaType)
            } finally {
                source.delete()
                destination.delete()
            }
        }
    }

    @Test
    fun rejectsTamperedAndUploadSignatures() {
        val system = system ?: return
        val file = system.root.then("signed.txt")
        val signed = file.signedUrl
        val otherFile = system.root.then("other.txt")
        assertFailsWith<IllegalArgumentException> {
            system.parseExternalUrl(otherFile.signedUrl.substringBefore('?') + "?" + signed.substringAfter('?'))
        }
        assertFailsWith<IllegalArgumentException> {
            system.parseExternalUrl(signed.replace(Regex("se=[^&]+"), "se=2099-01-01T00%3A00%3A00Z"))
        }
        assertFailsWith<IllegalArgumentException> { system.parseExternalUrl(file.uploadUrl(1.hours)) }
        assertFailsWith<IllegalArgumentException> { system.parseExternalUrl(signed.replace(Regex("se=[^&]+"), "se=garbage")) }
        assertFailsWith<IllegalArgumentException> { system.parseExternalUrl(signed.substringBefore('?')) }
    }

    @Test
    fun rejectsSegmentsAzureWouldResolve() {
        // Signing needs no network, so this runs without Azurite.
        val system = ExternalFileSystem.Settings(
            "azure-blob://devstoreaccount1:Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==@test?endpoint=https://127.0.0.1:10000/devstoreaccount1"
        ).invoke("test", TestSettingContext())
        for (path in listOf(ExternalPath("users", "..", "x"), ExternalPath("."), ExternalPath("file."), ExternalPath("a/b"))) {
            assertFailsWith<IllegalArgumentException>(path.parts.toString()) { system.signUrl(path) }
        }
        system.signUrl(ExternalPath("users", "..x", "a.b"))
    }

    @Test
    fun copyOfMissingSourceIsAnIllegalArgument() {
        val system = system ?: return
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                system.root.then("copy/missing.txt").copyTo(system.root.then("copy/destination.txt"))
            }
        }
    }
}
