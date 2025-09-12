package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.data.TypedData
import dev.whyoleg.cryptography.algorithms.HMAC
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.annotations.TestOnly
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(SealedSerializationApi::class)
public class ExternalServerFileSerializer(
    public val clock: Clock,
    public val scanners: List<FileScanner>,
    public val fileSystems: List<PublicFileSystem>,
    public val jail: FileObject = fileSystems.first().root.then("upload-jail"),
    public val ready: FileObject = fileSystems.first().root.then("uploaded"),
    public val onUse: (FileObject) -> Unit,
    public val key: HMAC.Key
) : KSerializer<ServerFile> {
    private val primary = fileSystems.first()
    private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.files.ExternalServerFileSerializer")

    private val uploadFile: suspend (data: TypedData) -> FileObject = {
        scanners.scan(it)
        val d = primary.root.thenRandom("uploaded", "file")
        d.put(it)
        d
    }

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = object : SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "com.lightningkite.lightningserver.files.ServerFile/external"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = listOf()
    }

    /**
     * We need to sign the file's URL as we serialize it.
     */
    override fun serialize(encoder: Encoder, value: ServerFile) {
        val url = value.location
        val file = fileSystems.firstNotNullOfOrNull {
            // We don't need to check signatures; this is coming from us, after all.
            it.parseInternalUrl(url)
        }
        if (file == null) {
            // TODO: Is this dangerous?  If someone could inject a foreign url, this allows them to direct users to malware
            logger.warn {
                "The given url (${value.location}) does not start with any files root. Known roots: ${
                    fileSystems.flatMap { it.rootUrls }.joinToString()
                }"
            }
            encoder.encodeString(value.location)
        } else {
            encoder.encodeString(file.signedUrl)
        }
    }

    public fun certifyForUse(jailPath: String, expiration: Duration): String =
        signUrl("future:$jailPath", expiration)

    public fun certifyAlreadyScannedForUse(readyPath: String, expiration: Duration): String =
        signUrl("future-prescanned:$readyPath", expiration)

    public suspend fun scan(value: String, expiration: Duration): String {
        val raw = value
        if (raw.startsWith("future-prescanned:")) return value
        if (!raw.startsWith("future:")) throw IllegalArgumentException("URL scheme is not 'future'")
        if (!verifyUrl(raw)) throw IllegalArgumentException("URL is not valid")
        val withoutFuture = raw.substringAfter("future:").substringBefore('?')
        val safe = ready.then(withoutFuture)
        val source = jail.then(withoutFuture)
        scanners.copyAndScan(source, safe)
        return certifyAlreadyScannedForUse(withoutFuture, expiration)
    }

    /**
     * We need to ensure that the input is something that the server has validly signed - that's how we determine if this is OK.
     */
    override fun deserialize(decoder: Decoder): ServerFile {
        val raw = decoder.decodeString()
        when {
            raw.startsWith("future:") -> {
                if (!verifyUrl(raw)) throw IllegalArgumentException("URL is not valid")
                val source = jail.then(raw.substringAfter("future:").substringBefore('?'))
                val safe = ready.then(raw.substringAfter("future:").substringBefore('?'))
                runBlocking { scanners.copyAndScan(source, safe) }
                return ServerFile(safe.url)
            }

            raw.startsWith("future-prescanned:") -> {
                if (!verifyUrl(raw)) throw IllegalArgumentException("URL is not valid")
                val safe = ready.then(raw.substringAfter("future-prescanned:").substringBefore('?'))
                onUse(safe)
                return ServerFile(safe.url)
            }

            raw.startsWith("data:") -> {
                val type = MediaType(raw.removePrefix("data:").substringBefore(';'))
                val base64 = raw.substringAfter("base64,")
                val data = Base64.decode(base64)
                //            if(data.size < 500) return ServerFile(raw)
                return runBlocking {
                    uploadFile(TypedData.bytes(data, type))
                }.let { ServerFile(it.url) }
            }

            else -> {
                val file = fileSystems.firstNotNullOfOrNull {
                    it.parseExternalUrl(raw)
                } ?: throw IllegalArgumentException(
                    "The given url (${raw.substringBefore('?')}) does not start with any files root.  Known roots: ${
                        fileSystems.flatMap { it.rootUrls }.joinToString()
                    }"
                )
                return ServerFile(file.url)
            }
        }
    }

    @TestOnly
    internal fun signUrl(url: String, expiration: Duration): String {
        if(url.contains('?')) throw IllegalArgumentException("URL cannot contain query parameters.")
        return url.plus("?useUntil=${clock.now().plus(expiration).toEpochMilliseconds()}").let {
            it + "&token=" + key.signatureGenerator().generateSignatureBlocking(it.toByteArray())
                .let { Base64.UrlSafe.encode(it) }
        }
    }

    @TestOnly
    internal fun verifyUrl(url: String): Boolean {
        val params = url.substringAfter('?')
            .split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return verifyUrl(
            url.substringBefore('?'),
            params["useUntil"]?.toLong() ?: throw IllegalArgumentException("Parameter 'useUntil' is missing in '$url'"),
            params["token"] ?: throw IllegalArgumentException("Parameter 'token' is missing in '$url'")
        )
    }

    @TestOnly
    internal fun verifyUrl(url: String, exp: Long, token: String): Boolean {
        return (Instant.fromEpochMilliseconds(exp) > clock.now()) && key.signatureVerifier().tryVerifySignatureBlocking(
            data = (url.substringBefore('?') + "?useUntil=$exp").toByteArray(),
            signature = Base64.UrlSafe.decode(token)
        )
    }
}
