package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.files.test.FileSystemTests
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

class KotlinxIoExternalFileSystemTest : FileSystemTests() {
    override val system: ExternalFileSystem =
        ExternalFileSystem.Settings("file://local/test?serveUrl=http://localhost:8080/files")
            .invoke("test", TestSettingContext())

    @Test
    override fun testSignedUrlAccess() { /*skip, not hosted*/
    }

    @Test
    override fun testSignedUpload() { /*skip, not hosted*/
    }

    val kfileSystem = KotlinxIoExternalFileSystem(
        "files",
        TestSettingContext(),
        KFile("local/test"),
        serveUrl = "http://localhost:8080/files"
    )

    @Test
    fun uploadUrl() {
        println(kfileSystem.root.then("test.txt").uploadUrl(1.minutes))
    }

    /**
     * [ExternalPath] segments are literal, so a path that reads as navigation can be built - a local
     * file system is the thing that has to refuse it, since it resolves paths against a real
     * hierarchy. Every operation must reject it, not just the ones a caller happens to test.
     */
    @Test
    fun traversal() = runTest {
        val escaping = listOf(
            ExternalPath(".."),
            ExternalPath("..", "test.txt"),
            ExternalPath("subfolder", "..", "..", "test.txt"),
            ExternalPath("."),
            ExternalPath("../test.txt"),      // one literal segment that contains a separator
            ExternalPath("..\\test.txt"),
            ExternalPath(""),
        )
        val content = TypedData(Data.Text("x"), MediaType.Text.Plain)
        for (path in escaping) {
            assertFailsWith<IllegalArgumentException>("get $path") { kfileSystem.get(path) }
            assertFailsWith<IllegalArgumentException>("getRange $path") { kfileSystem.getRange(path, 0L..1L) }
            assertFailsWith<IllegalArgumentException>("put $path") { kfileSystem.put(path, content) }
            assertFailsWith<IllegalArgumentException>("delete $path") { kfileSystem.delete(path) }
            assertFailsWith<IllegalArgumentException>("head $path") { kfileSystem.head(path) }
            assertFailsWith<IllegalArgumentException>("list $path") { kfileSystem.list(path) }
        }
        // Dots that are part of a longer name are ordinary names, not navigation.
        kfileSystem.root.then("test..txt")
        kfileSystem.root.then("subfolder/test..txt")
        kfileSystem.root.then(".test..txt")
        kfileSystem.root.then("subfolder/.test..txt")
    }

    /**
     * The serve URL is the one path an attacker can type, so parsing one must not be able to
     * manufacture a traversal - neither by writing it plainly nor by escaping it.
     */
    @Test
    fun servedUrlCannotTraverse() = runTest {
        val unsigned = KotlinxIoExternalFileSystem(
            "unsigned",
            TestSettingContext(),
            KFile("local/test"),
            serveUrl = "http://localhost:8080/files/",
        )
        assertFailsWith<IllegalArgumentException> {
            unsigned.parseExternalUrl("http://localhost:8080/files/../../etc/passwd")
        }
        // Escaped traversal parses to a literal segment, which the file system then refuses.
        val escaped = unsigned.parseExternalUrl("http://localhost:8080/files/_002e_002e/etc/passwd")!!
        assertEquals(listOf("..", "etc", "passwd"), escaped.path.parts)
        assertFailsWith<IllegalArgumentException> { escaped.get() }
    }

    /**
     * Content types are served back to browsers, so whoever can write a file must not be able to
     * choose the content type of a *different* file by writing its sidecar.
     */
    @Test
    fun contentTypeCannotBeForged() = runTest {
        val image = kfileSystem.root.then("photo.jpg")
        try {
            image.put(TypedData(Data.Text("not really a jpeg"), MediaType.Image.JPEG))

            // A file that merely looks like a sidecar is an ordinary file and changes nothing.
            val lookalike = kfileSystem.root.then("photo.jpg.contenttype")
            lookalike.put(TypedData(Data.Text("text/html"), MediaType.Text.Plain))
            assertEquals(MediaType.Image.JPEG, image.head()!!.type)
            assertEquals(MediaType.Image.JPEG, image.get()!!.mediaType)
            lookalike.delete()

            // The real sidecar lives in the reserved subtree, which is not addressable at all.
            assertFailsWith<IllegalArgumentException> {
                kfileSystem.root.then(".metadata/photo.jpg.contenttype")
                    .put(TypedData(Data.Text("text/html"), MediaType.Text.Plain))
            }
            assertFailsWith<IllegalArgumentException> { kfileSystem.root.then(".metadata").list() }
        } finally {
            image.delete()
        }
    }

    @Test
    fun signingKey() = runTest {
        // Path construction succeeds - the .signingKey path itself is a valid path. The guard
        // rejects direct operations against it, since it backs URL signing.
        assertFailsWith<IllegalArgumentException> {
            kfileSystem.root.then(".signingKey").put(TypedData(Data.Text("x"), MediaType.Text.Plain))
        }
        // Nested paths named .signingKey are unaffected - only the top-level file is reserved.
        kfileSystem.root.then("subfolder/.signingKey").put(TypedData(Data.Text("x"), MediaType.Text.Plain))
    }
}