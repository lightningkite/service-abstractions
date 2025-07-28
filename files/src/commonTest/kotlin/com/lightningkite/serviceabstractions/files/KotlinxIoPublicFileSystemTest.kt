package com.lightningkite.serviceabstractions.files

import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.TestSettingContext
import com.lightningkite.serviceabstractions.files.test.FileSystemTests
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test

class KotlinxIoPublicFileSystemTest: FileSystemTests() {
    override val system: PublicFileSystem = KotlinxIoPublicFileSystem(
        context = TestSettingContext(),
        rootDirectory = Path("local/test")
    )
    @Test override fun testSignedUrlAccess() { /*skip, not hosted*/ }
    @Test override fun testSignedUpload() { /*skip, not hosted*/ }
}