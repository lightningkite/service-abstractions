package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.files.test.FileSystemTests
import kotlinx.io.files.Path
import kotlin.test.Test

class KotlinxIoPublicFileSystemTest: FileSystemTests() {
    override val system: PublicFileSystem = PublicFileSystem.Settings("file://local/test?serveUrl=http://localhost:8080/files").invoke("test", TestSettingContext())
    @Test override fun testSignedUrlAccess() { /*skip, not hosted*/ }
    @Test override fun testSignedUpload() { /*skip, not hosted*/ }
}