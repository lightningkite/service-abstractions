package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
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
     * Indicates how much of a file the scanner needs to read for validation.
     */
    public enum class Requires {
        /** The scanner doesn't need to read any file content */
        Nothing,
        /** The scanner needs the first 16 bytes (e.g., for magic number validation) */
        FirstSixteenBytes,
        /** The scanner needs the entire file content */
        Whole
    }

    /**
     * Determines how much data this scanner needs to validate a file of the given type.
     *
     * @param claimedType The media type claimed by the file
     * @return How much of the file needs to be read for this scanner
     */
    public fun requires(claimedType: MediaType): Requires

    /**
     * Scans the provided data stream to validate it matches the claimed type.
     *
     * @param claimedType The media type the file claims to be
     * @param data A source stream of the file data to scan
     * @throws FileScanException if validation fails (e.g., type mismatch, malware detected)
     */
    public suspend fun scan(claimedType: MediaType, data: Source)

    /**
     * Settings for a FileScanner.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "file://files"
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
 * Scans typed data using this file scanner.
 *
 * @param item The typed data to scan
 * @throws FileScanException if validation fails
 */
public suspend fun FileScanner.scan(item: TypedData): Unit = scan(item.mediaType, item.data.source())

/**
 * Copies a file and scans it, deleting the destination if scanning fails.
 *
 * This is useful for safely processing uploaded files - if they fail validation,
 * they won't remain in the destination location.
 *
 * @param source The source file to copy
 * @param destination The destination file location
 * @throws FileScanException if scanning fails
 */
public suspend fun FileScanner.copyAndScan(source: FileObject, destination: FileObject) {
    try {
        source.copyTo(destination)
        scan(source.get()!!)
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}

// TODO: Splittable stream - currently downloads entire file to scan with multiple scanners.
// Consider implementing a stream splitter to avoid multiple downloads for large files.
/**
 * Scans typed data using multiple file scanners in parallel.
 *
 * Note: Currently downloads the entire file to disk to enable multiple scanners to read it.
 * This may be inefficient for large files scanned by multiple scanners.
 *
 * @param item The typed data to scan
 * @throws FileScanException if any scanner fails validation
 */
public suspend fun List<FileScanner>.scan(item: TypedData) {
    // TODO Splittable stream
    coroutineScope {
        val asFile = item.download()
        val all = this@scan.map {
            val t = launch { it.scan(item.mediaType, asFile.source().buffered()) }
            t.start()
            t
        }
        all.joinAll()
    }
}

/**
 * Copies a file and scans it with multiple scanners, deleting the destination if any scanner fails.
 *
 * All scanners run in parallel. If any scanner fails, the destination is deleted.
 *
 * @param source The source file to copy
 * @param destination The destination file location
 * @throws FileScanException if any scanner fails validation
 * @throws IllegalArgumentException if the source file doesn't exist
 */
public suspend fun List<FileScanner>.copyAndScan(source: FileObject, destination: FileObject) {
    try {
        source.copyTo(destination)
        scan(source.get() ?: throw IllegalArgumentException("Source file ${source.url} does not exist."))
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}

/**
 * A FileScanner that validates files by checking their magic numbers (file signatures).
 *
 * This scanner reads the first 16 bytes of a file to verify that the binary signature
 * matches the claimed media type. This helps prevent users from uploading malicious
 * files disguised with incorrect extensions.
 *
 * Currently supports validation for:
 * - JPEG images (including EXIF format)
 * - GIF images
 * - TIFF images
 * - PNG images
 * - XML-based formats
 */
public class CheckMimeFileScanner(
    override val name: String,
    override val context: SettingContext
) : FileScanner {
    override fun requires(claimedType: MediaType): FileScanner.Requires = FileScanner.Requires.FirstSixteenBytes

    override suspend fun scan(claimedType: MediaType, data: Source) {
        val bytes = data.use {
            data.readByteArray(16)
        }

        // Validate we have enough bytes to perform magic number checks
        if (bytes.size < 16) {
            throw FileScanException(
                "File too small to validate (${bytes.size} bytes, need at least 16 bytes for magic number validation)"
            )
        }

        val c1 = bytes[0].toUByte().toInt()
        val c2 = bytes[1].toUByte().toInt()
        val c3 = bytes[2].toUByte().toInt()
        val c4 = bytes[3].toUByte().toInt()
        val c5 = bytes[4].toUByte().toInt()
        val c6 = bytes[5].toUByte().toInt()
        val c7 = bytes[6].toUByte().toInt()
        val c8 = bytes[7].toUByte().toInt()
        val c9 = bytes[8].toUByte().toInt()
        val c10 = bytes[9].toUByte().toInt()
        val c11 = bytes[10].toUByte().toInt()
        when (claimedType) {
            MediaType.Image.JPEG -> {
                if (c1 == 0xFF && c2 == 0xD8 && c3 == 0xFF) {
                    if (c4 == 0xE0 || c4 == 0xEE) {
                        return
                    }

                    /**
                     * File format used by digital cameras to store images.
                     * Exif Format can be read by any application supporting
                     * JPEG. Exif Spec can be found at:
                     * http://www.pima.net/standards/it10/PIMA15740/Exif_2-1.PDF
                     */
                    if ((c4 == 0xE1) &&
                        (c7 == 'E'.code && c8 == 'x'.code && c9 == 'i'.code && c10 == 'f'.code && c11 == 0)
                    ) {
                        return
                    }
                }
                throw FileScanException(
                    "Mime type mismatch; doesn't fit the JPEG format ${c1.toUByte().toString(16)} ${c2.toUByte().toString(16)} ${c3.toUByte().toString(16)} ${
                        c4.toUByte().toString(16)
                    }"
                )
            }

            MediaType.Image.GIF -> {
                if (c1 == 'G'.code && c2 == 'I'.code && c3 == 'F'.code && c4 == '8'.code) return
                throw FileScanException("Mime type mismatch; doesn't fit the GIF format")
            }

            MediaType.Image.Tiff -> {

                if ((c1 == 0x49 && c2 == 0x49 && c3 == 0x2a && c4 == 0x00)
                    || (c1 == 0x4d && c2 == 0x4d && c3 == 0x00 && c4 == 0x2a)
                ) {
                    return
                }
                throw FileScanException("Mime type mismatch; doesn't fit the TIFF format")
            }

            MediaType.Image.PNG -> {
                if (c1 == 137 && c2 == 80 && c3 == 78 &&
                    c4 == 71 && c5 == 13 && c6 == 10 &&
                    c7 == 26 && c8 == 10
                ) {
                    return
                }
                throw FileScanException("Mime type mismatch; doesn't fit the PNG format")
            }

            in MediaType.xmlTypes -> {
                if (bytes.decodeToString().trimStart().firstOrNull() == '<') return
//                if(bytes.decodeToString()Charsets.UTF_16BE).trimStart().firstOrNull() == '<') return
//                if(bytes.decodeToString()Charsets.UTF_16LE).trimStart().firstOrNull() == '<') return
//                if(bytes.decodeToString()Charsets.UTF_32LE).trimStart().firstOrNull() == '<') return
//                if(bytes.decodeToString()Charsets.UTF_32BE).trimStart().firstOrNull() == '<') return
                throw FileScanException("Mime type mismatch; doesn't fit the XML format")
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}

/*
 * TODO: API Recommendations
 *
 * 2. The CheckMimeFileScanner only validates a small set of image formats and XML.
 *    Consider:
 *    - Adding more common formats (PDF, ZIP, MP4, etc.)
 *    - Making the validation rules configurable/extensible
 *    - Providing a registry pattern for adding custom validators
 *
 * 3. The List<FileScanner>.scan() extension downloads the entire file to enable multiple
 *    scanners to read it. For large files with many scanners, consider:
 *    - Implementing a splittable/tee'd stream
 *    - Caching file content in memory for small files
 *    - Allowing scanners to declare if they can share a single pass
 *
 * 4. Consider adding a result type instead of throwing exceptions for some use cases:
 *    sealed class ScanResult {
 *        object Valid : ScanResult()
 *        data class Invalid(val reason: String) : ScanResult()
 *    }
 *    This would allow collecting all validation failures instead of failing fast.
 *
 * 5. The FileScanner.Requires enum doesn't have a way to specify custom byte amounts.
 *    Consider: data class Requires(val bytes: Int?) where null means whole file.
 */

