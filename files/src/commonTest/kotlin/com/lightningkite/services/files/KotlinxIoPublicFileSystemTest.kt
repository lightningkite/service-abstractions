package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.files.test.FileSystemTests
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

class KotlinxIoPublicFileSystemTest : FileSystemTests() {
    override val system: PublicFileSystem =
        PublicFileSystem.Settings("file://local/test?serveUrl=http://localhost:8080/files")
            .invoke("test", TestSettingContext())

    @Test
    override fun testSignedUrlAccess() { /*skip, not hosted*/
    }

    @Test
    override fun testSignedUpload() { /*skip, not hosted*/
    }

    val kfileSystem = KotlinxIoPublicFileSystem(
        "files",
        TestSettingContext(),
        KFile("local/test"),
        serveUrl = "http://localhost:8080/files"
    )

    @Test
    fun uploadUrl() {
        println(kfileSystem.root.then("test.txt").uploadUrl(1.minutes))
    }

    @Test
    fun traversal() {
        assertFailsWith<IllegalArgumentException> { kfileSystem.root.then("../test.txt") }
        assertFailsWith<IllegalArgumentException> { kfileSystem.root.then("/../test.txt") }
        assertFailsWith<IllegalArgumentException> { kfileSystem.root.then("subfolder/../test.txt") }
        assertFailsWith<IllegalArgumentException> { kfileSystem.root.then("./test.txt") }
        assertFailsWith<IllegalArgumentException> { kfileSystem.root.then("/./test.txt") }
        assertFailsWith<IllegalArgumentException> { kfileSystem.root.then("subfolder/./test.txt") }
        kfileSystem.root.then("test..txt")
        kfileSystem.root.then("subfolder/test..txt")
        kfileSystem.root.then(".test..txt")
        kfileSystem.root.then("subfolder/.test..txt")
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