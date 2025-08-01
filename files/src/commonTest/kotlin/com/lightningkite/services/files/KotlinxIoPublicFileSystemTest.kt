package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.files.test.FileSystemTests
import kotlinx.io.files.Path
import kotlin.test.Test

class KotlinxIoPublicFileSystemTest: FileSystemTests() {
    override val system: PublicFileSystem = KotlinxIoPublicFileSystem(
        context = TestSettingContext(),
        rootDirectory = Path("local/test")
    )
    @Test override fun testSignedUrlAccess() { /*skip, not hosted*/ }
    @Test override fun testSignedUpload() { /*skip, not hosted*/ }
}