package com.lightningkite.services.files

import com.lightningkite.services.*
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.MediaType
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline


/**
 * A service for scanning and validating file content.
 *
 * FileScanner implementations can verify that uploaded files match their claimed media types,
 * scan for malware, check file integrity, or perform other validation operations.
 */
public interface FileScanner : Service {

    /**
     * Scans the provided ExternalFile for validation (e.g., type mismatch, malware detected).
     *
     * @param file The ExternalFile to scan
     * @throws FileScanException if validation fails.
     */
    public suspend fun scan(file: ExternalFile)

    /**
     * Settings for a FileScanner.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "file://files",
    ) : Setting<FileScanner> {

        public companion object : UrlSettingParser<FileScanner>() {
            init {
                register("mime") { name, url, context ->
                    CheckMimeFileScanner(
                        name = name,
                        context = context
                    )
                }
            }
        }

        override fun invoke(name: String, context: SettingContext): FileScanner {
            return parse(name, url, context)
        }
    }
}

/**
 * Exception thrown when file scanning detects an issue.
 *
 * This can indicate type mismatches, malware detection, or other validation failures.
 */
public open class FileScanException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Scans the ExternalFile using multiple file scanners in parallel.
 *
 * @param file The ExternalFile to scan
 * @throws FileScanException if any scanner fails validation
 */
public suspend fun List<FileScanner>.scan(file: ExternalFile) {
    coroutineScope {
        this@scan
            .map { launch { it.scan(file) } }
            .joinAll()
    }
}

/**
 * A FileScanner that validates files by checking their magic numbers (file signatures).
 *
 * This scanner reads the first 16 bytes of a file to verify that the binary signature
 * matches the claimed media type. This helps prevent users from uploading malicious
 * files disguised with incorrect extensions.
 *
 * Currently, supports validation for most Image, Video, and Audio types and some Application types
 */
public class CheckMimeFileScanner(
    override val name: String,
    override val context: SettingContext,
) : FileScanner {

    override suspend fun scan(file: ExternalFile) {
        // A magic number lives in the first 16 bytes, so a ranged read keeps this from pulling a
        // multi-gigabyte object across the network just to look at its header.
        //
        // The range is a maximum, not a requirement: files shorter than a format's signature are
        // legitimate (an empty or few-byte file is not automatically invalid), so `getRange` clamps at
        // end-of-file and the signature match below fails cleanly when the bytes available cannot
        // complete a signature.
        val item = file.getRange(0L..15L) ?: throw FileScanException("File does not exist")
        val bytes = item.use { it.data.bytes() }

        if (signatures[item.mediaType]?.none { it.matches(bytes) } == true) {
            throw FileScanException("Mime type mismatch; doesn't fit the ${item.mediaType.subtype} format")
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private class Segment(val offset: Int, val bytes: UByteArray)
    private class Signature(vararg val segments: Segment) {

        @OptIn(ExperimentalUnsignedTypes::class)
        fun matches(data: ByteArray): Boolean {
            val d = data.asUByteArray()
            for (seg in segments) {
                if (data.size < seg.offset + seg.bytes.size) return false
                for (i in seg.bytes.indices)
                    if (d[seg.offset + i] != seg.bytes[i]) return false
            }
            return true
        }
    }

    private companion object {
        // @formatter:off
        @OptIn(ExperimentalUnsignedTypes::class)
        private val signatures: Map<MediaType, List<Signature>> = mapOf(
            MediaType.Application.Docx to listOf(
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x03u, 0x04u))), // 50 4B 03 04
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x05u, 0x06u))), // 50 4B 05 06
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x07u, 0x08u))), // 50 4B 07 08
            ),
            MediaType.Application.GZip to listOf(
                Signature(Segment(0, ubyteArrayOf(0x1Fu, 0x8Bu))), // 1F 8B
            ),
            MediaType.Application.OctetStream to listOf(
                Signature(Segment(0, ubyteArrayOf(0x7Fu, 0x45u, 0x4Cu, 0x46u))), // 7F 45 4C 46
            ),
            MediaType.Application.Pdf to listOf(
                Signature(Segment(0, ubyteArrayOf(0x25u, 0x50u, 0x44u, 0x46u, 0x2Du))), // 25 50 44 46 2D
            ),
            MediaType.Application.Pptx to listOf(
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x03u, 0x04u))), // 50 4B 03 04
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x05u, 0x06u))), // 50 4B 05 06
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x07u, 0x08u))), // 50 4B 07 08
            ),
            MediaType.Application.Xml to listOf(
                Signature(Segment(0, ubyteArrayOf(0x3Cu, 0x3Fu, 0x78u, 0x6Du, 0x6Cu, 0x20u))),                                                                          // 3C 3F 78 6D 6C 20
                Signature(Segment(0, ubyteArrayOf(0x3Cu, 0x00u, 0x3Fu, 0x00u, 0x78u, 0x00u, 0x6Du, 0x00u, 0x6Cu, 0x00u, 0x20u))),                                       // 3C 00 3F 00 78 00 6D 00 6C 00 20
                Signature(Segment(0, ubyteArrayOf(0x3Cu, 0x00u, 0x00u, 0x00u, 0x3Fu, 0x00u, 0x00u, 0x00u, 0x78u, 0x00u, 0x00u, 0x00u, 0x6Du, 0x00u, 0x00u, 0x00u))),    // 3C 00 00 00 3F 00 00 00 78 00 00 00 6D 00 00 00
            ),
            MediaType.Application.Zip to listOf(
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x03u, 0x04u))), // 50 4B 03 04
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x05u, 0x06u))), // 50 4B 05 06
                Signature(Segment(0, ubyteArrayOf(0x50u, 0x4Bu, 0x07u, 0x08u))), // 50 4B 07 08
            ),
            MediaType.Audio.AAC to listOf(
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xF1u))), //  FF F1
            ),
            MediaType.Audio.FLAC to listOf(
                Signature(Segment(0, ubyteArrayOf(0x66u, 0x4Cu, 0x61u, 0x43u))), // 66 4C 61 43
            ),
            MediaType.Audio.MP3 to listOf(
                Signature(Segment(0, ubyteArrayOf(0x49u, 0x44u, 0x33u))), // 49 44 33
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xFBu))), // FF FB
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xF3u))), // FF F3
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xF2u))), // FF F2
            ),
            MediaType.Audio.MP4 to listOf(
                Signature(Segment(4, ubyteArrayOf(0x66u, 0x74u, 0x79u, 0x70u, 0x69u, 0x73u, 0x6Fu, 0x6Du))), // ?? ?? ?? ?? 66 74 79 70 69 73 6F 6D
                Signature(Segment(4, ubyteArrayOf(0x66u, 0x74u, 0x79u, 0x70u, 0x4Du, 0x53u, 0x4Eu, 0x56u))), // ?? ?? ?? ?? 66 74 79 70 4D 53 4E 56
            ),
            MediaType.Audio.MPEG to listOf(
                Signature(Segment(0, ubyteArrayOf(0x00u, 0x00u, 0x01u, 0xB3u))), // 00 00 01 B3
                Signature(Segment(0, ubyteArrayOf(0x00u, 0x00u, 0x01u, 0xBAu))), // 00 00 01 B3
            ),
            MediaType.Audio.OGG to listOf(
                Signature(Segment(0, ubyteArrayOf(0x4Fu, 0x67u, 0x67u, 0x53u, 0x00u, 0x02u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u))), // 4F 67 67 53 00 02 00 00 00 00 00 00 00 00
                Signature(Segment(0, ubyteArrayOf(0x4Fu, 0x67u, 0x67u, 0x53u))),                                                                       // 4F 67 67 53
            ),
            MediaType.Audio.OPUS to listOf(
                Signature(Segment(0, ubyteArrayOf(0x4Fu, 0x67u, 0x67u, 0x53u, 0x00u))), // 4F 67 67 53 00
            ),
            MediaType.Audio.WAV to listOf(
                Signature(                                                       // 52 49 46 46 ?? ?? ?? ?? 57 41 56 45
                    Segment(0, ubyteArrayOf(0x52u, 0x49u, 0x46u, 0x46u)),
                    Segment(8, ubyteArrayOf(0x57u, 0x41u, 0x56u, 0x45u))
                ),
            ),
            MediaType.Image.AVIF to listOf(
                Signature(Segment(4, ubyteArrayOf(0x66u, 0x74u, 0x79u, 0x70u, 0x61u, 0x76u, 0x69u, 0x66u))), // ?? ?? ?? ?? 66 74 79 70 61 76 69 66
            ),
            MediaType.Image.BMP to listOf(
                Signature(Segment(0, ubyteArrayOf(0x42u, 0x4Du))), // 42 4D
            ),
            MediaType.Image.GIF to listOf(
                Signature(Segment(0, ubyteArrayOf(0x47u, 0x49u, 0x46u, 0x38u, 0x37u, 0x61u))), // 47 49 46 38 37 61
                Signature(Segment(0, ubyteArrayOf(0x47u, 0x49u, 0x46u, 0x38u, 0x39u, 0x61u))), // 47 49 46 38 39 61
            ),
            MediaType.Image.JPEG to listOf(
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xD8u, 0xFFu, 0xDBu))),                                                         // FF D8 FF DB
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xD8u, 0xFFu, 0xE0u, 0x00u, 0x10u, 0x4Au, 0x46u, 0x49u, 0x46u, 0x00u, 0x01u))), // FF D8 FF E0 00 10 4A 46 49 46 00 01
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xD8u, 0xFFu, 0xEEu))),                                                         // FF D8 FF EE
                Signature(Segment(0, ubyteArrayOf(0xFFu, 0xD8u, 0xFFu, 0xE0u))),                                                         // FF D8 FF E0
                Signature(                                                                                                               // FF D8 FF E1 ?? ?? 45 78 69 66 00 00
                    Segment(0, ubyteArrayOf(0xFFu, 0xD8u, 0xFFu, 0xE0u)),
                    Segment(6, ubyteArrayOf(0x45u, 0x78u, 0x69u, 0x66u, 0x00u, 0x00u))
                ),
            ),
            MediaType.Image.PNG to listOf(
                Signature(Segment(0, ubyteArrayOf(0x89u, 0x50u, 0x4Eu, 0x47u, 0x0Du, 0x0Au, 0x1Au, 0x0Au))), // 89 50 4E 47 0D 0A 1A 0A
            ),
            MediaType.Image.SVG to listOf(
                Signature(Segment(0, ubyteArrayOf(0x3Cu, 0x73u, 0x76u, 0x67u))), // 3C 73 76 67
            ),
            MediaType.Image.Tiff to listOf(
                Signature(Segment(0, ubyteArrayOf(0x4Du, 0x4Du, 0x00u, 0x2Au))), // 4D 4D 00 2A
                Signature(Segment(0, ubyteArrayOf(0x4Du, 0x4Du, 0x00u, 0x2Bu))), // 4D 4D 00 2B
                Signature(Segment(0, ubyteArrayOf(0x49u, 0x49u, 0x2Au, 0x00u))), // 49 49 2A 00
                Signature(Segment(0, ubyteArrayOf(0x49u, 0x49u, 0x2Bu, 0x00u))), // 49 49 2B 00
            ),
            MediaType.Image.WebP to listOf(
                Signature(                                                  // 52 49 46 46 ?? ?? ?? ?? 57 45 42 50
                    Segment(0, ubyteArrayOf(0x52u, 0x49u, 0x46u, 0x46u)),
                    Segment(8, ubyteArrayOf(0x57u, 0x45u, 0x42u, 0x50u))
                ),
            ),
            MediaType.Video.AVI to listOf(
                Signature(Segment(4, ubyteArrayOf(0x41u, 0x56u, 0x49u, 0x20u))),    // 41 56 49 20
                Signature(                                                          // 52 49 46 46 ?? ?? ?? ?? 41 56 49 20
                    Segment(0, ubyteArrayOf(0x52u, 0x49u, 0x46u, 0x46u)),
                    Segment(8, ubyteArrayOf(0x41u, 0x56u, 0x49u, 0x20u)),
                ),
            ),
            MediaType.Video.OGG to listOf(
                Signature(Segment(0, ubyteArrayOf(0x4Fu, 0x67u, 0x67u, 0x53u, 0x00u, 0x02u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u))), // 4F 67 67 53 00 02 00 00 00 00 00 00 00 00
                Signature(Segment(0, ubyteArrayOf(0x4Fu, 0x67u, 0x67u, 0x53u))),                                                                       // 4F 67 67 53
            ),
            MediaType.Video.MKV to listOf(
                Signature(Segment(0, ubyteArrayOf(0x1Au, 0x45u, 0xDFu, 0xA3u))), // 1A 45 DF A3
            ),
            MediaType.Video.MP4 to listOf(
                Signature(Segment(4, ubyteArrayOf(0x66u, 0x74u, 0x79u, 0x70u, 0x69u, 0x73u, 0x6Fu, 0x6Du))), // ?? ?? ?? ?? 66 74 79 70 69 73 6F 6D
                Signature(Segment(4, ubyteArrayOf(0x66u, 0x74u, 0x79u, 0x70u, 0x4Du, 0x53u, 0x4Eu, 0x56u))), // ?? ?? ?? ?? 66 74 79 70 4D 53 4E 56
            ),
            MediaType.Video.MPEG to listOf(
                Signature(Segment(0, ubyteArrayOf(0x00u, 0x00u, 0x01u, 0xB3u))), // 00 00 01 B3
                Signature(Segment(0, ubyteArrayOf(0x00u, 0x00u, 0x01u, 0xBAu))), // 00 00 01 B3
            ),
            MediaType.Video.QuickTime to listOf(
                Signature(Segment(4, ubyteArrayOf(0x6Du, 0x6Fu, 0x6Fu, 0x76u, 0x00u))), // ?? ?? ?? ?? 6D 6F 6F 76 00
                Signature(Segment(4, ubyteArrayOf(0x6Du, 0x64u, 0x61u, 0x74u, 0x00u))), // ?? ?? ?? ?? 6D 64 61 74 00
            ),
            MediaType.Video.WEBM to listOf(
                Signature(Segment(0, ubyteArrayOf(0x6Du, 0x6Fu, 0x6Fu, 0x76u, 0x00u))), // 1A 45 DF A3
            ),
            MediaType.Text.Html to listOf(
                Signature(Segment(0, ubyteArrayOf(0x3Cu, 0x21u, 0x44u, 0x4Fu, 0x43u, 0x54u, 0x59u, 0x50u, 0x45u, 0x20u, 0x48u, 0x54u, 0x4Du, 0x4Cu,))), // 3C 21 44 4F 43 54 59 50 45 20 48 54 4D 4C
            ),
        )
        // @formatter:on
    }

    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding a result type instead of throwing exceptions for some use cases:
 *    sealed class ScanResult {
 *        object Valid : ScanResult()
 *        data class Invalid(val reason: String) : ScanResult()
 *    }
 *    This would allow collecting all validation failures instead of failing fast.
 *
 */

