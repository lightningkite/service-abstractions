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


public interface FileScanner : Service {
    public enum class Requires { Nothing, FirstSixteenBytes, Whole }

    public fun requires(claimedType: MediaType): Requires
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

public open class FileScanException(message: String, cause: Throwable? = null) : Exception(message, cause)

public suspend fun FileScanner.scan(item: TypedData): Unit = scan(item.mediaType, item.data.source())
public suspend fun FileScanner.copyAndScan(source: FileObject, destination: FileObject) {
    try {
        source.copyTo(destination)
        scan(source.get()!!)
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}

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

public suspend fun List<FileScanner>.copyAndScan(source: FileObject, destination: FileObject) {
    try {
        source.copyTo(destination)
        scan(source.get() ?: throw IllegalArgumentException("Source file ${source.url} does not exist."))
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}

public class CheckMimeFileScanner(
    override val name: String,
    override val context: SettingContext
) : FileScanner {
    override fun requires(claimedType: MediaType): FileScanner.Requires = FileScanner.Requires.FirstSixteenBytes
    override suspend fun scan(claimedType: MediaType, data: Source) {
        val bytes = data.use {
            data.readByteArray(16)
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

