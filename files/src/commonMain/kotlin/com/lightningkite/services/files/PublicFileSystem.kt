package com.lightningkite.services.files

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.KFile
import com.lightningkite.services.data.TypedData
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

    public fun parseInternalUrl(url: String): FileObject?
    public fun parseExternalUrl(url: String): FileObject?


    override suspend fun healthCheck(): HealthStatus {
        return try {
            val testFile = root.then("health-check/test-file.txt")
            val contentData = Data.Text("Test Content")
            val content = TypedData(contentData, MediaType.Text.Plain)
            testFile.put(content)
            val retrieved = testFile.get()
            if (retrieved?.mediaType != MediaType.Text.Plain) {
                HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test write resulted in file of incorrect content type"
                )
            } else if (retrieved.data.text() != contentData.text()) {
                HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test content did not match"
                )
            } else {
                testFile.delete()
                HealthStatus(level = HealthStatus.Level.OK)
            }
        } catch (e: Exception) {
            HealthStatus(
                level = HealthStatus.Level.ERROR,
                additionalMessage = e.message
            )
        }
    }

    /**
     * Settings for a FileSystem.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "file://${KFile(SystemFileSystem, Path("local/files")).also { it.createDirectories() }.resolved}"
    ) : Setting<PublicFileSystem> {

        public companion object : UrlSettingParser<PublicFileSystem>() {
            init {
                register("file") { name, url, context ->
                    val protocol = url.substringBefore("://")

                    val path = url.substringAfter("://").substringBefore("?").substringBefore("#")
                    val params = url.substringAfter("?", "").substringBefore("#")
                        .takeIf { it.isNotEmpty() }
                        ?.split("&")
                        ?.associate { it.substringBefore("=") to it.substringAfter("=", "") }
                        ?: emptyMap()
                    val serveUrl = params["serveUrl"] ?: throw IllegalArgumentException("No serveUrl provided")

                    KotlinxIoPublicFileSystem(
                        name = name,
                        context = context,
                        rootKFile = KFile(path),
                        serveUrl = serveUrl + (if(serveUrl.endsWith("/")) "" else "/"),
                    )
                }
            }
        }

        override fun invoke(name: String, context: SettingContext): PublicFileSystem {
            return parse(name, url, context)
        }
    }
}