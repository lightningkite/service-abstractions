package com.lightningkite.services.files.azure

import com.azure.core.util.Context
import com.azure.core.util.polling.LongRunningOperationStatus
import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.storage.blob.BlobClient
import com.azure.storage.blob.BlobContainerClient
import com.azure.storage.blob.BlobServiceClientBuilder
import com.azure.storage.blob.models.BlobErrorCode
import com.azure.storage.blob.models.BlobHttpHeaders
import com.azure.storage.blob.models.BlobRange
import com.azure.storage.blob.models.BlobStorageException
import com.azure.storage.blob.models.ListBlobsOptions
import com.azure.storage.blob.models.UserDelegationKey
import com.azure.storage.blob.options.BlobInputStreamOptions
import com.azure.storage.blob.options.BlobParallelUploadOptions
import com.azure.storage.blob.sas.BlobSasPermission
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues
import com.azure.storage.common.StorageSharedKeyCredential
import com.azure.storage.common.Utility
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.ExternalFile
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.ExternalPath
import com.lightningkite.services.files.FileInfo
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinInstant

/**
 * An [ExternalFileSystem] backed by a single Azure Blob Storage container.
 *
 * If [container] was built with the account's shared key, that key signs the SAS URLs handed to
 * clients. Otherwise (an Azure identity, e.g. managed identity) they are signed with user delegation
 * keys, which needs the "Storage Blob Data Contributor" role and caps signed URLs at 5 days.
 * When [signedUrlDuration] is null, download URLs are unsigned and the container must allow public
 * blob read access.
 */
public class AzureBlobExternalFileSystem(
    override val name: String,
    public val container: BlobContainerClient,
    public val signedUrlDuration: Duration? = null,
    override val context: SettingContext,
) : ExternalFileSystem {

    private val sharedKey: StorageSharedKeyCredential? =
        StorageSharedKeyCredential.getSharedKeyCredentialFromPipeline(container.httpPipeline)

    private val delegationKeys = ConcurrentHashMap<LocalDate, UserDelegationKey>()

    init {
        require(sharedKey != null || signedUrlDuration == null || signedUrlDuration <= 5.days) {
            "signedUrlDuration can be at most 5 days when authenticating with an Azure identity"
        }
    }

    private fun blob(path: ExternalPath): BlobClient {
        // Azure resolves "." and ".." segments, so "users/1/../2/x" would land on "users/2/x". Segments are literal, so refuse them.
        for (part in path.parts) {
            require('/' !in part && !part.endsWith('.')) { "Azure Blob can't store the path segment '$part'" }
        }
        return container.getBlobClient(path.parts.joinToString("/"))
    }

    /**
     * The SAS query string granting [permission] on [blob] until [expiry].
     *
     * A delegation key covers 6 days from the UTC midnight starting [keyDay], so one key signs everything
     * issued in a day, and its expiry stays a day inside Azure's 7-day limit even with clock skew. Azure
     * derives a key from its parameters rather than storing it, so any instance asking for the same day
     * gets the same key, which is what lets any instance verify any other's URLs.
     */
    private fun sas(
        blob: BlobClient,
        expiry: OffsetDateTime,
        permission: BlobSasPermission,
        keyDay: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): String {
        val values = BlobServiceSasSignatureValues(expiry, permission)
        if (sharedKey != null) return blob.generateSas(values)
        val today = LocalDate.now(ZoneOffset.UTC)
        // keyDay can come from a client's URL; bounding it stops forged URLs from making us fetch keys.
        require(keyDay in today.minusDays(6)..today) { "Signed URL has an invalid key start" }
        val keyStart = keyDay.atStartOfDay().atOffset(ZoneOffset.UTC)
        require(expiry <= keyStart.plusDays(6)) { "A URL signed with an Azure identity can last at most 5 days" }
        delegationKeys.keys.removeIf { it < today.minusDays(6) }
        val key = delegationKeys.computeIfAbsent(keyDay) {
            container.serviceClient.getUserDelegationKey(keyStart, keyStart.plusDays(6))
        }
        return blob.generateUserDelegationSas(values, key)
    }

    private fun attributes(path: ExternalPath) = TelemetryAttributes {
        put(TelemetryKeys.File.path, context.telemetrySanitization.sanitizeFilePath(path.parts.joinToString("/")))
        put(TelemetryKeys.Rpc.system, "azure.blob")
    }

    override suspend fun flow(path: ExternalPath): Flow<ExternalPath> {
        // Without the trailing "/", listing "reports" would also match a sibling like "reportsBackup.zip".
        val prefix = if (path.parts.isEmpty()) "" else path.parts.joinToString("/") + "/"
        return flow {
            // Each hasNext() may fetch the next page; emitting inside the span would break flow's context rules.
            val pages = container.listBlobsByHierarchy("/", ListBlobsOptions().setPrefix(prefix), null).iterableByPage().iterator()
            while (true) {
                val page = telemetryTrace("list", attributes(path)) { if (pages.hasNext()) pages.next() else null } ?: break
                for (item in page.value) {
                    if (item.isPrefix == true) continue  // a subdirectory
                    emit(ExternalPath(item.name.split("/")))
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun head(path: ExternalPath): FileInfo? = telemetryTrace("head", attributes(path)) {
        withContext(Dispatchers.IO) {
            try {
                val properties = blob(path).properties
                FileInfo(
                    type = MediaType(properties.contentType),
                    size = properties.blobSize.bytes,
                    lastModified = properties.lastModified.toInstant().toKotlinInstant(),
                )
            } catch (e: BlobStorageException) {
                if (e.errorCode == BlobErrorCode.BLOB_NOT_FOUND) null else throw e
            }
        }
    }

    override suspend fun put(path: ExternalPath, content: TypedData): Unit = telemetryTrace("put", attributes(path)) {
        withContext(Dispatchers.IO) {
            // The SDK chunks a stream of unknown length itself, so no size is needed up front.
            content.data.source().asInputStream().use { stream ->
                val options = BlobParallelUploadOptions(stream)
                    .setHeaders(BlobHttpHeaders().setContentType(content.mediaType.toString()))
                blob(path).uploadWithResponse(options, null, Context.NONE)
            }
        }
        Unit
    }

    override suspend fun get(path: ExternalPath): TypedData? = telemetryTrace("get", attributes(path)) {
        withContext(Dispatchers.IO) {
            try {
                val stream = blob(path).openInputStream()
                TypedData.source(
                    source = stream.asSource().buffered(),
                    mediaType = MediaType(stream.properties.contentType),
                    size = stream.properties.blobSize,
                )
            } catch (e: BlobStorageException) {
                if (e.errorCode == BlobErrorCode.BLOB_NOT_FOUND) null else throw e
            }
        }
    }

    override suspend fun getRange(path: ExternalPath, range: LongRange): TypedData? {
        ExternalFileSystem.requireValidRange(range)
        return telemetryTrace("getRange", attributes(path)) {
            withContext(Dispatchers.IO) {
                // Computing the count of an open-ended `a..Long.MAX_VALUE` would overflow; null means "to the end".
                val count = if (range.last == Long.MAX_VALUE) null else range.last - range.first + 1
                try {
                    val stream = blob(path).openInputStream(BlobInputStreamOptions().setRange(BlobRange(range.first, count)))
                    val size = minOf(range.last, stream.properties.blobSize - 1) - range.first + 1
                    TypedData.source(stream.asSource().buffered(), MediaType(stream.properties.contentType), size)
                } catch (e: BlobStorageException) {
                    when {
                        e.errorCode == BlobErrorCode.BLOB_NOT_FOUND -> null
                        // A range starting at or past the end is a 416 here but an empty window in the contract.
                        // The 416 carries no content type, so fetch it.
                        e.statusCode == 416 -> head(path)?.let { TypedData.bytes(ByteArray(0), it.type) }
                        else -> throw e
                    }
                }
            }
        }
    }

    override suspend fun copyTo(path: ExternalPath, other: ExternalFile) {
        val otherSystem = other.fileSystem as? AzureBlobExternalFileSystem ?: return super.copyTo(path, other)
        telemetryTrace("copy", attributes(path)) {
            withContext(Dispatchers.IO) {
                val source = blob(path)
                val sourceUrl = "${source.blobUrl}?${sas(source, OffsetDateTime.now().plusHours(1), BlobSasPermission().setReadPermission(true))}"
                val result = try {
                    otherSystem.blob(other.path).beginCopy(sourceUrl, 1.seconds.toJavaDuration()).waitForCompletion()
                } catch (e: BlobStorageException) {
                    if (e.errorCode == BlobErrorCode.CANNOT_VERIFY_COPY_SOURCE && e.statusCode == 404) {
                        throw IllegalArgumentException("Source file does not exist: ${ExternalFile(this@AzureBlobExternalFileSystem, path)}", e)
                    }
                    throw e
                }
                check(result.status == LongRunningOperationStatus.SUCCESSFULLY_COMPLETED) {
                    "Copy from $path to $other ended with status ${result.status}"
                }
            }
        }
    }

    override suspend fun delete(path: ExternalPath): Unit = telemetryTrace("delete", attributes(path)) {
        withContext(Dispatchers.IO) { blob(path).deleteIfExists() }
        Unit
    }

    override fun signUrl(path: ExternalPath, timeout: Duration?): String {
        val blob = blob(path)
        val duration = timeout ?: signedUrlDuration ?: return blob.blobUrl
        val expiry = OffsetDateTime.now().plus(duration.toJavaDuration())
        return "${blob.blobUrl}?${sas(blob, expiry, BlobSasPermission().setReadPermission(true))}"
    }

    override fun uploadUrl(path: ExternalPath, timeout: Duration): String {
        val blob = blob(path)
        val expiry = OffsetDateTime.now().plus(timeout.toJavaDuration())
        return "${blob.blobUrl}?${sas(blob, expiry, BlobSasPermission().setCreatePermission(true).setWritePermission(true))}"
    }

    override fun parseLegacyUrl(url: String): ExternalFile? {
        val relative = url.removePrefix(container.blobContainerUrl + "/").takeIf { it != url } ?: return null
        // Utility.urlDecode, unlike URLDecoder, leaves '+' alone, which is correct for a URL path.
        return ExternalFile(this, ExternalPath(Utility.urlDecode(relative).split("/").filter { it.isNotEmpty() }))
    }

    override val referencesAreUnforgeable: Boolean get() = signedUrlDuration != null

    override fun parseExternalUrl(url: String): ExternalFile? {
        val file = parseLegacyUrl(url.substringBefore('?')) ?: return null
        if (signedUrlDuration == null) return file
        // Not BlobUrlParts: its CommonSasQueryParameters throws NullPointerException for an absent time.
        fun parseQuery(query: String) = query.split('&').associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        val presented = parseQuery(url.substringAfter('?', ""))
        fun time(key: String): OffsetDateTime = try {
            OffsetDateTime.parse(presented[key] ?: throw IllegalArgumentException("Signed URL is missing '$key'"))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Signed URL has a malformed '$key'", e)
        }
        val expiry = time("se")
        require(OffsetDateTime.now() < expiry) { "Signed URL has expired" }
        // Regenerating a read SAS with the presented expiry reproduces its signature only if we issued it.
        // Trap: the SDK always signs with its own service version (sv), so an SDK upgrade that bumps sv
        // rejects URLs issued before it until they're reissued.
        val keyDay = if ("skt" in presented) time("skt").toLocalDate() else LocalDate.now(ZoneOffset.UTC)
        val regenerated = parseQuery(sas(blob(file.path), expiry, BlobSasPermission().setReadPermission(true), keyDay))
        require(MessageDigest.isEqual(presented["sig"].orEmpty().toByteArray(), regenerated.getValue("sig").toByteArray())) {
            "Signed URL has an invalid signature"
        }
        return file
    }

    public companion object {
        init {
            // azure-blob://<account>:<accountKey>@<container>?signedUrlDuration=1h&endpoint=https://...
            // azure-blob://<account>@<container>?...   (DefaultAzureCredential: managed identity, env, az login)
            // The account key is used literally (base64 contains no '@'). endpoint defaults to
            // https://<account>.blob.core.windows.net; override it for Azurite or sovereign clouds.
            // signedUrlDuration defaults to 1h; "forever" or "null" means unsigned (public container).
            // A user-assigned managed identity is picked via the AZURE_CLIENT_ID environment variable.
            ExternalFileSystem.Settings.register("azure-blob") { name, url, context ->
                val withoutScheme = url.substringAfter("://")
                val authority = withoutScheme.substringBefore('?').trimEnd('/')
                require('@' in authority) {
                    "Invalid Azure Blob URL. Expected azure-blob://<account>[:<accountKey>]@<container>?signedUrlDuration=...&endpoint=..."
                }
                val userInfo = authority.substringBeforeLast('@')
                val account = userInfo.substringBefore(':')
                val key = userInfo.substringAfter(':', "").takeIf { it.isNotEmpty() }
                val containerName = authority.substringAfterLast('@')
                require(containerName.isNotEmpty() && '/' !in containerName) { "Invalid Azure Blob container name '$containerName'" }
                val params = withoutScheme.substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate { it.substringBefore('=') to it.substringAfter('=', "") }

                val signedUrlDuration = params["signedUrlDuration"].let {
                    when {
                        it == null -> 1.hours
                        it == "forever" || it == "null" -> null
                        it.all { it.isDigit() } -> it.toLong().seconds
                        else -> Duration.parse(it)
                    }
                }

                val service = BlobServiceClientBuilder().endpoint(params["endpoint"] ?: "https://$account.blob.core.windows.net")
                if (key != null) service.credential(StorageSharedKeyCredential(account, key))
                else service.credential(DefaultAzureCredentialBuilder().build())

                AzureBlobExternalFileSystem(
                    name = name,
                    container = service.buildClient().getBlobContainerClient(containerName),
                    signedUrlDuration = signedUrlDuration,
                    context = context,
                )
            }
        }
    }
}
