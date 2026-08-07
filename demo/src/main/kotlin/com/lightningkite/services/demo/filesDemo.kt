package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.*
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

/**
 * Demonstrates the files subsystem via `file://`, the only local/credential-free scheme it
 * offers: write, read back, sign a URL, and delete, all against a temp directory that is
 * cleaned up afterward.
 */
fun main() = runBlocking {
    val root = createTempDirectory("service-abstractions-files-demo")
    try {
        val context = TestSettingContext()
        val fs = ExternalFileSystem.Settings("file://$root?serveUrl=files&signedUrlDuration=10m")("files", context)

        val file = fs.root.then("greeting.txt")
        file.put(TypedData(Data.Text("Hello from the files demo!"), MediaType.Text.Plain))

        val readBack = file.get()
        println("Read back: ${readBack?.text()}")
        println("Info: ${file.head()}")
        println("Signed URL: ${file.signUrl()}")

        file.delete()
        println("Still exists after delete: ${file.head() != null}")
    } finally {
        root.toFile().deleteRecursively()
    }
}
