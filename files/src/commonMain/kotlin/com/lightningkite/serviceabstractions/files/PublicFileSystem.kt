package com.lightningkite.serviceabstractions.files

import com.lightningkite.serviceabstractions.Service
import com.lightningkite.serviceabstractions.Setting
import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.UrlSettingParser
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * An abstracted model for reading and writing files in a storage solution.
 * Every implementation will handle how to resolve FileObjects in their own system.
 */
public interface PublicFileSystem : Service {
    /**
     * The root file object for this file system.
     */
    public val root: FileObject

    /**
     * The root URLs for this file system.
     */
    public val rootUrls: List<String> get() = listOf(root.url)

    public fun parseSignedUrlForRead(url: String): FileObject
    public fun parseSignedUrlForWrite(url: String): FileObject

    /**
     * Settings for a FileSystem.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "file://files"
    ) : Setting<PublicFileSystem> {

        public companion object : UrlSettingParser<PublicFileSystem>() {
            init {
                register("file") { url, context ->
                    val protocol = url.substringBefore("://")
                    if (protocol != "kotlinx-io") {
                        throw IllegalArgumentException("KotlinxIoPublicFileSystem only supports kotlinx-io:// URLs, got $protocol")
                    }

                    val path = Path(url.substringAfter("://").substringBefore("?").substringBefore("#"))
                    val params = url.substringAfter("?", "").substringBefore("#", "")
                        .takeIf { it.isNotEmpty() }
                        ?.split("&")
                        ?.associate { it.substringBefore("=") to it.substringAfter("=", "") }
                        ?: emptyMap()
                    val serveUrl = params["serveUrl"] ?: throw IllegalArgumentException("No serveUrl provided")
                    val serveDirectory = params["serveDirectory"] ?: throw IllegalArgumentException("No serveDirectory provided")

                    KotlinxIoPublicFileSystem(
                        context = context,
                        kotlinxIo = SystemFileSystem,
                        rootDirectory = path,
                        serveUrl = serveUrl,
                        serveDirectory = serveDirectory,
                    )
                }
            }
        }

        override fun invoke(context: SettingContext): PublicFileSystem {
            return parse(url, context)
        }
    }
}