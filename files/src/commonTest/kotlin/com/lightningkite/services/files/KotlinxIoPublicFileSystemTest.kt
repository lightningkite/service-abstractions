package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.KFile
import com.lightningkite.services.files.test.FileSystemTests
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
    fun signingKey() {
        assertFailsWith<IllegalArgumentException> { kfileSystem.root.then(".signingKey") }
        kfileSystem.root.then("subfolder/.signingKey")
    }
}